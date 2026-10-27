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


def target(call):
    return call[call.index("--target") + 1] if "--target" in call else None


def temurin_args():
    return arg_defaults("Dockerfile.temurin")


# The images this project builds itself: never pulled, never looked up on docker.io.
LOCAL = ("datacraft/",)
BUILD_ORDER = [("Dockerfile.temurin", "jdk"), ("Dockerfile.temurin", "jre"), ("Dockerfile.build", None),
               *((name, None) for name in RUNTIME_DOCKERFILES)]


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

    def test_no_build_pulls_and_every_public_base_is_pulled_first(self):
        calls = self.run_build(BUILD_SPARK_IMAGE="true")
        builds = [call for call in calls if call[:1] == ["build"]]
        self.assertEqual(BUILD_ORDER, [(dockerfile(call), target(call)) for call in builds])
        for call in builds:
            # --pull would look the local images up on docker.io, a namespace this project does not
            # own; the public bases are pulled explicitly instead, each with retries.
            self.assertNotIn("--pull", call, dockerfile(call))
            pulled = {c[1] for c in calls[:calls.index(call)] if c[:1] == ["pull"]}
            for key, value in build_args(call).items():
                if not value.startswith(LOCAL):
                    self.assertIn(value, pulled, f"{dockerfile(call)} {key}")
        self.assertFalse([call for call in calls if call[:1] == ["pull"] and call[1].startswith(LOCAL)])
        for call in builds[3:]:
            self.assertEqual("datacraft/jar-builder:latest", build_args(call)["JAR_IMAGE"])

    def test_script_defaults_equal_the_dockerfile_defaults(self):
        calls = self.run_build(BUILD_SPARK_IMAGE="true")
        for call in calls:
            if call[:1] == ["build"] and dockerfile(call) != "Dockerfile.temurin":
                defaults = arg_defaults(dockerfile(call))
                for key, value in build_args(call).items():
                    if not value.startswith(LOCAL):
                        self.assertEqual(defaults[key], value, f"{dockerfile(call)} {key}")

    def test_overridden_bases_are_used(self):
        calls = self.run_build(MAVEN_IMAGE="maven:test", JRE_IMAGE="jre:test", AIRFLOW_IMAGE="airflow:test")
        builds = {(dockerfile(call), target(call)): build_args(call) for call in calls if call[:1] == ["build"]}
        # The overrides name the official bases; the pinned Temurin release goes on top of them.
        self.assertEqual("maven:test", builds[("Dockerfile.temurin", "jdk")]["BASE_IMAGE"])
        self.assertEqual("jre:test", builds[("Dockerfile.temurin", "jre")]["BASE_IMAGE"])
        self.assertEqual("airflow:test", builds[("Dockerfile.airflow", None)]["AIRFLOW_IMAGE"])
        self.assertNotIn(("Dockerfile.spark", None), builds)
        for image in ("maven:test", "jre:test", "airflow:test"):
            self.assertIn(["pull", image], calls)

    def test_the_temurin_pin_names_one_release_with_a_checksum_for_each_archive(self):
        text = (DOCKERFILES / "Dockerfile.temurin").read_text(encoding="utf-8")
        args = temurin_args()
        version = args["TEMURIN_VERSION"]
        self.assertRegex(version, r"^25\.\d+\.\d+(\.\d+)?\+\d+$")
        self.assertEqual(version.replace("+", "_"), args["TEMURIN_FILE"])
        checksums = {key: value for key, value in args.items() if key.endswith("_SHA256")}
        self.assertEqual({f"TEMURIN_{kind}_{arch}_SHA256" for kind in ("JRE", "JDK") for arch in ("AMD64", "ARM64")},
                         set(checksums))
        for key, value in checksums.items():
            self.assertRegex(value, r"^[0-9a-f]{64}$", key)
        self.assertEqual(4, len(set(checksums.values())))
        self.assertNotIn("BASE_IMAGE", args)
        for kind, arch, platform in (("jre", "amd64", "x64"), ("jre", "arm64", "aarch64"),
                                     ("jdk", "amd64", "x64"), ("jdk", "arm64", "aarch64")):
            with self.subTest(archive=f"{kind}-{arch}"):
                stage = re.search(rf"(?ms)^FROM scratch AS {kind}-{arch}$(.*?)(?=^FROM )", text)
                assert stage is not None, f"no stage {kind}-{arch}"
                self.assertIn(f"ADD --checksum=sha256:${{TEMURIN_{kind.upper()}_{arch.upper()}_SHA256}} "
                              "https://github.com/adoptium/temurin25-binaries/releases/download/"
                              f"jdk-${{TEMURIN_VERSION}}/OpenJDK25U-{kind}_{platform}_linux_hotspot_"
                              "${TEMURIN_FILE}.tar.gz /temurin.tar.gz", stage.group(1))
        for kind in ("jre", "jdk"):
            with self.subTest(target=kind):
                stage = re.search(rf"(?ms)^FROM \$\{{BASE_IMAGE\}} AS {kind}$(.*?)(?=^FROM |\Z)", text)
                assert stage is not None, f"no target {kind}"
                body = stage.group(1)
                self.assertIn(f"COPY --from={kind}-archive /temurin.tar.gz /tmp/temurin.tar.gz", body)
                # The base's own Java goes first, so that no file of the old release stays among the new.
                self.assertLess(body.index("rm -rf /opt/java/openjdk"), body.index("tar -xzf /tmp/temurin.tar.gz"))
                self.assertIn("ENV JAVA_VERSION=jdk-${TEMURIN_VERSION}", body)

    def test_the_java_floors_are_one_release_the_pin_meets(self):
        pom = (DEPLOY.parent / "pom.xml").read_text(encoding="utf-8")
        smoke = (DEPLOY.parent / "cicd" / "images" / "smoke-images.sh").read_text(encoding="utf-8")
        enforcer = re.search(r"<requireJavaVersion>\s*<version>\[([0-9.]+),\)</version>", pom)
        image = re.search(r"(?m)^java_floor=([0-9.]+)$", smoke)
        assert enforcer is not None and image is not None, "a Java floor is missing"
        self.assertEqual(enforcer.group(1), image.group(1))

        def release(value):
            return tuple(int(part) for part in re.findall(r"\d+", value.split("+")[0]))

        self.assertGreaterEqual(release(temurin_args()["TEMURIN_VERSION"]), release(enforcer.group(1)))

    def test_a_failed_pull_is_retried_before_the_build_goes_on(self):
        # Docker Hub drops a token request now and then ("failed to fetch anonymous token ... EOF"),
        # which once stopped a local build that a plain rerun then finished.
        calls = self.run_build(FAILING_PULL="apache/airflow:3.3.2", PULL_FAILURES="2")
        self.assertEqual(3, calls.count(["pull", "apache/airflow:3.3.2"]))
        built = [(dockerfile(call), target(call)) for call in calls if call[:1] == ["build"]]
        self.assertEqual(BUILD_ORDER[:5], built)
        self.assertIn("apache/airflow:3.3.2", self.stderr)

    def test_a_pull_that_keeps_failing_stops_the_build(self):
        calls = self.run_build(status=1, FAILING_PULL="apache/airflow:3.3.2", PULL_FAILURES="99")
        self.assertEqual(3, calls.count(["pull", "apache/airflow:3.3.2"]))
        built = [(dockerfile(call), target(call)) for call in calls if call[:1] == ["build"]]
        self.assertEqual(BUILD_ORDER[:4], built)
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

    def test_every_image_takes_its_java_from_the_pinned_temurin_release(self):
        # The official images lag security releases (25.0.5 on 2026-10-27, five days after its
        # release), and the Spark base image lags further (25.0.3 on 2026-10-11). Every image
        # therefore runs the pinned release that Dockerfile.temurin puts on top of the official base.
        calls = self.run_build(BUILD_SPARK_IMAGE="true", JRE_IMAGE="jre:test")
        builds = {dockerfile(call): build_args(call) for call in calls if call[:1] == ["build"]}
        self.assertEqual("datacraft/maven:latest", builds["Dockerfile.build"]["MAVEN_IMAGE"])
        self.assertRegex((DOCKERFILES / "Dockerfile.build").read_text(encoding="utf-8"), r"(?m)^ARG MAVEN_IMAGE$")
        for name in RUNTIME_DOCKERFILES:
            with self.subTest(dockerfile=name):
                self.assertEqual("datacraft/jre:latest", builds[name]["JRE_IMAGE"])
                text = (DOCKERFILES / name).read_text(encoding="utf-8")
                self.assertNotIn("JRE_IMAGE", arg_defaults(name))
                self.assertRegex(text, r"(?m)^ARG JRE_IMAGE$")
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
