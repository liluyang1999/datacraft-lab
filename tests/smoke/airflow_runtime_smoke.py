"""Real DAG runs against the packaged CLI, Spark and a temporary local SFTP server.

Run with Python from an Airflow 3.3.2 environment that also has pyspark 4.2.0.
All metadata, credentials, logs, downloads and Parquet outputs live in a temporary directory.
"""

import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[2]


@contextmanager
def sftp_source(source):
    import paramiko
    from paramiko.common import (
        AUTH_FAILED, AUTH_SUCCESSFUL, OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED, OPEN_SUCCEEDED)
    from paramiko.sftp import SFTP_NO_SUCH_FILE, SFTP_PERMISSION_DENIED

    key = paramiko.RSAKey.generate(2048)
    secret = secrets.token_urlsafe(24)

    class Server(paramiko.ServerInterface):
        def check_auth_password(self, username, password):
            return AUTH_SUCCESSFUL if username == "datacraft-test" and password == secret else AUTH_FAILED

        def get_allowed_auths(self, username):
            return "password"

        def check_channel_request(self, kind, chanid):
            return OPEN_SUCCEEDED if kind == "session" else OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    class ReadHandle(paramiko.SFTPHandle):
        """paramiko serves reads from the handle's readfile attribute."""

        def __init__(self, flags, readfile):
            super().__init__(flags)
            self.readfile = readfile

    class Files(paramiko.SFTPServerInterface):
        def stat(self, path):
            if path != "/input.csv":
                return SFTP_NO_SUCH_FILE
            return paramiko.SFTPAttributes.from_stat(source.stat())

        lstat = stat

        def open(self, path, flags, attr):
            if path != "/input.csv" or flags & (os.O_WRONLY | os.O_RDWR):
                return SFTP_PERMISSION_DENIED
            return ReadHandle(flags, source.open("rb"))

    listener = socket.socket()
    listener.bind(("127.0.0.1", 0))
    listener.listen()
    listener.settimeout(0.2)
    stop = threading.Event()
    transports = []
    failures = []

    def serve():
        while not stop.is_set():
            try:
                client, _ = listener.accept()
            except socket.timeout:
                continue
            except OSError:
                if not stop.is_set():
                    failures.append("SFTP listener closed unexpectedly")
                break
            transport = paramiko.Transport(client)
            transports.append(transport)
            transport.add_server_key(key)
            transport.set_subsystem_handler("sftp", paramiko.SFTPServer, Files)
            try:
                transport.start_server(server=Server())
            except Exception as error:
                failures.append(type(error).__name__)
                transport.close()

    thread = threading.Thread(target=serve, name="datacraft-test-sftp")
    thread.start()
    try:
        yield {
            "conn_type": "sftp", "host": "127.0.0.1", "port": listener.getsockname()[1],
            "login": "datacraft-test", "password": secret,
            "extra": {"no_host_key_check": False, "host_key": key.get_base64()},
        }
    finally:
        stop.set()
        listener.close()
        thread.join(timeout=10)
        for transport in transports:
            transport.close()
        if thread.is_alive() or failures:
            raise RuntimeError(f"SFTP fixture did not stop cleanly: {failures}")


def verify_output(path):
    # row_count only compares the dataset with the converter's own count; pin the count and notes.
    from pyspark.sql import SparkSession

    spark = SparkSession.builder.master("local[1]").appName("runtime-smoke-verify").getOrCreate()
    try:
        notes = sorted(row.note for row in spark.read.parquet(str(path)).select("note").collect())
    finally:
        spark.stop()
    if notes != ["hello\nworld", "中文"]:
        raise AssertionError(f"{path} contains {notes!r}")
    print(f"Verified output rows: {path.name}", flush=True)


def run_dags(data):
    from airflow.dag_processing.dagbag import DagBag

    sys.path.insert(0, str(ROOT / "orchestration/airflow/dags"))
    bag = DagBag(dag_folder=str(ROOT / "orchestration/airflow/dags"))
    if bag.import_errors:
        raise AssertionError(bag.import_errors)

    def run(name, conf=None):
        result = bag.dags[name].test(run_conf=conf)
        if result.state != "success":
            raise AssertionError(f"{name} ended in {result.state}")
        print(f"Verified real DAG: {name}", flush=True)

    data.mkdir()
    source = data / "source with ' spaces.csv"
    source.write_text('id,note\n001,"hello\nworld"\n002,中文\n', encoding="utf-8")
    run("datacraft_engine_jobs")
    # Running the same overwrite pipeline twice proves retries do not duplicate rows.
    for _ in range(2):
        run("datacraft_spark_etl", {"input": str(source), "output": str(data / "converted.parquet")})
    verify_output(data / "converted.parquet")
    with sftp_source(source) as connection:
        os.environ["AIRFLOW_CONN_DATACRAFT_SFTP"] = json.dumps(connection)
        try:
            run("datacraft_sftp_ingest", {
                "remote_path": "/input.csv", "local_path": str(data / "nested/ingest.csv"),
                "output": str(data / "ingested.parquet"),
            })
            if (data / "nested/ingest.csv").read_bytes() != source.read_bytes():
                raise AssertionError("SFTP content differs from source")
            verify_output(data / "ingested.parquet")
        finally:
            os.environ.pop("AIRFLOW_CONN_DATACRAFT_SFTP", None)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", required=True, type=Path)
    parser.add_argument("--data", type=Path)
    args = parser.parse_args()
    if args.data is not None:
        run_dags(args.data)
        return
    jar = args.jar.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="datacraft-runtime-") as directory:
        work = Path(directory)
        env = {
            **os.environ,
            "PATH": str(Path(sys.executable).parent) + os.pathsep + os.environ["PATH"],
            "AIRFLOW_HOME": str(work / "airflow"),
            "AIRFLOW__CORE__DAGS_FOLDER": str(ROOT / "orchestration/airflow/dags"),
            "AIRFLOW__CORE__LOAD_EXAMPLES": "false",
            "AIRFLOW__CORE__AUTH_MANAGER": "airflow.providers.fab.auth_manager.fab_auth_manager.FabAuthManager",
            "AIRFLOW__API_AUTH__JWT_SECRET": secrets.token_hex(32),
            "AIRFLOW_ADMIN_USERNAME": "runtime-test",
            "AIRFLOW_ADMIN_PASSWORD": "-" + secrets.token_urlsafe(24),
            "AIRFLOW_ADMIN_EMAIL": "runtime@example.invalid",
            "DATACRAFT_CLI_JAR": str(jar),
            # DAG path parameters must stay under the data root; confine them to this run's data.
            "DATACRAFT_DATA_ROOT": str(work / "data"),
            "DATACRAFT_SPARK_MASTER": "local[1]",
            "DATACRAFT_TASK_RETRIES": "0",
            "SPARK_LOCAL_IP": "127.0.0.1",
            "PYTHONDONTWRITEBYTECODE": "1",
        }
        for _ in range(2):
            subprocess.run(["bash", str(ROOT / "deploy/scripts/airflow-bootstrap.sh")], env=env, check=True, timeout=180)
        subprocess.run([sys.executable, str(Path(__file__).resolve()), "--jar", str(jar), "--data", str(work / "data")],
                       env=env, check=True, timeout=1200)
    print("Runtime fixtures and Airflow state removed", flush=True)


if __name__ == "__main__":
    main()
