"""SFTP ingest pipeline: download a remote CSV, convert it to Parquet, verify the row count.

Prerequisites:
  * The SFTP provider (``apache-airflow-providers-sftp``) is installed (see requirements.txt).
  * An Airflow connection named ``datacraft_sftp`` exists (Admin -> Connections, type SSH/SFTP)
    and verifies the server key, e.g. extra ``{"host_key": "ssh-ed25519 AAAA..."}``. The download
    task fails without retrying when the connection would accept any host key.

``local_path`` and ``output`` must be absolute paths inside ``DATACRAFT_DATA_ROOT``, without
``.``/``..`` segments or Hadoop glob characters; the SFTP account bounds ``remote_path``.

The download step uses Airflow's native SFTP provider (connection + secret management handled by
Airflow). The JVM ``SftpClient`` is a library for future JVM jobs; no shipped job uses it.
"""

from __future__ import annotations

from datetime import datetime, timezone

from airflow.providers.sftp.operators.sftp import SFTPOperator
from airflow.sdk import DAG, Param, chain

from datacraft_common import DEFAULT_ARGS, data_path_param, require_verified_sftp_host, spark_task

with DAG(
    dag_id="datacraft_sftp_ingest",
    description="Downloads a CSV over SFTP, converts it to Parquet, and verifies the row count.",
    start_date=datetime(2026, 1, 1, tzinfo=timezone.utc),
    max_active_runs=1,
    schedule=None,
    catchup=False,
    default_args=DEFAULT_ARGS,
    params={
        "remote_path": Param("/upload/input.csv", type="string", minLength=1),
        "local_path": data_path_param("ingest/input.csv", "Download destination for the remote CSV"),
        "output": data_path_param("ingest/output.parquet", "Parquet dataset to overwrite"),
        "header": Param("true", enum=["true", "false"]),
        "delimiter": Param(",", type="string", minLength=1),
        "schema": Param("", type="string"),
        "inferSchema": Param("true", enum=["true", "false"]),
        "multiLine": Param("true", enum=["true", "false"]),
    },
    tags=["datacraft", "sftp", "ingest"],
) as dag:
    download = SFTPOperator(
        task_id="download",
        ssh_conn_id="datacraft_sftp",
        remote_filepath="{{ params.remote_path }}",
        local_filepath="{{ params.local_path }}",
        operation="get",
        create_intermediate_dirs=True,
        pre_execute=require_verified_sftp_host("datacraft_sftp"),
    )

    convert = spark_task(
        "csv_to_parquet",
        "csv-to-parquet",
        params={
            "input": "{{ params.local_path }}", "output": "{{ params.output }}",
            "header": "{{ params.header }}", "delimiter": "{{ params.delimiter }}",
            "schema": "{{ params.schema }}", "inferSchema": "{{ params.inferSchema }}",
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

    chain(download, convert, count)
