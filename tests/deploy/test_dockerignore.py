"""The Docker build context must exclude local runtime state and secrets, but keep build inputs."""

import posixpath
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


def dockerignore_patterns(text):
    """Parse .dockerignore like Docker (moby/patternmatcher): Clean, strip a leading '/', keep '!'."""
    patterns = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        exclude = not line.startswith("!")
        pattern = posixpath.normpath(line.lstrip("!").strip())
        if len(pattern) > 1 and pattern.startswith("/"):
            pattern = pattern[1:]
        patterns.append((exclude, pattern))
    return patterns


def pattern_regex(pattern):
    out, index = "^", 0
    while index < len(pattern):
        if pattern.startswith("**", index):
            index += 3 if pattern.startswith("**/", index) else 2
            out += ".*" if index >= len(pattern) else "(.*/)?"
        elif pattern[index] == "*":
            out, index = out + "[^/]*", index + 1
        elif pattern[index] == "?":
            out, index = out + "[^/]", index + 1
        else:
            out, index = out + re.escape(pattern[index]), index + 1
    return re.compile(out + "$")


def is_excluded(patterns, path):
    """The last matching pattern wins; a pattern also matches a path through any parent directory."""
    excluded, parts = False, path.split("/")
    parents = ["/".join(parts[:depth]) for depth in range(1, len(parts))]
    for exclude, pattern in patterns:
        regex = pattern_regex(pattern)
        if regex.match(path) or any(regex.match(parent) for parent in parents):
            excluded = exclude
    return excluded


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
                     "deploy/compose/.env", "warehouse/t/part-0.parquet", "spark-warehouse/x",
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


if __name__ == "__main__":
    unittest.main()
