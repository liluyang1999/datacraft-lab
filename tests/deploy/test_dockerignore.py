"""The Docker build context must exclude local runtime state and secrets, but keep build inputs."""

import importlib.util
from pathlib import Path
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[2]

# The matcher is the one the CI checks use to compute the build context without Docker.
_spec = importlib.util.spec_from_file_location(
    "docker_context", ROOT / "cicd" / "build" / "docker_context.py")
assert _spec is not None and _spec.loader is not None
docker_context = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(docker_context)
dockerignore_patterns = docker_context.dockerignore_patterns
is_excluded = docker_context.is_excluded


def copied_from_the_context(dockerfile_text):
    """Sources of every COPY that reads the build context, not another stage or image."""
    sources = []
    for line in re.sub(r"\\\r?\n", " ", dockerfile_text).splitlines():
        words = line.split()
        if words[:1] == ["COPY"] and not any(word.startswith("--from=") for word in words):
            paths = [word for word in words[1:] if not word.startswith("--")]
            sources.extend(paths[:-1])
    return sources


def gitignore_entries(sections):
    """Non-negated .gitignore entries under the given '### <name>' headers, without slashes."""
    entries, active = [], False
    for line in (ROOT / ".gitignore").read_text(encoding="utf-8").splitlines():
        if line.startswith("###"):
            active = any(line.startswith(f"### {section}") for section in sections)
        elif active and line.strip() and not line.startswith(("#", "!")):
            entries.append(line.strip().strip("/"))
    return entries


class DockerignoreTests(unittest.TestCase):
    def setUp(self):
        self.patterns = dockerignore_patterns((ROOT / ".dockerignore").read_text(encoding="utf-8"))

    def test_matcher_follows_docker_semantics(self):
        patterns = dockerignore_patterns("/logs/\n**/target/\n**/.env\n*.log\nkeep/*\n!keep/me\n")
        for path in ("logs/a", "a/b/target/x.class", "target/x", ".env", "a/.env", "x.log", "keep/other"):
            self.assertTrue(is_excluded(patterns, path), path)
        for path in ("a/logs/b", "a/x.log", ".env.example", "keep/me", "targets/x"):
            self.assertFalse(is_excluded(patterns, path), path)

    def test_gitignored_runtime_state_and_secrets_stay_out_of_the_build_context(self):
        entries = gitignore_entries(("datacraft runtime", "secrets / local env"))
        self.assertIn("backups", entries)  # dumps created by the documented metadata backup procedure
        present = {pattern for exclude, pattern in self.patterns if exclude}
        for entry in entries:
            self.assertTrue(entry in present or f"**/{entry}" in present,
                            f"{entry} is git-ignored but sent to the Docker daemon")

    def test_local_state_is_excluded(self):
        cli = "modules/interfaces/datacraft-cli"
        for path in ("backups/airflow-20260925T000000Z.dump", ".env", f"{cli}/.env",
                     "deploy/compose/.env", "deploy/compose/.env.bak", ".env.2026-10-07",
                     f"{cli}/.env.local", f"{cli}/build.log", f"{cli}/datacraft-cli.iml",
                     "warehouse/t/part-0.parquet", "spark-warehouse/x",
                     ".airflow/airflow.db", f"{cli}/target/datacraft-cli.jar",
                     ".claude/worktrees/wf/pom.xml", "tests/jvm/datacraft-io/java/X.java",
                     "docs/README.md", "design/architecture.md", "cicd/build/check-jar-contents.sh"):
            self.assertTrue(is_excluded(self.patterns, path), path)

    def test_build_inputs_stay_in_the_build_context(self):
        cli = "modules/interfaces/datacraft-cli"
        for path in ("pom.xml", f"{cli}/pom.xml", f"{cli}/src/main/scala/X.scala",
                     "deploy/compose/.env.example", "orchestration/airflow/requirements.txt",
                     "orchestration/airflow/dags/datacraft_common.py", "deploy/scripts/airflow-bootstrap.sh"):
            self.assertFalse(is_excluded(self.patterns, path), path)

    def test_git_ignores_env_file_copies_but_tracks_the_template(self):
        paths = ["deploy/compose/.env", "deploy/compose/.env.bak", ".env.2026-10-07",
                 "deploy/compose/.env.example"]
        try:
            # NUL-separated bytes: text mode on Windows would append a carriage return.
            result = subprocess.run(["git", "check-ignore", "--no-index", "-z", "--stdin"],
                                    input="\0".join(paths).encode(), cwd=ROOT,
                                    capture_output=True, timeout=60)
        except OSError:
            self.skipTest("git is not available")
        self.assertIn(result.returncode, (0, 1), result.stderr.decode(errors="replace"))
        ignored = [name for name in result.stdout.decode().split("\0") if name]
        self.assertEqual(paths[:3], ignored)

    def test_every_path_a_dockerfile_copies_is_in_the_build_context(self):
        # A moved module or a newly ignored directory would otherwise fail only inside `docker build`.
        context = docker_context.context_files(ROOT)
        dockerfiles = sorted((ROOT / "deploy" / "docker").glob("Dockerfile.*"))
        copied = {dockerfile.name: copied_from_the_context(dockerfile.read_text(encoding="utf-8"))
                  for dockerfile in dockerfiles}
        self.assertIn("pom.xml", copied["Dockerfile.build"])
        self.assertIn("orchestration/airflow/dags/", copied["Dockerfile.airflow"])
        for name, sources in copied.items():
            for source in sources:
                with self.subTest(dockerfile=name, source=source):
                    path = source.rstrip("/")
                    self.assertTrue(path == "." or path in context
                                    or any(file.startswith(f"{path}/") for file in context),
                                    f"{name} copies {source}, which is not in the build context")


if __name__ == "__main__":
    unittest.main()
