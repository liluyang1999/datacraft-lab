# datacraft-lab Airflow Orchestration

Airflow is the **orchestrator only** — it schedules and sequences the JVM artifacts (the
`datacraft-cli` jar) and never re-implements business logic. DAGs target **Apache Airflow 3.x** and
fall back to 2.x imports so they still parse in an older local install.

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

## Running a Spark job on a real cluster

`spark_task` passes the same `--master` to both `spark-submit` and the CLI, so set
`DATACRAFT_SPARK_MASTER` (or the DAG-level `master=`) to your cluster URL. The jar is built with
Spark as a **provided** dependency, so `spark-submit` supplies Spark at runtime.

## SFTP connection

`datacraft_sftp_ingest` needs an Airflow connection `datacraft_sftp` (Admin → Connections, SSH/SFTP
type). Without the SFTP provider installed the download step degrades to a `noop` so the DAG still
parses.

## Local syntax check

```bash
python -m py_compile orchestration/airflow/dags/*.py
```
