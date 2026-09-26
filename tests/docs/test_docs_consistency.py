"""Documentation lives in docs/ and design/, its links resolve, and it agrees with the build.

Layout: every Markdown and HTML page lives under docs/ or design/, is reachable from
docs/README.md and has relative links that resolve; tests live only under tests/; and no file
names a path, module location or build property that the 2026-09-26 restructure moved or renamed
(docs/CHANGELOG.md keeps those as history). Claims that drifted before: stale cloud wording, a
Java release other than the build's, a workstation JDK path, a serve-api command the CLI refuses,
Airflow pins that disagree, a CI constraints file for another Python version, and loopback-only
ports advertised on <host>.

Standard library only; runs on Windows and Linux. The files checked are those git lists as
tracked or untracked-but-not-ignored; without git, a directory walk approximates that.
"""

import functools
import html
from html.parser import HTMLParser
import os
from pathlib import Path
import posixpath
import re
import subprocess
import unittest
from urllib.parse import unquote
import xml.etree.ElementTree as ElementTree

ROOT = Path(__file__).resolve().parents[2]
# This module quotes the stale names it looks for, so the stale-name scan skips it.
THIS_FILE = Path(__file__).resolve().relative_to(ROOT).as_posix()

DOC_DIRECTORIES = ("docs/", "design/")
INDEX = "docs/README.md"
# History: it may name moved paths and renamed properties.
CHANGELOG = "docs/CHANGELOG.md"
CI_WORKFLOW = ".github/workflows/ci.yml"

PAGE_SUFFIXES = (".md", ".html", ".htm")
TEXT_SUFFIXES = PAGE_SUFFIXES + (".css", ".js", ".cjs", ".mjs", ".json", ".txt", ".svg")
# Only used when git cannot list the files; approximates .gitignore for a plain directory walk.
SKIPPED_DIRECTORIES = {".git", ".idea", ".mypy_cache", ".pytest_cache", ".venv", ".vscode",
                       "__pycache__", "build", "node_modules", "target"}
SKIPPED_ROOT_DIRECTORIES = {".airflow", ".claude", ".worktrees", "backups", "data", "dist",
                            "logs", "spark-warehouse", "tmp", "warehouse"}

FENCED_CODE = re.compile(r"^ {0,3}(`{3,}|~{3,}).*?^ {0,3}\1[ \t]*$", re.M | re.S)
INLINE_CODE = re.compile(r"(`+)(?!`).+?(?<!`)\1(?!`)")
HTML_COMMENT = re.compile(r"<!--.*?-->", re.S)
HTML_TAG = re.compile(r"<[^<>]*>")
INLINE_LINK = re.compile(r"\]\(\s*(<[^<>\n]*>|[^\s()]+(?:\([^\s()]*\)[^\s()]*)*)")
REFERENCE_LINK = re.compile(r"^ {0,3}\[(?!\^)[^\]\n]+\]:[ \t]*(<[^<>\n]*>|\S+)", re.M)
SCHEME = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*:")
COMPOSE_VARIABLE = re.compile(r"\$\{[A-Za-z_][A-Za-z0-9_]*(?::?-([^}]*))?\}")

# Test sources by this repository's conventions: Maven src/test trees, unittest and node:test.
MAVEN_TEST_TREE = re.compile(r"(?:^|/)src/test/")
TEST_SCRIPT = re.compile(r"(?:^|/)(?:test_[^/]*\.py|[^/]*_test\.py|[^/]*\.test\.[cm]?js)$")

# Paths and names the 2026-09-26 restructure moved or renamed; either path separator matches.
STALE_NAMES = (
    "deploy/tests", ".github/scripts", "orchestration/airflow/tests",
    "orchestration/airflow/README.md", "docs/deployment/", "docs/architecture.md",
    "cloud-and-deployment.md", "data-processing.md", "PROJECT-REVIEW.html",
    "CLOUD-DEPLOYMENT-ANALYSIS.html", "engineering-report", "test.nio.jvm.args",
)
STALE_NAME = re.compile("|".join(re.escape(name).replace("/", r"[\\/]") for name in STALE_NAMES))
# A module's src, target or pom.xml, which must be named from its directory under modules/.
MODULE_PATH = re.compile(r"(?<![\w-])(datacraft-[a-z]+)[\\/](?:src|target|pom\.xml)(?![\w-])")
# Maven selects modules by :artifactId; a bare module name is a directory that no longer exists.
PATH_SELECTED_MODULE = re.compile(r"(?<![\w-])(?:-pl|--projects)[\s=]+(?:[^\s,]*,)*datacraft-")

STALE_PHRASES = (r"cloudflare[\s-]+vs\.?[\s-]+aws", r"decision[\s-]+tree", r"verified[\s-]+arm64")
RELEASE = re.compile(r"(?:(?<![\w-])--?release[\s:=]+|\brelease=)(\d+)\b")
WORKSTATION_JDK = re.compile(r"D:[\\/]+Java", re.I)
SERVE_API_HOST = re.compile(r"--host[\s=]+['\"`]?([^\s'\"`<>]+)")
AIRFLOW_PINS = (
    ("apache-airflow==", re.compile(r"apache-airflow==(\d+(?:\.\d+)+)")),
    ("apache/airflow:", re.compile(r"apache/airflow:(\d+(?:\.\d+)+)")),
    # The constraints branch is constraints-<airflow>/; the file inside it names the Python.
    ("constraints-", re.compile(r"\bconstraints-(\d+(?:\.\d+)+)/")),
)
# The pin kinds each file must carry; the Airflow guide may show any of them.
AIRFLOW_PIN_FILES = {
    CI_WORKFLOW: ("apache-airflow==", "constraints-"),
    "deploy/docker/Dockerfile.airflow": ("apache/airflow:",),
    "docs/guides/airflow.md": (),
}
SETUP_PYTHON = re.compile(
    r"^\s*python-version:\s*['\"]?(\d+\.\d+)(?:\.\d+)?['\"]?\s*(?:#.*)?$", re.M)
CONSTRAINTS_PYTHON = re.compile(r"\bconstraints-\d+(?:\.\d+)+/constraints-(\d+\.\d+)\.txt\b")


def read(name):
    return (ROOT / name).read_text(encoding="utf-8")


def read_text_file(name):
    """A file's UTF-8 text (undecodable bytes replaced), or None for a binary file."""
    data = (ROOT / name).read_bytes()
    return None if b"\0" in data else data.decode("utf-8", errors="replace")


@functools.lru_cache(maxsize=None)
def nested_checkout(directory):
    """True when a directory below ROOT ('' is ROOT) belongs to another checkout or worktree."""
    if not directory:
        return False
    return (ROOT / directory / ".git").exists() or nested_checkout(posixpath.dirname(directory))


def walked_files():
    names = []
    for directory, subdirectories, files in os.walk(ROOT):
        relative = Path(directory).relative_to(ROOT).as_posix()
        relative = "" if relative == "." else relative
        subdirectories[:] = [
            name for name in subdirectories
            if name not in SKIPPED_DIRECTORIES
            and (relative or name not in SKIPPED_ROOT_DIRECTORIES)
            and not Path(directory, name, ".git").exists()]
        names.extend(posixpath.join(relative, name) for name in files)
    return names


@functools.lru_cache(maxsize=None)
def repository_files():
    """Existing tracked and untracked-but-not-ignored files as POSIX paths relative to ROOT."""
    try:
        listed = subprocess.run(
            ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
            cwd=ROOT, capture_output=True, check=True, timeout=120).stdout
        names = [name for name in listed.decode("utf-8", "surrogateescape").split("\0")
                 if name and not name.endswith("/")]
    except (OSError, subprocess.SubprocessError):
        names = walked_files()
    # Worktrees checked out inside the repository show up as untracked files where git cannot
    # resolve their .git link (for example a Windows worktree read from WSL).
    return frozenset(name for name in names
                     if not nested_checkout(posixpath.dirname(name)) and (ROOT / name).is_file())


@functools.lru_cache(maxsize=None)
def repository_directories():
    directories = {"."}
    for name in repository_files():
        parent = posixpath.dirname(name)
        while parent:
            directories.add(parent)
            parent = posixpath.dirname(parent)
    return frozenset(directories)


def is_page(name):
    return name.lower().endswith(PAGE_SUFFIXES)


def doc_pages():
    return sorted(name for name in repository_files()
                  if name.startswith(DOC_DIRECTORIES) and is_page(name))


def doc_text_files():
    return sorted(name for name in repository_files()
                  if name.startswith(DOC_DIRECTORIES) and name.lower().endswith(TEXT_SUFFIXES))


@functools.lru_cache(maxsize=None)
def directory_entries(directory):
    try:
        return frozenset(os.listdir(ROOT / directory))
    except OSError:
        return frozenset()


def exists_as_written(path):
    """Path existence with case-sensitive names, so a Windows run fails where Linux CI would."""
    parent = ""
    for part in path.split("/"):
        if part not in directory_entries(parent):
            return False
        parent = posixpath.join(parent, part)
    return True


class _LinkAttributes(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.links = []

    def handle_starttag(self, tag, attrs):
        self.links.extend(value for name, value in attrs if name in ("href", "src") and value)


def html_links(text):
    parser = _LinkAttributes()
    parser.feed(text)
    parser.close()
    return parser.links


def markdown_links(text):
    """Inline, reference and raw-HTML link targets outside code and comments."""
    text = INLINE_CODE.sub("", HTML_COMMENT.sub("", FENCED_CODE.sub("", text)))
    links = [match.group(1) for pattern in (INLINE_LINK, REFERENCE_LINK)
             for match in pattern.finditer(text)]
    links = [link[1:-1] if link.startswith("<") else link for link in links]
    return links + html_links(text)


def link_target(source, link):
    """The normalised repository path a relative link names; None for external or same-page."""
    link = link.strip()
    if not link or link.startswith(("#", "//")) or SCHEME.match(link):
        return None
    path = unquote(re.split(r"[?#]", link, maxsplit=1)[0])
    if not path:
        return None
    base = "" if path.startswith("/") else posixpath.dirname(source)
    return posixpath.normpath(posixpath.join(base, path.lstrip("/")))


def opened_file(target):
    """What a link target opens: the file, a directory's README.md, '' for a directory without
    one, or None when nothing exists there."""
    if target in repository_directories() and (target == "." or exists_as_written(target)):
        readme = posixpath.normpath(posixpath.join(target, "README.md"))
        return readme if readme in repository_files() else ""
    if target in repository_files() and exists_as_written(target):
        return target
    return None


@functools.lru_cache(maxsize=None)
def doc_links():
    """Broken relative links on the pages under docs/ and design/, and the pages each opens."""
    broken, edges = [], []
    for page in doc_pages():
        text = read(page)
        links = markdown_links(text) if page.lower().endswith(".md") else html_links(text)
        opened_pages = set()
        for link in links:
            target = link_target(page, link)
            if target is None:
                continue
            if target == ".." or target.startswith("../"):
                broken.append(f"{page} -> {link} (outside the repository)")
                continue
            opened = opened_file(target)
            if opened is None:
                broken.append(f"{page} -> {link}")
            elif is_page(opened):
                opened_pages.add(opened)
        edges.append((page, frozenset(opened_pages)))
    return tuple(broken), tuple(edges)


def reachable_pages(start, edges):
    """Pages reachable from start; only pages under docs/ and design/ are followed further."""
    pages, queue = {start}, [start]
    while queue:
        for target in sorted(edges.get(queue.pop(0), ())):
            if target not in pages:
                pages.add(target)
                queue.append(target)
    return pages


def html_text(text):
    """HTML as text: comments and tags removed (their line breaks kept), entities decoded."""
    def line_breaks(match):
        return "\n" * match.group(0).count("\n")
    return html.unescape(HTML_TAG.sub(line_breaks, HTML_COMMENT.sub(line_breaks, text)))


def page_text(name):
    text = read(name)
    return text if name.lower().endswith(".md") else html_text(text)


@functools.lru_cache(maxsize=None)
def pom():
    return ElementTree.parse(ROOT / "pom.xml").getroot()


def pom_property(name):
    element = pom().find(f"{{*}}properties/{{*}}{name}")
    return None if element is None or not element.text else element.text.strip()


def module_directories():
    """{artifactId: directory} from the root pom's <modules>; directories end in the artifactId."""
    paths = [(module.text or "").strip() for module in pom().findall("{*}modules/{*}module")]
    return {posixpath.basename(path): path for path in paths if path}


def misplaced_module_paths(line, directories):
    """Module src/target/pom.xml paths in line that do not start at the module's directory."""
    found = []
    for match in MODULE_PATH.finditer(line):
        directory = directories.get(match.group(1))
        parent = posixpath.dirname(directory) if directory else ""
        before = line[:match.start()].replace("\\", "/")
        if parent and not before.endswith(parent + "/"):
            found.append(match.group(0))
    return found


def stale_mentions(text, directories):
    """(line number, text) of each moved path, renamed name or module selected by directory."""
    found = []
    for number, line in enumerate(text.splitlines(), 1):
        found.extend((number, match.group(0)) for match in STALE_NAME.finditer(line))
        found.extend((number, path) for path in misplaced_module_paths(line, directories))
        found.extend((number, match.group(0)) for match in PATH_SELECTED_MODULE.finditer(line))
    return found


def published_ports(compose_text):
    """(bind address, host port) of each short-syntax `ports:` entry; '' is every interface."""
    entries, indent = [], None
    for line in compose_text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        depth = len(line) - len(line.lstrip())
        if indent is not None and depth > indent:
            if stripped.startswith("- "):
                entries.append(stripped[2:])
            continue
        indent = depth if re.fullmatch(r"ports:\s*(#.*)?", stripped) else None
    ports = []
    for entry in entries:
        value = re.sub(r"\s+#.*$", "", entry).strip().strip("'\"")
        value = COMPOSE_VARIABLE.sub(lambda match: match.group(1) or "", value).split("/")[0]
        if value.startswith("["):
            bind, _, rest = value[1:].partition("]")
            parts = [bind] + rest.lstrip(":").split(":")
        else:
            parts = value.split(":")
        if len(parts) == 3:
            ports.append((parts[0], parts[1]))
        elif len(parts) == 2:
            ports.append(("", parts[0]))
    return ports


def is_loopback(host):
    return host in ("localhost", "::1") or host.startswith("127.")


def logical_lines(text):
    """(first line number, text) with shell backslash and PowerShell backtick continuations."""
    lines, pending, first = [], "", None
    for number, line in enumerate(text.splitlines(), 1):
        first = number if first is None else first
        stripped = line.rstrip()
        if stripped.endswith("\\") or stripped.endswith(" `"):
            pending += stripped[:-1] + " "
            continue
        lines.append((first, pending + line))
        pending, first = "", None
    if pending:
        lines.append((first, pending))
    return lines


def binds_off_loopback(line):
    """A serve-api command whose literal --host is not a loopback address (variables skipped)."""
    if "serve-api" not in line:
        return False
    hosts = [match.group(1).rstrip(".,;)").strip("[]")
             for match in SERVE_API_HOST.finditer(line)]
    return any(re.fullmatch(r"[A-Za-z0-9.:-]+", host) and not is_loopback(host)
               for host in hosts)


def workflow_job(text, job):
    """The non-comment lines of jobs.<job> in a GitHub Actions workflow, found by indentation."""
    body, in_jobs, key_indent, in_job = [], False, None, False
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(line) - len(line.lstrip(" "))
        if indent == 0:
            if in_job:
                break
            in_jobs = re.fullmatch(r"jobs:\s*(?:#.*)?", stripped) is not None
            continue
        if not in_jobs:
            continue
        key_indent = indent if key_indent is None else key_indent
        if indent <= key_indent:
            if in_job:
                break
            in_job = indent == key_indent and re.fullmatch(
                rf"{re.escape(job)}:\s*(?:#.*)?", stripped) is not None
        elif in_job:
            body.append(line)
    return body


def airflow_pins(text):
    return {kind: sorted(set(pattern.findall(text))) for kind, pattern in AIRFLOW_PINS}


def line_number(text, match):
    return text.count("\n", 0, match.start()) + 1


class DocsConsistencyTests(unittest.TestCase):
    maxDiff = None

    def test_helpers_follow_markdown_html_compose_and_workflow_syntax(self):
        text = ("[a](x.md#s) [b](<y z.md>) ![c](img/p.png \"t\")\n`[d](code.md)`\n"
                "```\n[e](fenced.md)\n```\n<!-- [f](comment.md) -->\n[g]: ref.md\n"
                "[^1]: footnote\n<a href=\"h.html?q=1\">h</a>\n")
        self.assertEqual(["x.md#s", "y z.md", "img/p.png", "ref.md", "h.html?q=1"],
                         markdown_links(text))
        self.assertEqual("docs/b.md", link_target("docs/a.md", "b.md#top"))
        self.assertEqual("design/c.md", link_target("docs/a.md", "../design/c.md"))
        self.assertEqual("..", link_target("docs/a.md", "../../x/.."))
        self.assertEqual("docs/a b.md", link_target("docs/x.html", "a%20b.md?x=1"))
        for link in ("#top", "https://x.test/", "mailto:a@x.test", "//cdn.test/x.js", "data:,x"):
            self.assertIsNone(link_target("docs/a.md", link), link)
        self.assertEqual("x\n\n--host 0.0.0.0 &\n",
                         html_text("<p>x<br\n/>\n--host <b>0.0.0.0</b> &amp;</p><!-- c -->\n"))

        modules = {"datacraft-cli": "modules/interfaces/datacraft-cli",
                   "datacraft-io": "modules/io/datacraft-io"}
        sample = ("see deploy\\tests and `.github/scripts/x.py`\n"
                  "java -jar modules/interfaces/datacraft-cli/target/datacraft-cli.jar\n"
                  "java -jar datacraft-cli/target/datacraft-cli.jar\n"
                  "modules/core/datacraft-io/src tests/jvm/datacraft-io/java datacraft-lab/src\n"
                  "./mvnw -pl datacraft-cli; mvn -pl :datacraft-cli -am\n"
                  "mvn -pl :datacraft-api,datacraft-io\n")
        self.assertEqual([(1, "deploy\\tests"), (1, ".github/scripts"),
                          (3, "datacraft-cli/target"), (4, "datacraft-io/src"),
                          (5, "-pl datacraft-"), (6, "-pl :datacraft-api,datacraft-")],
                         stale_mentions(sample, modules))
        for name in ("tests/deploy/test_x.py", "deploy/x_test.py", "tests/docs/a.test.cjs"):
            self.assertIsNotNone(TEST_SCRIPT.search(name), name)
        for name in ("deploy/scripts/deployment_env.py", "tests/ci/check_test_counts.py"):
            self.assertIsNone(TEST_SCRIPT.search(name), name)
        self.assertIsNotNone(MAVEN_TEST_TREE.search("modules/io/datacraft-io/src/test/java/X"))

        workflow = ("on: push\njobs:\n  build:\n    steps:\n      - run: echo dags:\n"
                    "  dags:\n    steps:\n      # python-version: \"3.11\"\n"
                    "      - with:\n          python-version: \"3.13\"\n"
                    "      - run: pip install 'apache-airflow==3.3.2' -c "
                    "https://x.test/constraints-3.3.2/constraints-3.13.txt\n"
                    "  scripts:\n    steps: []\n")
        job = "\n".join(workflow_job(workflow, "dags"))
        self.assertEqual(["3.13"], SETUP_PYTHON.findall(job))
        self.assertEqual(["3.13"], CONSTRAINTS_PYTHON.findall(job))
        self.assertEqual({"apache-airflow==": ["3.3.2"], "apache/airflow:": [],
                          "constraints-": ["3.3.2"]}, airflow_pins(job))
        self.assertEqual([], workflow_job(workflow, "missing"))

        compose = ("services:\n  a:\n    ports:\n"
                   "      - \"${BIND:-127.0.0.1}:${PORT:-8080}:8080\"  # UI\n"
                   "      - 9000:9000/tcp\n      - \"5000\"\n    environment:\n      - X=1:2:3\n")
        self.assertEqual([("127.0.0.1", "8080"), ("", "9000")], published_ports(compose))
        self.assertEqual([(1, "a  b"), (3, "c")], logical_lines("a \\\nb\nc"))
        self.assertTrue(binds_off_loopback("java -jar x.jar --command serve-api --host 0.0.0.0"))
        self.assertTrue(binds_off_loopback("--command serve-api --port 1 --host=10.0.0.5"))
        self.assertTrue(binds_off_loopback("run `--command serve-api --host 0.0.0.0`."))
        self.assertTrue(binds_off_loopback("--command serve-api --host [::]"))
        self.assertFalse(binds_off_loopback("--command serve-api --host 127.0.0.1"))
        self.assertFalse(binds_off_loopback("--command serve-api --host $bind"))
        self.assertFalse(binds_off_loopback("--command serve-api --host <address>"))

    def test_relative_links_in_docs_and_design_resolve(self):
        broken, _ = doc_links()
        self.assertEqual([], list(broken), "broken relative links:\n" + "\n".join(broken))

    def test_every_doc_is_reachable_from_docs_readme(self):
        self.assertTrue(INDEX in repository_files(), f"{INDEX} is missing")
        _, edges = doc_links()
        reachable = reachable_pages(INDEX, dict(edges))
        unreachable = [page for page in doc_pages() if page not in reachable]
        self.assertEqual([], unreachable,
                         f"no chain of relative links from {INDEX} reaches these pages")

    def test_documentation_lives_only_in_docs_and_design(self):
        misplaced = sorted(name for name in repository_files()
                           if is_page(name) and not name.startswith(DOC_DIRECTORIES))
        self.assertEqual([], misplaced, "Markdown and HTML documents belong in docs/ or design/")

    def test_tests_live_only_in_tests(self):
        misplaced = sorted(name for name in repository_files()
                           if MAVEN_TEST_TREE.search(name)
                           or (TEST_SCRIPT.search(name) and not name.startswith("tests/")))
        self.assertEqual([], misplaced,
                         "tests belong in tests/ (JVM tests in tests/jvm/<artifactId>/)")

    def test_no_moved_path_or_renamed_name_is_mentioned(self):
        directories = module_directories()
        self.assertTrue(directories, "pom.xml lists no <modules>")
        offenders = []
        for name in sorted(repository_files() - {CHANGELOG, THIS_FILE}):
            text = read_text_file(name)
            if text is not None:
                offenders.extend(f"{name}:{number}: {mention}"
                                 for number, mention in stale_mentions(text, directories))
        self.assertEqual([], offenders,
                         "these name a path or property the 2026-09-26 restructure moved or "
                         "renamed (see docs/CHANGELOG.md), or select a module by directory name "
                         "instead of -pl :<artifactId>")

    def test_documented_java_release_matches_the_build(self):
        java_version = pom_property("java.version")
        if java_version is None:
            self.fail("pom.xml must declare java.version")
        offenders = []
        for name in doc_text_files():
            text = read(name)
            offenders.extend(f"{name}:{line_number(text, match)}: {match.group(0)}"
                             for match in RELEASE.finditer(text)
                             if match.group(1) != java_version)
        self.assertEqual([], offenders, f"pom.xml java.version is {java_version}")

    def test_no_workstation_jdk_path(self):
        offenders = []
        for name in doc_text_files():
            for number, line in enumerate(read(name).splitlines(), 1):
                if WORKSTATION_JDK.search(line) or WORKSTATION_JDK.search(html.unescape(line)):
                    offenders.append(f"{name}:{number}")
        self.assertEqual([], offenders, "use a JAVA_HOME placeholder, not this workstation's path")

    def test_serve_api_off_loopback_names_the_data_root(self):
        offenders = []
        for name in doc_pages():
            lines = logical_lines(page_text(name))
            for index, (number, text) in enumerate(lines):
                following = lines[index + 1][1] if index + 1 < len(lines) else ""
                if binds_off_loopback(text) and "DATACRAFT_DATA_ROOT" not in text + following:
                    offenders.append(f"{name}:{number}")
        self.assertEqual([], offenders,
                         "serve-api refuses a non-loopback --host without DATACRAFT_DATA_ROOT; "
                         "name the variable on the same or the next line")

    def test_airflow_pins_agree(self):
        found = {}
        for name, required in AIRFLOW_PIN_FILES.items():
            with self.subTest(file=name):
                if not (ROOT / name).is_file():
                    self.fail(f"{name} is missing")
                pins = {kind: versions for kind, versions in airflow_pins(read(name)).items()
                        if versions}
                found[name] = pins
                self.assertTrue(pins, f"{name} pins no Airflow version")
                missing = [kind for kind in required if kind not in pins]
                self.assertEqual([], missing, f"{name} lacks these Airflow pins")
        versions = {version for pins in found.values() for listed in pins.values()
                    for version in listed}
        self.assertEqual(1, len(versions), f"Airflow pins disagree: {found}")

    def test_dags_job_constraints_match_its_python(self):
        job = "\n".join(workflow_job(read(CI_WORKFLOW), "dags"))
        self.assertTrue(job, f"{CI_WORKFLOW} has no dags job")
        pythons = set(SETUP_PYTHON.findall(job))
        constrained = set(CONSTRAINTS_PYTHON.findall(job))
        self.assertTrue(pythons, "the dags job sets up no literal python-version")
        self.assertTrue(constrained,
                        "the dags job installs Airflow without a constraints-3.NN.txt file")
        self.assertEqual(pythons, constrained,
                         "the dags job's constraints file must match its setup-python version")

    def test_readme_and_changelog_drop_stale_cloud_claims(self):
        for name in (INDEX, CHANGELOG):
            text = read(name)
            for phrase in STALE_PHRASES:
                with self.subTest(file=name, phrase=phrase):
                    match = re.search(phrase, text, re.I)
                    where = f"{name}:{line_number(text, match)}" if match else ""
                    self.assertIsNone(match, f"{where} says {match and match.group(0)!r}")

    def test_loopback_only_ports_are_not_advertised_on_a_host_placeholder(self):
        compose = read("deploy/compose/docker-compose.yml")
        ports = published_ports(compose)
        self.assertTrue(ports, "no short-syntax port mapping parsed from docker-compose.yml")
        loopback = sorted({port for bind, port in ports if is_loopback(bind)})
        if not loopback:
            return
        alternatives = "|".join(re.escape(port) for port in loopback)
        advertised = re.compile(rf"https?://<host>:(?:{alternatives})(?![0-9])", re.I)
        offenders = []
        for name in sorted(repository_files()):
            if not is_page(name):
                continue
            for number, line in enumerate(read(name).splitlines(), 1):
                if advertised.search(line) or advertised.search(html.unescape(line)):
                    offenders.append(f"{name}:{number}")
        self.assertEqual([], offenders,
                         f"docker-compose.yml publishes {loopback} on loopback only; use "
                         "localhost through an SSH tunnel instead of http://<host>:<port>")


if __name__ == "__main__":
    unittest.main()
