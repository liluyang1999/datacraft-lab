"""Airflow launches JVM artifacts; templated values travel as literal argv via env."""

from __future__ import annotations

import json
import os
import re
import shlex
from collections.abc import Mapping, Sequence
from datetime import timedelta

from airflow.providers.standard.operators.bash import BashOperator
from airflow.sdk import Param
from airflow.sdk.exceptions import AirflowFailException


# --- Configuration resolved from the environment, with laptop-friendly defaults ---------------
PROJECT_HOME = os.environ.get("DATACRAFT_HOME", "/opt/datacraft")
CLI_JAR = os.environ.get("DATACRAFT_CLI_JAR", f"{PROJECT_HOME}/datacraft-cli.jar")
JAVA_BIN = os.environ.get("DATACRAFT_JAVA_BIN", "java")
SPARK_SUBMIT = os.environ.get("DATACRAFT_SPARK_SUBMIT", "spark-submit")
SPARK_MASTER = os.environ.get("DATACRAFT_SPARK_MASTER", "local[*]")
MAIN_CLASS = "com.example.datacraft.cli.Runner"


# Spark globs a read path containing any of {}[]*?\ (Hadoop expands root/{../..}/x and unescapes
# root/\.\./x before resolving it), so data paths refuse them; NUL, CR and LF are never valid.
_UNSAFE_PATH_CHARS = r"[\x00\n\r{}\[\]*?\\]"


def _data_root() -> str:
    raw = os.environ.get("DATACRAFT_DATA_ROOT", f"{PROJECT_HOME}/data")
    # Paths are matched against the root literally, so spell it the way paths are normally written.
    # Hadoop reads a leading "//" as a host name and the JVM jobs trim the variable, so both are
    # refused here rather than normalised differently on each side.
    root = re.sub("/+", "/", raw).rstrip("/")
    if (not root.startswith("/") or raw.startswith("//") or raw != raw.strip()
            or re.search(_UNSAFE_PATH_CHARS, root)):
        raise ValueError(
            "DATACRAFT_DATA_ROOT (default $DATACRAFT_HOME/data) must be an absolute directory "
            "other than / that does not start with // or carry surrounding whitespace, without "
            f"any of {{}}[]*?\\ or NUL, CR, LF, got {raw!r}"
        )
    return root


# Trigger-conf file paths are confined here: Spark overwrite deletes its target before writing.
DATA_ROOT = _data_root()


def _env_int(name: str, default: int, minimum: int) -> int:
    raw = os.environ.get(name)
    if raw is None:
        return default
    try:
        value = int(raw)
    except ValueError:
        raise ValueError(f"{name} must be an integer, got {raw!r}") from None
    if value < minimum:
        raise ValueError(f"{name} must be at least {minimum}")
    return value


DEFAULT_ARGS = {
    "owner": "datacraft",
    "retries": _env_int("DATACRAFT_TASK_RETRIES", 1, 0),
    "retry_delay": timedelta(minutes=2),
    # Airflow 3 takes a float multiplier (0 = constant delay); 2.0 doubles the delay per retry.
    "retry_exponential_backoff": 2.0,
    "max_retry_delay": timedelta(minutes=10),
    "execution_timeout": timedelta(minutes=_env_int("DATACRAFT_TASK_TIMEOUT_MINUTES", 60, 1)),
}


def _data_path_pattern(root: str) -> str:
    # Spark (Hadoop Path) and the SFTP download resolve "." and ".." segments, so a prefix check
    # alone would let root/../x escape. _UNSAFE_PATH_CHARS are refused anywhere, so Spark reads the
    # literal path and "$" cannot match before a trailing newline. At least one real segment must
    # follow the root itself. The JVM jobs trim their parameters, so a trailing space or control
    # character would make them read another file than the SFTP download wrote.
    return (rf"^(?![\s\S]*{_UNSAFE_PATH_CHARS}){re.escape(root)}(?:/+(?!\.\.?(?:/|$))[^/]+)+/*"
            r"(?<![\x00-\x20])$")


def data_path_param(default_name: str, description: str) -> Param:
    """Trigger-conf path that must stay under DATA_ROOT; defaults to ``DATA_ROOT/default_name``."""
    return Param(
        f"{DATA_ROOT}/{default_name}",
        description=f"{description} (absolute path under {DATA_ROOT}; no . or .. segments or {{}}[]*?\\)",
        type="string",
        minLength=1,
        pattern=_data_path_pattern(DATA_ROOT),
    )


def require_verified_sftp_host(conn_id: str):
    """Return a ``pre_execute`` hook that fails the task unless ``conn_id`` verifies the host key.

    The SSH provider accepts any server key (with only a log warning) unless the connection pins
    ``host_key`` or sets ``no_host_key_check`` to false, so the guard refuses to download otherwise.
    Settings the provider rejects, such as a malformed or DSS ``host_key``, also fail without retry.
    """

    def check(context):
        from airflow.providers.sftp.hooks.sftp import SFTPHook
        from paramiko import SSHException

        try:
            hook = SFTPHook(ssh_conn_id=conn_id)  # Reads the connection only; no network I/O.
        except (ValueError, SSHException) as err:
            raise AirflowFailException(f"Connection {conn_id!r} has invalid SSH settings: {err}") from err
        if hook.no_host_key_check or hook.allow_host_key_change:
            raise AirflowFailException(
                f"Connection {conn_id!r} must verify the SFTP host key: set the host_key extra and "
                "do not enable no_host_key_check or allow_host_key_change"
            )

    return check


class _ArgvBashOperator(BashOperator):
    """Argument values render as Jinja strings, never as ``.sh``/``.bash`` template files."""

    template_ext = ()


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
    return _ArgvBashOperator(task_id=task_id, bash_command=command, env=env, **kwargs)


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
