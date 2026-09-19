"""Airflow launches JVM artifacts; templated values travel as literal argv via env."""

from __future__ import annotations

import json
import os
import re
import shlex
from collections.abc import Mapping, Sequence
from datetime import timedelta

from airflow.providers.standard.operators.bash import BashOperator


# --- Configuration resolved from the environment, with laptop-friendly defaults ---------------
PROJECT_HOME = os.environ.get("DATACRAFT_HOME", "/opt/datacraft")
CLI_JAR = os.environ.get("DATACRAFT_CLI_JAR", f"{PROJECT_HOME}/datacraft-cli.jar")
JAVA_BIN = os.environ.get("DATACRAFT_JAVA_BIN", "java")
SPARK_SUBMIT = os.environ.get("DATACRAFT_SPARK_SUBMIT", "spark-submit")
SPARK_MASTER = os.environ.get("DATACRAFT_SPARK_MASTER", "local[*]")
MAIN_CLASS = "com.example.datacraft.cli.Runner"

def _env_int(name: str, default: int, minimum: int) -> int:
    value = int(os.environ.get(name, str(default)))
    if value < minimum:
        raise ValueError(f"{name} must be at least {minimum}")
    return value


DEFAULT_ARGS = {
    "owner": "datacraft",
    "retries": _env_int("DATACRAFT_TASK_RETRIES", 1, 0),
    "retry_delay": timedelta(minutes=2),
    "retry_exponential_backoff": True,
    "max_retry_delay": timedelta(minutes=10),
    "execution_timeout": timedelta(minutes=_env_int("DATACRAFT_TASK_TIMEOUT_MINUTES", 60, 1)),
}


def _extra_tokens(value: str | Sequence[str]) -> list[str]:
    # Legacy strings are tokenized once, never evaluated as shell code.
    return shlex.split(value) if isinstance(value, str) else list(value)


def _param_tokens(params: Mapping[str, str] | None) -> list[str]:
    result = []
    for key, value in (params or {}).items():
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.-]*", key):
            raise ValueError(f"Invalid job parameter key: {key!r}")
        result.extend(("--param", f"{key}={value}"))
    return result


def _task(task_id: str, argv: list[str], *, structured=False, **kwargs) -> BashOperator:
    env = dict(kwargs.pop("env", None) or {})
    if any(key.startswith("_DATACRAFT_ARG_") for key in env):
        raise ValueError("_DATACRAFT_ARG_ environment names are reserved")
    references = []
    for index, value in enumerate(argv):
        if "\0" in str(value):
            raise ValueError("Command arguments must not contain NUL")
        key = f"_DATACRAFT_ARG_{index}"
        env[key] = str(value)
        references.append(f'"${{{key}}}"')
    kwargs.setdefault("append_env", True)
    kwargs.setdefault("do_xcom_push", False)
    kwargs.setdefault("skip_on_exit_code", None)
    command = " ".join(references)
    if structured:
        # Spark shutdown hooks write logs after Runner returns. A dedicated result file prevents
        # those lines from replacing the JSON value that BashOperator pushes to XCom.
        command = (
            "set -euo pipefail\n"
            "result_file=$(mktemp)\n"
            "trap 'rm -f -- \"$result_file\"' EXIT\n"
            + command + ' --result-file "$result_file"\n'
            + 'cat -- "$result_file"'
        )
    else:
        command = "exec " + command
    return BashOperator(task_id=task_id, bash_command=command, env=env, **kwargs)


def cli_task(task_id, command, *, params=None, lifecycle="dev", extra_args=(), **kwargs):
    """Run a non-Spark job. Extra args are argv tokens (legacy strings use shlex.split)."""
    return _task(task_id, [JAVA_BIN, "-jar", CLI_JAR, "--command", command, "--lifecycle", lifecycle]
                 + _param_tokens(params) + _extra_tokens(extra_args), **kwargs)


def spark_task(task_id, command, *, params=None, lifecycle="dev", master=None, extra_conf=(), **kwargs):
    """Run Spark with identical launcher/session masters and structured result XCom."""
    effective_master = SPARK_MASTER if master is None else master
    if params and "spark.master" in params and params["spark.master"] != effective_master:
        raise ValueError("spark.master must match the spark-submit master")
    kwargs.setdefault("do_xcom_push", True)
    kwargs.setdefault("output_processor", json.loads)
    return _task(task_id, [SPARK_SUBMIT, "--master", effective_master, "--class", MAIN_CLASS]
                 + _extra_tokens(extra_conf)
                 + [CLI_JAR, "--command", command, "--lifecycle", lifecycle, "--master", effective_master, "--json"]
                 + _param_tokens(params), structured=True, **kwargs)
