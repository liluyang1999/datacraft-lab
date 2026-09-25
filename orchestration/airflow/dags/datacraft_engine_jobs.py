"""Smoke / health DAG: exercises the non-Spark engine jobs through the CLI jar.

Use this to verify a fresh deployment: it runs ``noop`` then ``echo`` and should both succeed.
"""

from __future__ import annotations

from datetime import datetime, timezone

from airflow.sdk import DAG

from datacraft_common import DEFAULT_ARGS, cli_task

with DAG(
    dag_id="datacraft_engine_jobs",
    description="Runs the built-in echo and noop engine jobs to verify the platform.",
    start_date=datetime(2026, 1, 1, tzinfo=timezone.utc),
    max_active_runs=1,
    schedule=None,
    catchup=False,
    default_args=DEFAULT_ARGS,
    tags=["datacraft", "engine", "smoke"],
) as dag:
    noop = cli_task("noop", "noop")
    echo = cli_task("echo", "echo", params={"message": "hello from airflow"})

    noop >> echo
