"""Real Airflow parsing, template rendering and Bash execution regressions."""

import base64
from contextlib import contextmanager
from datetime import timedelta
import importlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from airflow.dag_processing.dagbag import DagBag
from airflow.exceptions import AirflowException
from airflow.sdk import DAG
from airflow.sdk.exceptions import AirflowFailException, ParamValidationError

DAGS = Path(__file__).resolve().parents[2] / "orchestration" / "airflow" / "dags"
sys.path.insert(0, str(DAGS))
import datacraft_common as common


@contextmanager
def separate_common(**environment):
    """Imports of datacraft_common (including by DAG files) see ``environment``; ``common`` is kept."""
    shared = sys.modules.pop("datacraft_common")
    try:
        with patch.dict(os.environ, environment):
            yield
    finally:
        sys.modules["datacraft_common"] = shared


class DagTests(unittest.TestCase):
    def test_all_dags_parse_with_real_providers(self):
        bag = DagBag(dag_folder=str(DAGS))
        self.assertEqual({}, bag.import_errors)
        self.assertEqual(
            {"datacraft_engine_jobs", "datacraft_spark_etl", "datacraft_sftp_ingest"},
            set(bag.dags),
        )
        for dag in bag.dags.values():
            self.assertEqual(1, dag.max_active_runs)
            for task in dag.tasks:
                self.assertIsNotNone(task.execution_timeout)
                self.assertEqual(common.DEFAULT_ARGS["retries"], task.retries)
                # The float API, not a bool that only a deserialization shim turns into 2.0.
                self.assertIs(float, type(task.retry_exponential_backoff))
                self.assertEqual(2.0, task.retry_exponential_backoff)
                self.assertEqual(timedelta(minutes=10), task.max_retry_delay)
        ingest = bag.dags["datacraft_sftp_ingest"]
        self.assertEqual("SFTPOperator", type(ingest.get_task("download")).__name__)
        self.assertTrue(ingest.get_task("download").create_intermediate_dirs)
        self.assertEqual({"csv_to_parquet"}, ingest.get_task("row_count").upstream_task_ids)

    def test_helper_module_is_excluded_by_dag_folder_airflowignore(self):
        from airflow.utils.file import list_py_file_paths

        # Safe mode off: the ignore file, not the content heuristic, must exclude the helper.
        found = {Path(p).name for p in list_py_file_paths(str(DAGS), safe_mode=False)}
        self.assertEqual({"datacraft_engine_jobs.py", "datacraft_spark_etl.py", "datacraft_sftp_ingest.py"}, found)
        self.assertFalse((DAGS.parent / ".airflowignore").exists(), "ignore files above the DAG folder are never read")

    def test_data_path_params_are_confined(self):
        root = "/opt/datacraft/data"
        with separate_common(DATACRAFT_DATA_ROOT=root):
            bag = DagBag(dag_folder=str(DAGS))
        self.assertEqual({}, bag.import_errors)
        rejected = (
            "/opt/airflow/logs", "out.parquet", "/opt/datacraft/datacraft-cli.jar", "/opt/datacraft/database/x",
            f"{root}/../x", f"{root}/./x", f"{root}/a/../../x", f"{root}/a/..", f"{root}/a\n/../../x",
            f"{root}/x\n", f"{root}/a\rb", f"{root}/a\0b", f"{root}/", f"{root}//", root,
            # Spark globs these on read: Hadoop expands {../..} and unescapes \.\. to reach outside.
            f"{root}/{{../..}}/x", f"{root}/{{a,b}}/x", root + "/\\.\\./x", f"{root}/a\\b", f"{root}/*.csv",
            f"{root}/in?.csv", f"{root}/[ab]/x",
            # The JVM jobs trim values, so they would read another file than the download wrote.
            f"{root}/x ", f"{root}/x\t", f"{root}/a/.. ", f"{root}/x\x0b",
        )
        accepted = (
            f"{root}/sub/out.parquet", f"{root}/a..b/c", f"{root}/.cache/x", f"{root}/it's here.csv",
            f"{root}/a,b (1)%20#.csv",
        )
        for dag_id, names in (("datacraft_spark_etl", ("input", "output")), ("datacraft_sftp_ingest", ("local_path", "output"))):
            dag = bag.dags[dag_id]
            dag.params.validate()
            for name in names:
                param = dag.params.get_param(name)
                self.assertTrue(param.resolve().startswith(root + "/"))
                for value in rejected:
                    with self.subTest(dag=dag_id, param=name, value=value), self.assertRaises(ParamValidationError):
                        param.resolve(value)
                for value in accepted:
                    with self.subTest(dag=dag_id, param=name, value=value):
                        self.assertEqual(value, param.resolve(value))

    def test_data_root_is_absolute_and_matched_literally(self):
        for raw in ("", "/", "//", "data", "relative/data/", "/srv/data[1]", "/srv/{a,b}", "/srv/da\\ta",
                    "//srv//lake/", " /srv/lake", "/srv/lake "):
            with self.subTest(raw=raw), separate_common(DATACRAFT_DATA_ROOT=raw):
                with self.assertRaisesRegex(ValueError, "DATACRAFT_DATA_ROOT"):
                    importlib.import_module("datacraft_common")
        # Inner duplicate and trailing slashes are collapsed, so normally written paths still match.
        with separate_common(DATACRAFT_DATA_ROOT="/srv//lake/"):
            self.assertEqual("/srv/lake", importlib.import_module("datacraft_common").DATA_ROOT)
        with separate_common(DATACRAFT_HOME="/opt/datacraft/"):
            os.environ.pop("DATACRAFT_DATA_ROOT", None)
            module = importlib.import_module("datacraft_common")
        self.assertEqual("/opt/datacraft/data", module.DATA_ROOT)
        self.assertEqual("/opt/datacraft/data/x", module.data_path_param("in.csv", "Input").resolve("/opt/datacraft/data/x"))
        with separate_common(DATACRAFT_DATA_ROOT="/srv/lake-1.v2/"):
            module = importlib.import_module("datacraft_common")
        self.assertIsNot(common, module)
        self.assertEqual("/srv/lake-1.v2", module.DATA_ROOT)
        param = module.data_path_param("in.csv", "Input")
        self.assertEqual("/srv/lake-1.v2/in.csv", param.resolve())
        for value in ("/srv/lake-1Xv2/in.csv", "/srv/lake-1.v2x/in.csv"):
            with self.subTest(value=value), self.assertRaises(ParamValidationError):
                param.resolve(value)

    def test_sftp_download_refuses_unverified_host_keys(self):
        import paramiko

        download = DagBag(dag_folder=str(DAGS)).dags["datacraft_sftp_ingest"].get_task("download")
        # The Task SDK runs this hook itself; BaseOperator.pre_execute(context) would not call it.
        guard = download._pre_execute_hook
        self.assertIsNotNone(guard)
        key = "ssh-rsa " + paramiko.RSAKey.generate(2048).get_base64()
        base = {"conn_type": "sftp", "host": "127.0.0.1", "login": "u", "password": "p"}

        def connection(extra):
            return patch.dict(os.environ, {"AIRFLOW_CONN_DATACRAFT_SFTP": json.dumps({**base, "extra": extra})})

        for extra in (
            {}, {"no_host_key_check": True}, {"no_host_key_check": False, "allow_host_key_change": True},
            {"host_key": key, "allow_host_key_change": True},
        ):
            with self.subTest(extra=extra), connection(extra), self.assertRaises(AirflowFailException):
                guard({})
        # The provider rejects these while reading the connection; retrying cannot fix them either.
        for extra in (
            {"host_key": key, "no_host_key_check": True}, {"host_key": "ssh-dss AAAA"}, {"host_key": "ssh-rsa"},
            {"host_key": "ssh-rsa not-base64"}, {"host_key": "ssh-rsa " + base64.b64encode(b"garbage").decode()},
        ):
            with self.subTest(extra=extra), connection(extra):
                with self.assertRaisesRegex(AirflowFailException, "'datacraft_sftp' has invalid SSH settings"):
                    guard({})
        for extra in ({"host_key": key}, {"no_host_key_check": False}):
            with self.subTest(extra=extra), connection(extra):
                guard({})

    def test_rendered_values_remain_single_literal_arguments(self):
        with tempfile.TemporaryDirectory() as work:
            launcher = Path(work) / "launcher with spaces"
            launcher.write_text(
                '#!/usr/bin/env python3\nimport json, sys\nprint(json.dumps(sys.argv[1:]))\n'
            )
            launcher.chmod(0o755)
            value = "O'Reilly; $(printf INJECTED) `printf INJECTED` *\n中文 = yes"
            with patch.object(common, "JAVA_BIN", str(launcher)), patch.object(common, "CLI_JAR", "/tmp/a ' jar.jar"):
                task = common.cli_task("literal", "echo", params={"message": "{{ params.message }}"})
            task.render_template_fields({"params": {"message": value}})
            result = subprocess.run(
                ["bash", "-c", task.bash_command],
                env={**os.environ, **(task.env or {})}, cwd=work,
                capture_output=True, text=True, check=True,
            )
            self.assertEqual(
                ["-jar", "/tmp/a ' jar.jar", "--command", "echo", "--lifecycle", "dev", "--param", "message=" + value],
                json.loads(result.stdout),
            )

    def test_script_suffixed_values_stay_literal_arguments(self):
        with tempfile.TemporaryDirectory() as work:
            launcher = Path(work) / "launcher.sh"
            launcher.write_text('#!/usr/bin/env python3\nimport json, sys\nprint(json.dumps(sys.argv[1:]))\n')
            launcher.chmod(0o755)
            with DAG("suffix_literal", schedule=None), patch.object(common, "JAVA_BIN", str(launcher)):
                task = common.cli_task("literal", "echo", params={"script": "x.sh"}, extra_args=["setup.bash"])
            task.render_template_fields({"params": {}})
            result = subprocess.run(
                ["bash", "-c", task.bash_command], env={**os.environ, **task.env},
                cwd=work, capture_output=True, text=True, check=True,
            )
            self.assertEqual(
                ["-jar", common.CLI_JAR, "--command", "echo", "--lifecycle", "dev", "--param", "script=x.sh", "setup.bash"],
                json.loads(result.stdout),
            )

    def test_nonzero_exit_is_failed_including_99(self):
        with tempfile.TemporaryDirectory() as work:
            launcher = Path(work) / "fail"
            for code in (1, 99):
                launcher.write_text(f"#!/bin/sh\nexit {code}\n")
                launcher.chmod(0o755)
                with patch.object(common, "JAVA_BIN", str(launcher)):
                    task = common.cli_task(f"failing_{code}", "noop")
                self.assertFalse(task.skip_on_exit_code)
                try:
                    with self.assertRaises(AirflowException):
                        task.execute(context={})
                finally:
                    task.subprocess_hook.sub_process.stdout.close()

    def test_task_default_environment_values_are_validated_by_name(self):
        cases = (("DATACRAFT_TASK_RETRIES", "", 0), ("DATACRAFT_TASK_RETRIES", "abc", 0),
                 ("DATACRAFT_TASK_RETRIES", "-1", 0), ("DATACRAFT_TASK_TIMEOUT_MINUTES", "0", 1))
        for name, raw, minimum in cases:
            with self.subTest(name=name, raw=raw), patch.dict(os.environ, {name: raw}):
                with self.assertRaisesRegex(ValueError, name):
                    common._env_int(name, 1, minimum)
        with patch.dict(os.environ, {"DATACRAFT_TASK_RETRIES": " 3 "}):
            self.assertEqual(3, common._env_int("DATACRAFT_TASK_RETRIES", 1, 0))
        with patch.dict(os.environ):
            os.environ.pop("DATACRAFT_TASK_TIMEOUT_MINUTES", None)
            self.assertEqual(60, common._env_int("DATACRAFT_TASK_TIMEOUT_MINUTES", 60, 1))

    def test_spark_master_is_quoted_and_consistent(self):
        with patch.object(common, "SPARK_SUBMIT", "/bin/echo"):
            task = common.spark_task("spark", "row-count", master="local[2]", params={"input": "a b.parquet"})
        self.assertNotIn("{{", task.bash_command)
        result = subprocess.run(["bash", "-c", task.bash_command], env={**os.environ, **task.env}, capture_output=True, text=True, check=True)
        self.assertEqual(2, result.stdout.count("--master local[2]"))

    def test_spark_master_parameter_must_match_the_launcher(self):
        with self.assertRaisesRegex(ValueError, "spark.master"):
            common.spark_task("spark", "row-count", master="local[2]", params={"spark.master": "local[4]"})
        common.spark_task("spark", "row-count", master="local[2]", params={"spark.master": "local[2]"})

    def test_legacy_extra_args_string_is_split_not_evaluated(self):
        task = common.cli_task("legacy", "echo", extra_args="--x 'a b' $(id)")
        argv = [task.env[f"_DATACRAFT_ARG_{index}"] for index in range(len(task.env))]
        self.assertEqual(["--x", "a b", "$(id)"], argv[-3:])

    def test_unsafe_task_inputs_are_rejected(self):
        for key in ("bad key", "--config", "1x"):
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "parameter key"):
                common.cli_task("guarded", "echo", params={key: "x"})
        with self.assertRaisesRegex(ValueError, "reserved"):
            common.cli_task("guarded", "echo", env={"_DATACRAFT_ARG_0": "x"})
        with self.assertRaisesRegex(ValueError, "NUL"):
            common.cli_task("guarded", "echo", extra_args=["a\0b"])

    def test_structured_result_survives_shutdown_logs_and_cleans_temporary_file(self):
        with tempfile.TemporaryDirectory() as work:
            launcher = Path(work) / "spark"
            launcher.write_text(
                '#!/usr/bin/env python3\nimport sys\nfrom pathlib import Path\n'
                'Path(sys.argv[sys.argv.index("--result-file") + 1]).write_text(\'{"metrics":{"rows":"2"}}\\n\')\n'
                'print("Spark shutdown log", file=sys.stderr)\n'
            )
            launcher.chmod(0o755)
            with patch.object(common, "SPARK_SUBMIT", str(launcher)):
                task = common.spark_task("structured", "row-count", env={"TMPDIR": work})
            result = subprocess.run(["bash", "-c", task.bash_command], env={**os.environ, **task.env}, capture_output=True, text=True, check=True)
            self.assertEqual({"metrics": {"rows": "2"}}, task.output_processor(result.stdout.strip()))
            self.assertEqual([launcher], list(Path(work).iterdir()))

    def test_failed_structured_task_removes_result_file(self):
        with tempfile.TemporaryDirectory() as work:
            launcher = Path(work) / "spark"
            launcher.write_text(
                '#!/usr/bin/env python3\nimport sys\nfrom pathlib import Path\n'
                'Path(sys.argv[sys.argv.index("--result-file") + 1]).write_text("{}")\n'
                'sys.exit(3)\n'
            )
            launcher.chmod(0o755)
            with patch.object(common, "SPARK_SUBMIT", str(launcher)):
                task = common.spark_task("structured", "row-count", env={"TMPDIR": work})
            result = subprocess.run(["bash", "-c", task.bash_command], env={**os.environ, **task.env}, capture_output=True, text=True)
            self.assertEqual(3, result.returncode)
            self.assertEqual([launcher], list(Path(work).iterdir()))

    def test_validation_step_compares_conversion_metrics(self):
        bag = DagBag(dag_folder=str(DAGS))
        class TaskInstance:
            def xcom_pull(self, task_ids):
                return {"metrics": {"rows": "7"}}
        for name in ("datacraft_spark_etl", "datacraft_sftp_ingest"):
            task = bag.dags[name].get_task("row_count")
            task.render_template_fields({"params": dict(bag.dags[name].params), "ti": TaskInstance()})
            self.assertIn("expectedRows=7", task.env.values())


if __name__ == "__main__":
    unittest.main()
