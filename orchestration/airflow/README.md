# datacraft-lab Airflow Orchestration

Airflow is the **orchestrator only** — it schedules and sequences the JVM artifacts (the
`datacraft-cli` jar) and never re-implements business logic. DAGs target **Apache Airflow 3.x** and
are tested against **3.3.1** with its matching provider constraints.

## Layout

| File | Role |
| --- | --- |
| `dags/datacraft_common.py` | Reusable helpers: env-driven config + `cli_task` / `spark_task` builders. Not a DAG (see `.airflowignore`). |
| `dags/datacraft_engine_jobs.py` | Smoke test — runs `noop` then `echo` via the plain CLI jar. |
| `dags/datacraft_spark_etl.py` | `csv-to-parquet` then `row-count` via `spark-submit`; parameterised paths. |
| `dags/datacraft_sftp_ingest.py` | SFTP download → `csv-to-parquet` → `row-count`. |
| `requirements.txt` | Extra providers layered on the Airflow image. |

## Configuration (environment variables)

These are set by the deployment images/compose and consumed by `datacraft_common.py`:

| Variable | Default | Meaning |
| --- | --- | --- |
| `DATACRAFT_HOME` | `/opt/datacraft` | Base directory inside the Airflow image. |
| `DATACRAFT_CLI_JAR` | `$DATACRAFT_HOME/datacraft-cli.jar` | Path to the built CLI jar. |
| `DATACRAFT_JAVA_BIN` | `java` | Java launcher for non-Spark jobs. |
| `DATACRAFT_SPARK_SUBMIT` | `spark-submit` | spark-submit launcher for Spark jobs. |
| `DATACRAFT_SPARK_MASTER` | `local[*]` | Spark master; point at `spark://…` / `k8s://…` for a real cluster. |
| `DATACRAFT_TASK_RETRIES` | `1` | Default task retry count. |
| `DATACRAFT_TASK_TIMEOUT_MINUTES` | `60` | Positive per-task execution timeout. |

## Running a Spark job on a real cluster

`spark_task` passes the same `--master` to both `spark-submit` and the CLI, so set
`DATACRAFT_SPARK_MASTER` (or the DAG-level `master=`) to your cluster URL. The jar is built with
Spark as a **provided** dependency, so `spark-submit` supplies Spark at runtime.

## SFTP connection

`datacraft_sftp_ingest` needs an Airflow connection `datacraft_sftp` (Admin → Connections, SSH/SFTP
type). The SFTP provider is required: a missing provider is a visible DAG import error. Downloads
create parent directories, and a failed download blocks conversion. Configure a trusted host key in
the connection; do not disable host-key verification for production.

All DAGs use UTC start dates, one active run per DAG, bounded task execution, and exponential retry
backoff. The default output paths belong to each DAG. When overriding paths, do not have different
DAGs write the same output concurrently. `overwrite` retries replace the dataset; `append` is not
retry-idempotent and is not used by these DAGs.

`cli_task` and `spark_task` render argument values in `env`, then reference each value as one quoted
shell argument. Shell syntax inside a parameter is data. `extra_args` / `extra_conf` accept argument
lists; legacy strings are split with `shlex.split` and are never shell programs.

Spark tasks exchange only small result dictionaries through XCom. The CLI writes JSON into a
task-owned temporary file; the wrapper reads it after Spark exits and removes it on success or
failure. This avoids treating Spark shutdown logs as results. `row_count` receives the conversion's
`metrics.rows` as `expectedRows` and fails when the persisted dataset count differs.

Both data DAGs accept `schema` (Spark DDL, empty means infer), `inferSchema`, `multiLine`, `header`
and `delimiter` in trigger configuration. Use explicit STRING/DECIMAL types for identifiers and
financial precision; see [data-processing contracts](../../docs/data-processing.md).

The local CSV/Parquet handoff requires shared storage across task instances. The supplied Swarm
configuration pins workers to `DATACRAFT_DATA_NODE` while using local volumes. Configure and verify
networked storage before removing that placement constraint.

## Tests (Linux / WSL)

```bash
python -m venv .venv
source .venv/bin/activate
pip install 'apache-airflow==3.3.1' -r orchestration/airflow/requirements.txt \
  --constraint https://raw.githubusercontent.com/apache/airflow/constraints-3.3.1/constraints-3.12.txt
PYTHONDONTWRITEBYTECODE=1 python -B -m unittest discover -s orchestration/airflow/tests -v

# Real DAG execution, packaged CLI, Spark, and a temporary local SFTP server:
pip install 'apache-airflow==3.3.1' 'pyspark==4.2.0'
python -B orchestration/airflow/tests/runtime_smoke.py \
  --jar datacraft-cli/target/datacraft-cli.jar
```

Build the JAR with `./mvnw verify` first and run with JDK 25.0.3+. The runtime smoke initializes an
isolated metadata database twice, runs all three DAGs (ETL twice), verifies SFTP bytes, and cleans its
temporary credentials, files and logs. It does not contact a real SFTP account or deploy a stack.
