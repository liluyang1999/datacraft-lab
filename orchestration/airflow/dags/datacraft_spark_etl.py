"""CSV -> Parquet ETL with a row-count verification, executed via spark-submit.

Trigger with config to override the input/output paths, e.g.::

    {"input": "/opt/datacraft/data/sales.csv", "output": "/opt/datacraft/data/sales.parquet"}

Both paths must be absolute and inside ``DATACRAFT_DATA_ROOT`` (default ``/opt/datacraft/data``),
without ``.``/``..`` segments or Hadoop glob characters.
"""

from __future__ import annotations

from datetime import datetime, timezone

from airflow.sdk import DAG, Param, chain

from datacraft_common import DEFAULT_ARGS, data_path_param, spark_task

with DAG(
    dag_id="datacraft_spark_etl",
    description="Converts a CSV dataset to Parquet and verifies the output row count.",
    start_date=datetime(2026, 1, 1, tzinfo=timezone.utc),
    max_active_runs=1,
    schedule=None,
    catchup=False,
    default_args=DEFAULT_ARGS,
    params={
        "input": data_path_param("input.csv", "CSV file to convert"),
        "output": data_path_param("output.parquet", "Parquet dataset to overwrite"),
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

    chain(convert, count)
