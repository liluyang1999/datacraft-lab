# datacraft-lab Airflow Orchestration

Airflow is the **orchestrator only** — it schedules and sequences the JVM artifacts (the
`datacraft-cli` jar) and never re-implements business logic. DAGs target **Apache Airflow 3.x** and
are tested against **3.3.2** with its matching provider constraints.

## Layout

The DAGs and their extra Python requirements live in `orchestration/airflow/`; their tests live
under `tests/`. Paths below are relative to the repository root.

| File | Role |
| --- | --- |
| `orchestration/airflow/dags/datacraft_common.py` | Reusable helpers: env-driven config + `cli_task` / `spark_task` builders. Not a DAG; excluded from discovery by `orchestration/airflow/dags/.airflowignore` (Airflow 3 glob syntax). |
| `orchestration/airflow/dags/datacraft_engine_jobs.py` | Smoke test — runs `noop` then `echo` via the plain CLI jar. |
| `orchestration/airflow/dags/datacraft_spark_etl.py` | `csv-to-parquet` then `row-count` via `spark-submit`; parameterised paths. |
| `orchestration/airflow/dags/datacraft_sftp_ingest.py` | SFTP download → `csv-to-parquet` → `row-count`. |
| `orchestration/airflow/requirements.txt` | Extra providers layered on the Airflow image. |
| `tests/orchestration/test_dags.py` | DAG contract tests: real parsing, template rendering and Bash execution (needs Airflow). |
| `tests/smoke/airflow_runtime_smoke.py` | Real DAG runs with the packaged CLI jar, Spark and a temporary local SFTP server. |

Each DAG declares its task order with `chain(...)` from `airflow.sdk`, for example
`chain(download, convert, count)`, rather than with a bare `a >> b` expression.

## Configuration (environment variables)

`datacraft_common.py` reads these when a DAG is parsed. The Airflow image sets `DATACRAFT_HOME`,
`DATACRAFT_CLI_JAR`, `DATACRAFT_SPARK_SUBMIT` and `DATACRAFT_SPARK_MASTER`; the others use the
defaults below. To override one of the others, add it to `deploy/compose/.env`, which the Compose
and Swarm stacks pass to every Airflow container through `env_file`; both stacks also read
`DATACRAFT_SPARK_MASTER` from that file. The exception is `DATACRAFT_DATA_ROOT`: both stacks set it
to `/opt/datacraft/data`, where the `datacraft-data` volume is mounted, under `environment:`, which
takes precedence over `env_file`. To change it, edit the stack files and move the volume mount with
it. An invalid `DATACRAFT_DATA_ROOT`, or a non-integer or
too-small `DATACRAFT_TASK_RETRIES` or `DATACRAFT_TASK_TIMEOUT_MINUTES`, makes every DAG an import
error that names the variable. The launcher and jar paths are not checked until a task runs.

| Variable | Default | Meaning |
| --- | --- | --- |
| `DATACRAFT_HOME` | `/opt/datacraft` | Base directory inside the Airflow image. |
| `DATACRAFT_CLI_JAR` | `$DATACRAFT_HOME/datacraft-cli.jar` | Path to the built CLI jar. |
| `DATACRAFT_DATA_ROOT` | `$DATACRAFT_HOME/data` | Absolute directory other than `/` that confines the DAGs' data path parameters (see below). It must not start with `//` (Hadoop reads that as a host) or carry surrounding whitespace; other duplicate and trailing slashes are dropped. |
| `DATACRAFT_JAVA_BIN` | `java` | Java launcher for non-Spark jobs. |
| `DATACRAFT_SPARK_SUBMIT` | `spark-submit` | spark-submit launcher for Spark jobs. |
| `DATACRAFT_SPARK_MASTER` | `local[*]` | Spark master. Only local mode is tested; a `spark://…` master also needs the prerequisites below. |
| `DATACRAFT_TASK_RETRIES` | `1` | Default task retry count (integer, 0 or more). |
| `DATACRAFT_TASK_TIMEOUT_MINUTES` | `60` | Positive per-task execution timeout. |

## Data paths

The trigger-conf paths `input`/`output` (`datacraft_spark_etl`) and `local_path`/`output`
(`datacraft_sftp_ingest`) must be absolute paths inside `DATACRAFT_DATA_ROOT`: at least one segment
below the root, no `.` or `..` segments, no NUL, CR or LF characters, no trailing whitespace or
control character (the JVM jobs trim values, so they would read another file), and none of the Hadoop glob
characters `{ } [ ] * ? \`. Anything else is rejected when the run is triggered. Spark would expand
glob characters when it reads a path (`{../..}` becomes `../..`), so refusing them keeps each path
exactly as written; the root must not contain them either. Relative paths could never work here,
because each task runs in its own temporary working directory that is deleted when the task ends.
`remote_path` is bounded by the SFTP account instead. Spark overwrite deletes its target before
writing, so permission to trigger these DAGs means permission to replace or delete data inside the
root as the Airflow user.

## Running a Spark job on a real cluster

`spark_task` passes the same `--master` to both `spark-submit` and the CLI, so set
`DATACRAFT_SPARK_MASTER` (or pass `master=` to `spark_task` in DAG code) to your cluster URL. The jar
is built with Spark as a **provided** dependency, so `spark-submit` supplies Spark at runtime.

Only local mode is tested. A remote `spark://…` master runs the driver in client mode inside the
Airflow worker and requires:

- data paths that the driver and every executor see at the same location: a shared mount at the
  same `DATACRAFT_DATA_ROOT` path on every host (the path parameters accept only local paths under
  the root; S3A is not configured);
- executors that can reach the driver: set `spark.driver.host`, `spark.driver.port` and
  `spark.blockManager.port` through `extra_conf` or `spark-defaults.conf`, and open those ports;
- Java 25 and Spark 4.2.0 on the executors.

See section 6 of the [deployment guide](deployment.md) (in Chinese).

## SFTP connection

`datacraft_sftp_ingest` needs an Airflow connection `datacraft_sftp` (Admin → Connections, SSH/SFTP
type). The SFTP provider is required: a missing provider is a visible DAG import error. Downloads
create parent directories, and a failed download blocks conversion.

The SSH provider accepts any server key unless the connection says otherwise: without `host_key` it
defaults `no_host_key_check` to true and only logs a warning. Pin the server key in the connection
extra, for example `{"host_key": "ssh-ed25519 AAAA..."}`, or set `"no_host_key_check": false` and
provide a `known_hosts` file for the Airflow user. Never set `no_host_key_check: true` or
`allow_host_key_change: true`. The download task checks the connection before connecting and fails
without retrying otherwise, or when the provider rejects the settings (for example a malformed or
`ssh-dss` `host_key`), so add the key to existing connections before upgrading.

All DAGs use UTC start dates, one active run per DAG, bounded task execution, and exponential retry
backoff. The default output paths belong to each DAG. When overriding paths, do not have different
DAGs write the same output concurrently. `overwrite` retries replace the dataset; `append` is not
retry-idempotent and is not used by these DAGs.

`cli_task` and `spark_task` render argument values in `env`, then reference each value as one quoted
shell argument. Shell syntax inside a parameter is data. `extra_args` / `extra_conf` accept argument
lists; legacy strings are split with `shlex.split` and are never shell programs. Values ending in
`.sh` or `.bash` are passed literally too; they are never loaded as Jinja template files.

Spark tasks exchange only small result dictionaries through XCom. The CLI writes JSON into a
task-owned temporary file; the wrapper reads it after Spark exits and removes it on success or
failure. This avoids treating Spark shutdown logs as results. `row_count` receives the conversion's
`metrics.rows` as `expectedRows` and fails when the persisted dataset count differs.

Both data DAGs accept `schema` (Spark DDL, empty means infer), `inferSchema`, `multiLine`, `header`
and `delimiter` in trigger configuration. Use explicit STRING/DECIMAL types for identifiers and
financial precision; see the [data processing contracts](../../design/data-contracts.md).

The local CSV/Parquet handoff requires shared storage across task instances. The supplied Swarm
configuration pins workers to `DATACRAFT_DATA_NODE` while using local volumes. Configure and verify
networked storage before removing that placement constraint.

## Tests (Linux / WSL)

Run these commands from the repository root. Install Airflow with the constraints file for your
environment's own Python minor version; the commands below derive it the same way
`deploy/docker/Dockerfile.airflow` does. CI does the same with `cicd/airflow/install-airflow.sh`
on Python 3.13, the Python of the `apache/airflow:3.3.2` base image; inside an activated virtual
environment the script installs the providers and pyspark in one step.

```bash
python -m venv .venv
source .venv/bin/activate
python_minor=$(python -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')
pip install 'apache-airflow==3.3.2' -r orchestration/airflow/requirements.txt \
  --constraint "https://raw.githubusercontent.com/apache/airflow/constraints-3.3.2/constraints-${python_minor}.txt"
PYTHONDONTWRITEBYTECODE=1 python -B -m unittest discover -s tests/orchestration -v

# Real DAG execution, packaged CLI, Spark, and a temporary local SFTP server:
pip install 'apache-airflow==3.3.2' 'pyspark==4.2.0'
python -B tests/smoke/airflow_runtime_smoke.py \
  --jar modules/interfaces/datacraft-cli/target/datacraft-cli.jar
```

Build the JAR with `./mvnw verify` first (it writes
`modules/interfaces/datacraft-cli/target/datacraft-cli.jar`) with JDK 25.0.4.1 or newer, which the
build requires, and run it with a Java 25 runtime. The runtime smoke initializes an isolated metadata database twice, runs all three
DAGs (ETL twice) with `DATACRAFT_DATA_ROOT` set to its temporary data directory, verifies SFTP bytes
and the row count and exact `note` values of both Parquet outputs, and cleans its temporary
credentials, files and logs. It does not contact a real SFTP account or deploy a stack.
