"""SFTP ingest pipeline: download a remote CSV, convert it to Parquet, verify the row count.

Prerequisites:
  * The SFTP provider (``apache-airflow-providers-sftp``) is installed (see requirements.txt).
  * An Airflow connection named ``datacraft_sftp`` exists (Admin -> Connections, type SSH/SFTP).

The download step uses Airflow's native SFTP provider (connection + secret management handled by
Airflow); the JVM ``SftpClient`` remains available for programmatic transfers inside JVM jobs.
"""

from __future__ import annotations

from datetime import datetime

try:  # Airflow 3.x
    from airflow.sdk import DAG, Param
except ImportError:  # Airflow 2.x fallback
    from airflow import DAG
    from airflow.models.param import Param

from datacraft_common import DEFAULT_ARGS, cli_task, spark_task

try:
    from airflow.providers.sftp.operators.sftp import SFTPOperator

    _SFTP_AVAILABLE = True
except ImportError:  # keep the DAG parseable where the provider is absent
    _SFTP_AVAILABLE = False

with DAG(
    dag_id="datacraft_sftp_ingest",
    description="Downloads a CSV over SFTP, converts it to Parquet, and verifies the row count.",
    start_date=datetime(2026, 1, 1),
    schedule=None,
    catchup=False,
    default_args=DEFAULT_ARGS,
    params={
        "remote_path": Param("/upload/input.csv", type="string"),
        "local_path": Param("/opt/datacraft/data/ingest/input.csv", type="string"),
        "output": Param("/opt/datacraft/data/ingest/output.parquet", type="string"),
    },
    tags=["datacraft", "sftp", "ingest"],
) as dag:
    if _SFTP_AVAILABLE:
        download = SFTPOperator(
            task_id="download",
            ssh_conn_id="datacraft_sftp",
            remote_filepath="{{ params.remote_path }}",
            local_filepath="{{ params.local_path }}",
            operation="get",
        )
    else:
        download = cli_task("download", "noop")

    convert = spark_task(
        "csv_to_parquet",
        "csv-to-parquet",
        params={"input": "{{ params.local_path }}", "output": "{{ params.output }}"},
    )
    count = spark_task(
        "row_count",
        "row-count",
        params={"input": "{{ params.output }}", "inputFormat": "parquet"},
    )

    download >> convert >> count
