"""CSV -> Parquet ETL with a row-count verification, executed via spark-submit.

Trigger with config to override the input/output paths, e.g.::

    {"input": "/opt/datacraft/data/sales.csv", "output": "/opt/datacraft/data/sales.parquet"}
"""

from __future__ import annotations

from datetime import datetime, timezone

try:  # Airflow 3.x
    from airflow.sdk import DAG, Param
except ImportError:  # Airflow 2.x fallback
    from airflow import DAG
    from airflow.models.param import Param

from datacraft_common import DEFAULT_ARGS, spark_task

with DAG(
    dag_id="datacraft_spark_etl",
    description="Converts a CSV dataset to Parquet and verifies the output row count.",
    start_date=datetime(2026, 1, 1, tzinfo=timezone.utc),
    max_active_runs=1,
    schedule=None,
    catchup=False,
    default_args=DEFAULT_ARGS,
    params={
        "input": Param("/opt/datacraft/data/input.csv", type="string", minLength=1),
        "output": Param("/opt/datacraft/data/output.parquet", type="string", minLength=1),
        "header": Param("true", enum=["true", "false"]),
        "delimiter": Param(",", type="string", minLength=1),
        "schema": Param("", type="string"),
        "inferSchema": Param("true", enum=["true", "false"]),
        "multiLine": Param("true", enum=["true", "false"]),
    },
    tags=["datacraft", "spark", "etl"],
) as dag:
    convert = spark_task(
        "csv_to_parquet",
        "csv-to-parquet",
        params={
            "input": "{{ params.input }}",
            "output": "{{ params.output }}",
            "header": "{{ params.header }}",
            "delimiter": "{{ params.delimiter }}",
            "schema": "{{ params.schema }}",
            "inferSchema": "{{ params.inferSchema }}",
            "multiLine": "{{ params.multiLine }}",
        },
    )
    count = spark_task(
        "row_count",
        "row-count",
        params={
            "input": "{{ params.output }}", "inputFormat": "parquet",
            "expectedRows": "{{ ti.xcom_pull(task_ids='csv_to_parquet')['metrics']['rows'] }}",
        },
    )

    convert >> count
