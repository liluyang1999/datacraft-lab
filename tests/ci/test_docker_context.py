"""The Docker-free build-context check must see the files, and run the command, an image build does."""

import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest

REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "cicd" / "build" / "docker_context.py"

_spec = importlib.util.spec_from_file_location("docker_context", SCRIPT)
assert _spec is not None and _spec.loader is not None, SCRIPT
docker_context = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(docker_context)

DOCKERFILE = """# syntax=docker/dockerfile:1
FROM maven:3 AS build
RUN mvn -B -ntp -pl :app -am dependency:go-offline || true
COPY . .
RUN mvn -B -ntp -pl :app -am package -Dmaven.test.skip=true \\
    && mkdir -p /dist \\
    && cp app/target/app.jar /dist/app.jar
"""


def write_tree(root, files):
    for name, text in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")


def run_main(*arguments):
    output = io.StringIO()
    with contextlib.redirect_stdout(output):
        status = docker_context.main(list(arguments))
    return status, output.getvalue()


class ContextFilesTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name) / "repository"
        self.root.mkdir()

    def test_excluded_directories_and_files_are_left_out_at_any_depth(self):
        write_tree(self.root, {".dockerignore": "tests/\n**/target/\n*.log\n**/.env\n",
                               "pom.xml": "", "build.log": "", "tests/jvm/A.java": "",
                               "app/pom.xml": "", "app/src/Main.java": "", "app/.env": "",
                               "app/target/classes/Main.class": "", "app/notes.log": ""})
        # `*.log` has no `**/`, so like Docker it only matches at the context root.
        self.assertEqual([".dockerignore", "app/notes.log", "app/pom.xml", "app/src/Main.java", "pom.xml"],
                         docker_context.context_files(self.root))

    def test_a_negated_pattern_brings_a_file_back_from_an_excluded_directory(self):
        write_tree(self.root, {".dockerignore": "docs/\n!docs/LICENSE\n",
                               "docs/guide.md": "", "docs/LICENSE": "", "pom.xml": ""})
        self.assertEqual([".dockerignore", "docs/LICENSE", "pom.xml"],
                         docker_context.context_files(self.root))

    def test_a_wildcard_re_inclusion_reaches_into_every_excluded_directory(self):
        # Docker lets the last matching pattern win at any depth, so nothing may be skipped whole.
        write_tree(self.root, {".dockerignore": "docs/\ntarget/\n!**/LICENSE\n",
                               "docs/guide.md": "", "docs/legal/LICENSE": "",
                               "target/classes/LICENSE": "", "target/app.jar": ""})
        self.assertEqual([".dockerignore", "docs/legal/LICENSE", "target/classes/LICENSE"],
                         docker_context.context_files(self.root))

    def test_a_literal_re_inclusion_only_keeps_its_own_directories_walkable(self):
        patterns = docker_context.dockerignore_patterns(
            "**/.env.*\n!deploy/compose/.env.example\n")
        for directory in ("deploy", "deploy/compose"):
            self.assertTrue(docker_context.may_reinclude_below(patterns, directory), directory)
        for directory in (".git", "tests", "deploy/scripts", "deployment"):
            self.assertFalse(docker_context.may_reinclude_below(patterns, directory), directory)

    def test_without_a_dockerignore_file_the_whole_tree_is_the_context(self):
        write_tree(self.root, {"pom.xml": "", "tests/A.java": ""})
        self.assertEqual(["pom.xml", "tests/A.java"], docker_context.context_files(self.root))

    def test_copy_writes_exactly_the_context_and_reports_its_size(self):
        write_tree(self.root, {".dockerignore": "tests/\n", "pom.xml": "<project/>",
                               "app/src/Main.java": "class Main {}", "tests/A.java": "class A {}"})
        destination = self.root.parent / "context"
        self.assertEqual(3, docker_context.copy_context(self.root, destination))
        copied = sorted(path.relative_to(destination).as_posix()
                        for path in destination.rglob("*") if path.is_file())
        self.assertEqual([".dockerignore", "app/src/Main.java", "pom.xml"], copied)
        self.assertEqual("class Main {}", (destination / "app/src/Main.java").read_text(encoding="utf-8"))

    def test_copy_refuses_a_destination_inside_the_repository_or_with_content(self):
        write_tree(self.root, {"pom.xml": ""})
        # A copy inside the repository would be part of the context it is copying.
        with self.assertRaisesRegex(ValueError, "is inside"):
            docker_context.copy_context(self.root, self.root / "context")
        occupied = self.root.parent / "occupied"
        write_tree(occupied, {"stale/target/app.jar": ""})
        with self.assertRaisesRegex(ValueError, "is not empty"):
            docker_context.copy_context(self.root, occupied)
        self.assertEqual(["stale"], [path.name for path in occupied.iterdir()])

    def test_command_line_reports_a_refused_copy_as_an_error(self):
        write_tree(self.root, {"pom.xml": ""})
        status, output = run_main("copy", str(self.root / "context"), "--root", str(self.root))
        self.assertEqual(1, status)
        self.assertIn("::error::", output)
        self.assertFalse((self.root / "context").exists())


class MavenArgsTests(unittest.TestCase):
    def test_packaging_command_is_read_across_continuations_without_its_shell_tail(self):
        expected = ["-B", "-ntp", "-pl", ":app", "-am", "package", "-Dmaven.test.skip=true"]
        self.assertEqual(expected, docker_context.maven_args(DOCKERFILE))
        self.assertEqual(expected, docker_context.maven_args(DOCKERFILE.replace("\n", "\r\n")))

    def test_a_dockerfile_without_exactly_one_packaging_command_is_rejected(self):
        for text, found in (("FROM maven:3\nRUN mvn -B dependency:go-offline\n", 0),
                            (DOCKERFILE + "RUN mvn -B package\n", 2)):
            with self.subTest(found=found):
                with self.assertRaisesRegex(ValueError, f"found {found}"):
                    docker_context.maven_args(text)

    def test_command_line_prints_one_argument_per_line_and_reports_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            dockerfile = Path(directory) / "Dockerfile"
            dockerfile.write_text(DOCKERFILE, encoding="utf-8")
            status, output = run_main("maven-args", str(dockerfile))
            self.assertEqual(0, status)
            self.assertEqual(["-B", "-ntp", "-pl", ":app", "-am", "package", "-Dmaven.test.skip=true"],
                             output.splitlines())
            status, output = run_main("maven-args", str(Path(directory) / "missing"))
            self.assertEqual(1, status)
            self.assertIn("::error::", output)

    def test_builder_image_packages_the_cli_module_without_tests(self):
        # tests/ is not in the build context, so the image build cannot compile or run them.
        text = (REPOSITORY / "deploy" / "docker" / "Dockerfile.build").read_text(encoding="utf-8")
        arguments = docker_context.maven_args(text)
        self.assertIn("-Dmaven.test.skip=true", arguments)
        self.assertEqual(":datacraft-cli", arguments[arguments.index("-pl") + 1])
        self.assertIn("-am", arguments)


if __name__ == "__main__":
    unittest.main()
