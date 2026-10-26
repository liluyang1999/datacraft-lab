"""Image builds must refresh public bases without ever resolving the local-only builder remotely."""

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

DEPLOY = Path(__file__).resolve().parents[2] / "deploy"
DOCKERFILES = DEPLOY / "docker"
RUNTIME_DOCKERFILES = ("Dockerfile.jvm", "Dockerfile.airflow", "Dockerfile.spark")

# Records every docker invocation; `compose version` must succeed for require_docker. With
# FAILING_PULL set, the first PULL_FAILURES pulls of that image fail, as a registry that drops the
# connection does.
DOCKER_STUB = """#!/usr/bin/env python3
import json, os, sys
with open(os.environ['CALLS'], 'a') as out: out.write(json.dumps(sys.argv[1:]) + '\\n')
failing = ['pull', os.environ.get('FAILING_PULL', '')]
if sys.argv[1:] == failing:
    with open(os.environ['CALLS']) as calls:
        pulls = sum(1 for line in calls if json.loads(line) == failing)
    sys.exit(1 if pulls <= int(os.environ['PULL_FAILURES']) else 0)
"""
# The retry waits are real sleeps in the script; the tests do not wait for them.
SLEEP_STUB = "#!/bin/sh\nexit 0\n"


def arg_defaults(name):
    text = (DOCKERFILES / name).read_text(encoding="utf-8")
    return dict(re.findall(r"^ARG (\w+)=(\S+)$", text, re.MULTILINE))


def build_args(call):
    return dict(call[index + 1].split("=", 1) for index, arg in enumerate(call) if arg == "--build-arg")


def dockerfile(call):
    return Path(call[call.index("-f") + 1]).name


class ImageBuildContractTests(unittest.TestCase):
    def run_build(self, status=0, **env):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copytree(DEPLOY / "scripts", root / "deploy" / "scripts")
            stub = root / "bin" / "docker"
            stub.parent.mkdir()
            stub.write_text(DOCKER_STUB)
            stub.chmod(0o755)
            sleep = stub.parent / "sleep"
            sleep.write_text(SLEEP_STUB)
            sleep.chmod(0o755)
            calls = root / "calls.jsonl"
            environment = {key: value for key, value in os.environ.items()
                           if key not in ("MAVEN_IMAGE", "JRE_IMAGE", "AIRFLOW_IMAGE", "SPARK_IMAGE",
                                          "BUILD_SPARK_IMAGE")}
            environment.update(PATH=f"{stub.parent}{os.pathsep}{os.environ['PATH']}", CALLS=str(calls), **env)
            result = subprocess.run(["bash", str(root / "deploy/scripts/build-images.sh")], env=environment,
                                    stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=60)
            self.assertEqual(status, result.returncode, result.stderr)
            self.stderr = result.stderr
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

    def test_a_failed_pull_is_retried_before_the_build_goes_on(self):
        # Docker Hub drops a token request now and then ("failed to fetch anonymous token ... EOF"),
        # which once stopped a local build that a plain rerun then finished.
        calls = self.run_build(FAILING_PULL="apache/airflow:3.3.2", PULL_FAILURES="2")
        self.assertEqual(3, calls.count(["pull", "apache/airflow:3.3.2"]))
        built = [dockerfile(call) for call in calls if call[:1] == ["build"]]
        self.assertEqual(["Dockerfile.build", "Dockerfile.jvm", "Dockerfile.airflow"], built)
        self.assertIn("apache/airflow:3.3.2", self.stderr)

    def test_a_pull_that_keeps_failing_stops_the_build(self):
        calls = self.run_build(status=1, FAILING_PULL="apache/airflow:3.3.2", PULL_FAILURES="99")
        self.assertEqual(3, calls.count(["pull", "apache/airflow:3.3.2"]))
        built = [dockerfile(call) for call in calls if call[:1] == ["build"]]
        self.assertEqual(["Dockerfile.build", "Dockerfile.jvm"], built)
        self.assertIn("apache/airflow:3.3.2", self.stderr)

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
        # The API must not limp on after an OutOfMemoryError; the JVM option precedes -jar.
        entrypoint = json.loads(next(line for line in instructions
                                     if line.startswith("ENTRYPOINT "))[len("ENTRYPOINT "):])
        self.assertEqual(["java", "-XX:+ExitOnOutOfMemoryError", "-jar",
                          "/opt/datacraft/datacraft-cli.jar"], entrypoint)
        airflow = (DOCKERFILES / "Dockerfile.airflow").read_text(encoding="utf-8")
        self.assertRegex(airflow, r"(?m)^RUN mkdir -p /opt/datacraft/data .*chmod -R g\+rwX /opt/datacraft/data$")
        # The Spark image creates the same directory, and its jobs stay inside it in the same way.
        spark = (DOCKERFILES / "Dockerfile.spark").read_text(encoding="utf-8")
        self.assertRegex(spark, r"(?m)^RUN mkdir -p /opt/datacraft/data ")
        self.assertRegex(spark, r"(?m)^\s+DATACRAFT_DATA_ROOT=/opt/datacraft/data$")

    def test_every_runtime_image_takes_its_java_from_the_same_jre_image(self):
        # The Spark base image is rebuilt less often than Java is patched: it carried 25.0.3 while
        # the build required 25.0.4.1. Each image therefore copies the runtime of JRE_IMAGE, which
        # the script pulls before it builds.
        calls = self.run_build(BUILD_SPARK_IMAGE="true", JRE_IMAGE="jre:test")
        builds = {dockerfile(call): build_args(call) for call in calls if call[:1] == ["build"]}
        for name in RUNTIME_DOCKERFILES:
            with self.subTest(dockerfile=name):
                self.assertEqual("jre:test", builds[name]["JRE_IMAGE"])
                text = (DOCKERFILES / name).read_text(encoding="utf-8")
                self.assertEqual("eclipse-temurin:25-jre", arg_defaults(name)["JRE_IMAGE"])
                self.assertRegex(text, r"(?m)^FROM \$\{JRE_IMAGE\}( AS jre)?$")
        spark = (DOCKERFILES / "Dockerfile.spark").read_text(encoding="utf-8").splitlines()
        # Removed before the copy: a COPY onto the old runtime would leave its other files in place.
        removal, copy = spark.index("RUN rm -rf /opt/java/openjdk"), spark.index(
            "COPY --from=jre /opt/java/openjdk /opt/java/openjdk")
        self.assertLess(removal, copy)
        self.assertIn("ENV JAVA_HOME=/opt/java/openjdk", spark[copy:])

    def test_one_failed_package_download_does_not_fail_an_image_build(self):
        # A mirror answering one request with 502 failed a build on 2026-10-11. apt gives up at
        # once unless told to retry; pip retries by default.
        for name in RUNTIME_DOCKERFILES + ("Dockerfile.build",):
            text = (DOCKERFILES / name).read_text(encoding="utf-8")
            for call in re.findall(r"apt-get [^\n]*", re.sub(r"(?m)^#.*$", "", text)):
                with self.subTest(dockerfile=name, call=call):
                    self.assertRegex(call, r"^apt-get -o Acquire::Retries=[3-9] ")
        jvm = (DOCKERFILES / "Dockerfile.jvm").read_text(encoding="utf-8")
        self.assertEqual(2, len(re.findall(r"apt-get -o Acquire::Retries=", jvm)))

    def test_the_image_and_the_dag_tests_install_the_security_overrides_last(self):
        # The Airflow 3.3.2 constraints pin certifi and urllib3 releases that advisories published
        # later affect. The fixed releases go on top of the constrained install, which would
        # otherwise put the old ones back, and pip check then proves the result is consistent.
        dockerfile = (DOCKERFILES / "Dockerfile.airflow").read_text(encoding="utf-8")
        self.assertIn("COPY orchestration/airflow/security-overrides.txt /tmp/security-overrides.txt",
                      dockerfile)
        match = re.search(r"(?m)^RUN pip install (?:.*\\\n)*.*$", dockerfile)
        assert match is not None, "Dockerfile.airflow has no RUN pip install"
        install = match.group(0)
        script = (DEPLOY.parent / "cicd" / "airflow" / "install-airflow.sh").read_text(encoding="utf-8")
        for name, text, overrides in (
                ("Dockerfile.airflow", install, "-r /tmp/security-overrides.txt"),
                ("install-airflow.sh", script, "-r orchestration/airflow/security-overrides.txt")):
            with self.subTest(name):
                self.assertIn(overrides, text)
                position = text.index(overrides)
                self.assertLess(text.rindex("pyspark=="), position)
                self.assertLess(text.rindex("--constraint"), position)
                self.assertIn("pip check", text[position:])

    def test_a_code_change_does_not_download_the_python_packages_again(self):
        # Docker rebuilds every layer after the first one whose input changed. The jar and the DAGs
        # change with the code, the Python packages (pyspark alone is about 450 MB) do not.
        lines = (DOCKERFILES / "Dockerfile.airflow").read_text(encoding="utf-8").splitlines()
        install = next(i for i, line in enumerate(lines) if line.startswith("RUN pip install "))
        for copied in ("COPY --from=jar ", "COPY orchestration/airflow/dags/ ",
                       "COPY --chmod=755 deploy/scripts/airflow-bootstrap.sh "):
            position = next(i for i, line in enumerate(lines) if line.startswith(copied))
            self.assertLess(install, position, copied.strip())


if __name__ == "__main__":
    unittest.main()
