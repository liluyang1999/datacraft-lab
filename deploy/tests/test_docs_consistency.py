"""The documentation must link to files that exist and agree with the build and deployment files.

Relative links are crawled from README.md through every reachable Markdown and HTML page. The other
checks pin claims that drifted before: loopback-only ports advertised on <host>, stale cloud
wording, a Java release other than the build's, a workstation JDK path, a serve-api command the
CLI refuses, and Airflow pins that disagree.
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

PAGE_SUFFIXES = (".md", ".html", ".htm")
TEXT_SUFFIXES = PAGE_SUFFIXES + (".css", ".js", ".cjs", ".mjs", ".json", ".txt", ".svg")
REQUIRED_PAGES = (
    "PROJECT-REVIEW.html",
    "docs/engineering-report/index.html",
    "docs/engineering-report/maven-build.html",
    "docs/engineering-report/2026-09-19-review.md",
    "docs/engineering-report/2026-09-25-review.md",
)
# Only used when git cannot list the files; approximates .gitignore for a plain directory walk.
SKIPPED_DIRECTORIES = {".git", ".idea", ".venv", ".vscode", "__pycache__", "build", "node_modules",
                       "target"}
SKIPPED_ROOT_DIRECTORIES = {".airflow", ".worktrees", "backups", "data", "dist", "logs",
                            "spark-warehouse", "tmp", "warehouse"}

FENCED_CODE = re.compile(r"^ {0,3}(`{3,}|~{3,}).*?^ {0,3}\1[ \t]*$", re.M | re.S)
INLINE_CODE = re.compile(r"(`+)(?!`).+?(?<!`)\1(?!`)")
HTML_COMMENT = re.compile(r"<!--.*?-->", re.S)
INLINE_LINK = re.compile(r"\]\(\s*(<[^<>\n]*>|[^\s()]+(?:\([^\s()]*\)[^\s()]*)*)")
REFERENCE_LINK = re.compile(r"^ {0,3}\[(?!\^)[^\]\n]+\]:[ \t]*(<[^<>\n]*>|\S+)", re.M)
SCHEME = re.compile(r"[A-Za-z][A-Za-z0-9+.-]*:")
COMPOSE_VARIABLE = re.compile(r"\$\{[A-Za-z_][A-Za-z0-9_]*(?::?-([^}]*))?\}")

STALE_PHRASES = (r"cloudflare[\s-]+vs\.?[\s-]+aws", r"decision[\s-]+tree", r"verified[\s-]+arm64")
RELEASE = re.compile(r"(?:(?<![\w-])--?release[\s:=]+|\brelease=)(\d+)\b")
WORKSTATION_JDK = re.compile(r"D:[\\/]+Java", re.I)
SERVE_API_HOST = re.compile(r"--host[\s=]+['\"]?([^\s'\"]+)")
# The constraints branch is constraints-<airflow>/; the file inside it names the Python version.
AIRFLOW_PIN = re.compile(r"(?:apache-airflow==|apache/airflow:|\bconstraints-(?=[\d.]+/))"
                         r"(\d+(?:\.\d+)+)")
AIRFLOW_PIN_FILES = (".github/workflows/ci.yml", "deploy/docker/Dockerfile.airflow",
                     "orchestration/airflow/README.md")


def read(name):
    return (ROOT / name).read_text(encoding="utf-8")


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


def docs_text_files():
    return sorted(name for name in repository_files()
                  if name.startswith("docs/") and name.lower().endswith(TEXT_SUFFIXES))


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


@functools.lru_cache(maxsize=None)
def crawl(start="README.md"):
    """Pages reachable from start through relative links, and every broken link on them."""
    files = repository_files()
    directories = {"."}
    for name in files:
        parent = posixpath.dirname(name)
        while parent:
            directories.add(parent)
            parent = posixpath.dirname(parent)
    pages, queue, broken = {start}, [start], []
    while queue:
        page = queue.pop(0)
        text = read(page)
        links = markdown_links(text) if page.lower().endswith(".md") else html_links(text)
        for link in links:
            target = link_target(page, link)
            if target is None:
                continue
            if target == ".." or target.startswith("../"):
                broken.append(f"{page} -> {link} (outside the repository)")
                continue
            if target in directories and (target == "." or exists_as_written(target)):
                # A directory link renders that directory's README on the code host.
                target = posixpath.normpath(posixpath.join(target, "README.md"))
                if target not in files:
                    continue
            elif target not in files or not exists_as_written(target):
                broken.append(f"{page} -> {link}")
                continue
            if target.lower().endswith(PAGE_SUFFIXES) and target not in pages:
                pages.add(target)
                queue.append(target)
    return frozenset(pages), tuple(broken)


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
    hosts = [match.group(1).strip("[]") for match in SERVE_API_HOST.finditer(line)]
    return any(re.fullmatch(r"[A-Za-z0-9.:-]+", host) and not is_loopback(host)
               for host in hosts)


def line_number(text, match):
    return text.count("\n", 0, match.start()) + 1


class DocsConsistencyTests(unittest.TestCase):
    maxDiff = None

    def test_parsers_follow_markdown_html_and_compose_syntax(self):
        text = ("[a](x.md#s) [b](<y z.md>) ![c](img/p.png \"t\")\n`[d](code.md)`\n"
                "```\n[e](fenced.md)\n```\n<!-- [f](comment.md) -->\n[g]: ref.md\n"
                "[^1]: footnote\n<a href=\"h.html?q=1\">h</a>\n")
        self.assertEqual(["x.md#s", "y z.md", "img/p.png", "ref.md", "h.html?q=1"],
                         markdown_links(text))
        self.assertEqual("docs/b.md", link_target("docs/a.md", "b.md#top"))
        self.assertEqual("README.md", link_target("docs/a.md", "../README.md"))
        self.assertEqual("docs/a b.md", link_target("docs/x.html", "a%20b.md?x=1"))
        for link in ("#top", "https://x.test/", "mailto:a@x.test", "//cdn.test/x.js", "data:,x"):
            self.assertIsNone(link_target("docs/a.md", link), link)
        compose = ("services:\n  a:\n    ports:\n"
                   "      - \"${BIND:-127.0.0.1}:${PORT:-8080}:8080\"  # UI\n"
                   "      - 9000:9000/tcp\n      - \"5000\"\n    environment:\n      - X=1:2:3\n")
        self.assertEqual([("127.0.0.1", "8080"), ("", "9000")], published_ports(compose))
        self.assertEqual([(1, "a  b"), (3, "c")], logical_lines("a \\\nb\nc"))
        self.assertTrue(binds_off_loopback("java -jar x.jar --command serve-api --host 0.0.0.0"))
        self.assertTrue(binds_off_loopback("--command serve-api --port 1 --host=10.0.0.5"))
        self.assertFalse(binds_off_loopback("--command serve-api --host 127.0.0.1"))
        self.assertFalse(binds_off_loopback("--command serve-api --host $bind"))

    def test_relative_links_resolve(self):
        _, broken = crawl()
        self.assertEqual([], list(broken), "broken relative links:\n" + "\n".join(broken))

    def test_review_pages_are_reachable_from_readme(self):
        pages, _ = crawl()
        for page in REQUIRED_PAGES:
            with self.subTest(page=page):
                self.assertIn(page, pages, f"{page} is not reachable from README.md")

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
            if not name.lower().endswith(PAGE_SUFFIXES):
                continue
            for number, line in enumerate(read(name).splitlines(), 1):
                if advertised.search(line) or advertised.search(html.unescape(line)):
                    offenders.append(f"{name}:{number}")
        self.assertEqual([], offenders,
                         f"docker-compose.yml publishes {loopback} on loopback only; use "
                         "localhost through an SSH tunnel instead of http://<host>:<port>")

    def test_readme_and_changelog_drop_stale_cloud_claims(self):
        for name in ("README.md", "CHANGELOG.md"):
            text = read(name)
            for phrase in STALE_PHRASES:
                with self.subTest(file=name, phrase=phrase):
                    match = re.search(phrase, text, re.I)
                    where = f"{name}:{line_number(text, match)}" if match else ""
                    self.assertIsNone(match, f"{where} says {match and match.group(0)!r}")

    def test_documented_java_release_matches_the_build(self):
        pom = ElementTree.parse(ROOT / "pom.xml").getroot()
        java_version = pom.find("{*}properties/{*}java.version").text.strip()
        offenders = []
        for name in ["README.md", "PROJECT-REVIEW.html"] + docs_text_files():
            text = read(name)
            offenders.extend(f"{name}:{line_number(text, match)}: {match.group(0)}"
                             for match in RELEASE.finditer(text)
                             if match.group(1) != java_version)
        self.assertEqual([], offenders, f"pom.xml java.version is {java_version}")

    def test_no_workstation_jdk_path(self):
        offenders = []
        for name in ["README.md"] + docs_text_files():
            for number, line in enumerate(read(name).splitlines(), 1):
                if WORKSTATION_JDK.search(line) or WORKSTATION_JDK.search(html.unescape(line)):
                    offenders.append(f"{name}:{number}")
        self.assertEqual([], offenders, "use a JAVA_HOME placeholder, not this workstation's path")

    def test_readme_serve_api_off_loopback_names_the_data_root(self):
        lines = logical_lines(read("README.md"))
        offenders = []
        for index, (number, text) in enumerate(lines):
            following = lines[index + 1][1] if index + 1 < len(lines) else ""
            if binds_off_loopback(text) and "DATACRAFT_DATA_ROOT" not in text + following:
                offenders.append(f"README.md:{number}")
        self.assertEqual([], offenders,
                         "serve-api refuses a non-loopback --host without DATACRAFT_DATA_ROOT")

    def test_airflow_pins_agree(self):
        pins = {name: sorted(set(AIRFLOW_PIN.findall(read(name)))) for name in AIRFLOW_PIN_FILES}
        for name, versions in pins.items():
            with self.subTest(file=name):
                self.assertTrue(versions, f"{name} pins no Airflow version")
        versions = {version for found in pins.values() for version in found}
        self.assertEqual(1, len(versions), f"Airflow pins disagree: {pins}")


if __name__ == "__main__":
    unittest.main()
