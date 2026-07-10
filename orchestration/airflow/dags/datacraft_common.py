"""Reusable building blocks for datacraft-lab Airflow DAGs.

Targets Apache Airflow 3.x (the ``airflow.sdk`` / standard-provider API) and degrades gracefully to
Airflow 2.x imports so the DAGs still parse in an older local install.

Design intent: DAGs orchestrate *built artifacts* (the ``datacraft-cli`` jar) and environment, never
duplicating JVM business logic. All wiring is driven from environment variables (12-factor) so the
same DAGs run unchanged on a laptop, a single Compose host, or a Swarm cluster.
"""

from __future__ import annotations

import os
from datetime import timedelta

try:  # Airflow 3.x
    from airflow.providers.standard.operators.bash import BashOperator
except ImportError:  # Airflow 2.x fallback
    from airflow.operators.bash import BashOperator


# --- Configuration resolved from the environment, with laptop-friendly defaults ---------------
PROJECT_HOME = os.environ.get("DATACRAFT_HOME", "/opt/datacraft")
CLI_JAR = os.environ.get("DATACRAFT_CLI_JAR", f"{PROJECT_HOME}/datacraft-cli.jar")
JAVA_BIN = os.environ.get("DATACRAFT_JAVA_BIN", "java")
SPARK_SUBMIT = os.environ.get("DATACRAFT_SPARK_SUBMIT", "spark-submit")
SPARK_MASTER = os.environ.get("DATACRAFT_SPARK_MASTER", "local[*]")
MAIN_CLASS = "com.example.datacraft.cli.Runner"

DEFAULT_ARGS = {
    "owner": "datacraft",
    "retries": int(os.environ.get("DATACRAFT_TASK_RETRIES", "1")),
    "retry_delay": timedelta(minutes=2),
}


def _shell_quote(value) -> str:
    """POSIX single-quote escaping for a value interpolated into a bash command."""
    text = str(value)
    quote = "'"
    escaped = text.replace(quote, quote + chr(92) + quote + quote)
    return quote + escaped + quote


def _params_to_args(params) -> str:
    if not params:
        return ""
    return " ".join(f"--param {key}={_shell_quote(value)}" for key, value in params.items())


def cli_task(task_id, command, *, params=None, lifecycle="dev", extra_args="", **kwargs):
    """BashOperator running a non-Spark engine command through the plain CLI jar."""
    bash_command = (
        f'"{JAVA_BIN}" -jar "{CLI_JAR}" '
        f"--command {command} --lifecycle {lifecycle} "
        f"{_params_to_args(params)} {extra_args}"
    ).strip()
    return BashOperator(task_id=task_id, bash_command=bash_command, **kwargs)


def spark_task(task_id, command, *, params=None, lifecycle="dev", master=None, extra_conf="", **kwargs):
    """BashOperator running a Spark engine job through ``spark-submit``.

    The same master is passed to both ``spark-submit`` and the CLI so the session the job opens
    matches the cluster spark-submit targets.
    """
    effective_master = master or SPARK_MASTER
    bash_command = (
        f'"{SPARK_SUBMIT}" --master {effective_master} --class {MAIN_CLASS} {extra_conf} '
        f'"{CLI_JAR}" '
        f"--command {command} --lifecycle {lifecycle} --master {effective_master} "
        f"{_params_to_args(params)}"
    ).strip()
    return BashOperator(task_id=task_id, bash_command=bash_command, **kwargs)
