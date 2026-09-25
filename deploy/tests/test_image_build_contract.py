"""Image builds must refresh public bases without ever resolving the local-only builder remotely."""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

DEPLOY = Path(__file__).resolve().parents[1]
DOCKERFILES = DEPLOY / "docker"
RUNTIME_DOCKERFILES = ("Dockerfile.jvm", "Dockerfile.airflow", "Dockerfile.spark")

# Records every docker invocation; `compose version` must succeed for require_docker.
DOCKER_STUB = """#!/usr/bin/env python3
import json, os, sys
with open(os.environ['CALLS'], 'a') as out: out.write(json.dumps(sys.argv[1:]) + '\\n')
"""


def arg_defaults(name):
    text = (DOCKERFILES / name).read_text(encoding="utf-8")
    return dict(re.findall(r"^ARG (\w+)=(\S+)$", text, re.MULTILINE))


def build_args(call):
    return dict(call[index + 1].split("=", 1) for index, arg in enumerate(call) if arg == "--build-arg")


def dockerfile(call):
    return Path(call[call.index("-f") + 1]).name


class ImageBuildContractTests(unittest.TestCase):
    def run_build(self, **env):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copytree(DEPLOY / "scripts", root / "deploy" / "scripts")
            stub = root / "bin" / "docker"
            stub.parent.mkdir()
            stub.write_text(DOCKER_STUB)
            stub.chmod(0o755)
            calls = root / "calls.jsonl"
            environment = {key: value for key, value in os.environ.items()
                           if key not in ("MAVEN_IMAGE", "JRE_IMAGE", "AIRFLOW_IMAGE", "SPARK_IMAGE",
                                          "BUILD_SPARK_IMAGE")}
            environment.update(PATH=f"{stub.parent}{os.pathsep}{os.environ['PATH']}", CALLS=str(calls), **env)
            result = subprocess.run(["bash", str(root / "deploy/scripts/build-images.sh")], env=environment,
                                    stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=60)
            self.assertEqual(0, result.returncode, result.stderr)
            return [json.loads(line) for line in calls.read_text().splitlines()]

    def test_runtime_dockerfiles_have_no_registry_resolvable_builder_default(self):
        for name in RUNTIME_DOCKERFILES:
            text = (DOCKERFILES / name).read_text(encoding="utf-8")
            self.assertIsNone(re.search(r"^ARG JAR_IMAGE=", text, re.MULTILINE), name)
            self.assertRegex(text, r"(?m)^ARG JAR_IMAGE$", name)
            self.assertRegex(text, r"(?m)^FROM \$\{JAR_IMAGE\} AS jar$", name)

    def test_only_the_builder_pulls_during_build_and_every_public_base_is_refreshed_first(self):
        calls = self.run_build(BUILD_SPARK_IMAGE="true")
        builds = [call for call in calls if call[:1] == ["build"]]
        self.assertEqual(["Dockerfile.build", *RUNTIME_DOCKERFILES], [dockerfile(call) for call in builds])
        self.assertIn("--pull", builds[0])
        for call in builds[1:]:
            self.assertNotIn("--pull", call, dockerfile(call))
            self.assertEqual("datacraft/jar-builder:latest", build_args(call)["JAR_IMAGE"])
            # Every public base of this build was pulled before it.
            pulled = {c[1] for c in calls[:calls.index(call)] if c[:1] == ["pull"]}
            for key, value in build_args(call).items():
                if key != "JAR_IMAGE":
                    self.assertIn(value, pulled, f"{dockerfile(call)} {key}")
        self.assertNotIn("MAVEN_IMAGE", build_args(builds[0]))

    def test_script_defaults_equal_the_dockerfile_defaults(self):
        calls = self.run_build(BUILD_SPARK_IMAGE="true")
        for call in calls:
            if call[:1] == ["build"] and dockerfile(call) != "Dockerfile.build":
                defaults = arg_defaults(dockerfile(call))
                for key, value in build_args(call).items():
                    if key != "JAR_IMAGE":
                        self.assertEqual(defaults[key], value, f"{dockerfile(call)} {key}")

    def test_overridden_bases_are_used(self):
        calls = self.run_build(MAVEN_IMAGE="maven:test", JRE_IMAGE="jre:test", AIRFLOW_IMAGE="airflow:test")
        builds = {dockerfile(call): build_args(call) for call in calls if call[:1] == ["build"]}
        self.assertEqual("maven:test", builds["Dockerfile.build"]["MAVEN_IMAGE"])
        self.assertEqual("jre:test", builds["Dockerfile.jvm"]["JRE_IMAGE"])
        self.assertEqual({"jre:test", "airflow:test"},
                         {builds["Dockerfile.airflow"]["JRE_IMAGE"], builds["Dockerfile.airflow"]["AIRFLOW_IMAGE"]})
        self.assertNotIn("Dockerfile.spark", builds)
        self.assertIn(["pull", "airflow:test"], calls)

    def test_airflow_base_carries_the_fab_security_fixes(self):
        tag = arg_defaults("Dockerfile.airflow")["AIRFLOW_IMAGE"].rsplit(":", 1)[1]
        self.assertGreaterEqual(tuple(int(part) for part in tag.split(".")), (3, 3, 2))

    def test_runtime_images_keep_their_user_and_data_directory_contracts(self):
        jvm = (DOCKERFILES / "Dockerfile.jvm").read_text(encoding="utf-8")
        instructions = [line for line in jvm.splitlines() if line and not line.startswith("#")]
        users = [line.split()[1] for line in instructions if line.startswith("USER ")]
        self.assertTrue(users and re.fullmatch(r"[1-9]\d*:[1-9]\d*", users[-1]), "numeric non-root USER")
        self.assertLess(instructions.index(f"USER {users[-1]}"),
                        next(i for i, line in enumerate(instructions) if line.startswith("ENTRYPOINT")))
        # Compose may mount the shared volume on the API first; the Airflow image must seed it. An ENV
        # naming the directory creates nothing, and the file jobs need it to stay confined.
        self.assertFalse([line for line in instructions if not line.startswith("ENV ")
                          and re.search(r"/opt/datacraft/data(?![\w.-])", line)])
        self.assertIn("ENV DATACRAFT_DATA_ROOT=/opt/datacraft/data", instructions)
        airflow = (DOCKERFILES / "Dockerfile.airflow").read_text(encoding="utf-8")
        self.assertRegex(airflow, r"(?m)^RUN mkdir -p /opt/datacraft/data .*chmod -R g\+rwX /opt/datacraft/data$")


if __name__ == "__main__":
    unittest.main()
