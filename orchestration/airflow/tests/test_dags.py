"""Real Airflow parsing, template rendering and Bash execution regressions."""

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

DAGS = Path(__file__).resolve().parents[1] / "dags"
sys.path.insert(0, str(DAGS))
import datacraft_common as common


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
        ingest = bag.dags["datacraft_sftp_ingest"]
        self.assertEqual("SFTPOperator", type(ingest.get_task("download")).__name__)
        self.assertTrue(ingest.get_task("download").create_intermediate_dirs)
        self.assertEqual({"csv_to_parquet"}, ingest.get_task("row_count").upstream_task_ids)

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

    def test_spark_master_is_quoted_and_consistent(self):
        with patch.object(common, "SPARK_SUBMIT", "/bin/echo"):
            task = common.spark_task("spark", "row-count", master="local[2]", params={"input": "a b.parquet"})
        self.assertNotIn("{{", task.bash_command)
        result = subprocess.run(["bash", "-c", task.bash_command], env={**os.environ, **task.env}, capture_output=True, text=True, check=True)
        self.assertEqual(2, result.stdout.count("--master local[2]"))

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
