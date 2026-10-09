"""Fails when a version this repository pins has a known security advisory.

The build pins every dependency and every action, so nothing changes without a commit, and nothing
reports when a pinned version turns out to be vulnerable either. This check asks OSV
(https://osv.dev) about each pin:

- the dependencies the root pom.xml manages (Jackson's BOM stands for jackson-core and
  jackson-databind, the two artifacts the Enforcer already bans by version);
- apache-airflow and pyspark as cicd/airflow/install-airflow.sh installs them, the FAB provider at
  the floor of cicd/airflow/check_security_floor.py, and shellcheck-py;
- pyright, as the Makefile pins it;
- the GitHub Actions the workflows use, by the release named after each pinned commit.

A scheduled workflow runs it, so a new advisory appears as a failed run and no bot has to open pull
requests. Not covered: transitive dependencies, which Spark and the Airflow constraints file pin,
and the base images.

OSV matches versions itself for Maven, PyPI and npm. For GitHub Actions it only lists advisories, so
their version ranges are evaluated here, as the OSV schema describes.

Standard library only: the XML parsed is this repository's own pom.xml.

Usage: python3 -B cicd/security/check_advisories.py [--list] [REPOSITORY_ROOT]
Exit status: 0 when no pin has an advisory, 1 when one has, 2 when OSV could not be asked.
"""

import argparse
import json
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

OSV = "https://api.osv.dev/v1"
ACTIONS = "GitHub Actions"
# Members of an imported BOM that this build uses and that carry code.
BOM_MEMBERS = {
    "com.fasterxml.jackson:jackson-bom": ("com.fasterxml.jackson.core:jackson-core",
                                          "com.fasterxml.jackson.core:jackson-databind"),
}
PROPERTY = re.compile(r"\$\{([^}]+)\}")
PINNED_ACTION = re.compile(r"^\s*(?:-\s+)?uses:\s+([^@\s]+)@[0-9a-f]{40}\s+#\s*v([0-9][0-9A-Za-z.+-]*)\s*$")


class LookupFailed(Exception):
    """OSV could not be asked, so nothing is known about the pins."""


def maven_packages(root):
    """(ecosystem, name, version, source) of every dependency the root POM manages."""
    project = ET.parse(root / "pom.xml").getroot()
    properties = {node.tag.rpartition("}")[2]: (node.text or "").strip()
                  for node in project.findall("{*}properties/*")}

    def resolve(value):
        for _ in range(10):
            expanded = PROPERTY.sub(lambda match: properties.get(match.group(1), match.group(0)), value)
            if expanded == value:
                break
            value = expanded
        if "${" in value:
            raise ValueError(f"pom.xml: cannot resolve {value}")
        return value

    packages = []
    for dependency in project.findall("{*}dependencyManagement/{*}dependencies/{*}dependency"):
        name = resolve(f"{dependency.findtext('{*}groupId')}:{dependency.findtext('{*}artifactId')}")
        version = resolve(dependency.findtext("{*}version") or "")
        if dependency.findtext("{*}scope") == "import":
            if name not in BOM_MEMBERS:
                raise ValueError(f"pom.xml imports {name}: list the members to check in BOM_MEMBERS")
            packages += [("Maven", member, version, "pom.xml") for member in BOM_MEMBERS[name]]
        else:
            packages.append(("Maven", name, version, "pom.xml"))
    if not packages:
        raise ValueError("pom.xml manages no dependency")
    return packages


def pins(root, path, pattern, ecosystem, name=None):
    """The versions one file pins; pattern captures the name and the version, or the version alone
    when name is given.

    A file in which the pattern finds nothing is an error, not an empty list: a reworded pin would
    otherwise drop out of the check unnoticed.
    """
    matches = re.findall(pattern, (root / path).read_text(encoding="utf-8"), re.MULTILINE)
    found = [(ecosystem, name, match, path) if name else (ecosystem, *match, path) for match in matches]
    if not found:
        raise ValueError(f"{path}: no pinned version found")
    return found


def action_packages(root):
    """The actions of every workflow, each at the release its pinned commit is commented with."""
    packages = []
    for workflow in sorted((root / ".github" / "workflows").glob("*.y*ml")):
        for line in workflow.read_text(encoding="utf-8").splitlines():
            match = PINNED_ACTION.match(line)
            if match:
                packages.append((ACTIONS, match.group(1), match.group(2), f".github/workflows/{workflow.name}"))
    if not packages:
        raise ValueError(".github/workflows: no pinned action found")
    return packages


def pinned(root):
    """Every pin to check, once each: (ecosystem, name, version, file that pins it)."""
    packages = maven_packages(root)
    packages += pins(root, "cicd/airflow/install-airflow.sh", r"'(apache-airflow|pyspark)==([0-9][^']*)'", "PyPI")
    packages += pins(root, "cicd/airflow/check_security_floor.py",
                     r'"(apache-airflow-providers-[a-z-]+)": "([0-9][^"]*)"', "PyPI")
    packages += pins(root, "cicd/lint/install-shellcheck.sh", r"^package_version=([0-9][0-9.]*)$", "PyPI",
                     name="shellcheck-py")
    packages += pins(root, "Makefile", r"\b(pyright)@([0-9][0-9.]*)", "npm")
    packages += action_packages(root)
    unique = {}
    for ecosystem, name, version, source in packages:
        unique.setdefault((ecosystem, name, version), source)
    return sorted((*key, source) for key, source in unique.items())


def call(path, payload=None):
    """One OSV request, retried while the connection fails; raises LookupFailed in the end."""
    data = None if payload is None else json.dumps(payload).encode()
    request = urllib.request.Request(OSV + path, data=data, headers={
        "Content-Type": "application/json", "User-Agent": "datacraft-lab-advisory-check"})
    failure = None
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code < 500:
                raise LookupFailed(f"OSV answered HTTP {error.code} for {path}") from None
            failure = error
        except (urllib.error.URLError, TimeoutError, ConnectionError, json.JSONDecodeError) as error:
            failure = error
        time.sleep(2 * (attempt + 1))
    raise LookupFailed(f"OSV is not reachable: {failure}")


def release(version):
    """A release number as a comparable tuple: v4.1.3 and 4.1.3 are (4, 1, 3), 41 is (41,)."""
    return tuple(int(part) for part in re.findall(r"\d+", version.split("-")[0].split("+")[0]))


def in_ranges(version, ranges):
    """Whether version lies in one of an advisory's ranges, by the evaluation the OSV schema gives."""
    current = release(version)
    for span in ranges:
        vulnerable = False
        events = sorted(span.get("events", []), key=lambda event: release(next(iter(event.values()))))
        for event in events:
            if "introduced" in event and current >= release(event["introduced"]):
                vulnerable = True
            elif "fixed" in event and current >= release(event["fixed"]):
                vulnerable = False
            elif "last_affected" in event and current > release(event["last_affected"]):
                vulnerable = False
        if vulnerable:
            return True
    return False


def action_advisories(name, version):
    """Advisory ids for one action release; OSV lists them, the ranges are matched here."""
    found = []
    for advisory in call("/query", {"package": {"ecosystem": ACTIONS, "name": name}}).get("vulns", []):
        for affected in advisory.get("affected", []):
            package = affected.get("package", {})
            if package.get("ecosystem") == ACTIONS and package.get("name", "").lower() == name.lower():
                listed = {entry.lstrip("v") for entry in affected.get("versions", [])}
                if version in listed or in_ranges(version, affected.get("ranges", [])):
                    found.append(advisory["id"])
                    break
    return found


def query(packages):
    """{(ecosystem, name, version): [advisory ids]} for the packages that have any."""
    matched = [package for package in packages if package[0] != ACTIONS]
    advisories = {}
    for start in range(0, len(matched), 500):
        chunk = matched[start:start + 500]
        results = call("/querybatch", {"queries": [
            {"package": {"ecosystem": ecosystem, "name": name}, "version": version}
            for ecosystem, name, version, _ in chunk]}).get("results", [])
        if len(results) != len(chunk):
            raise LookupFailed(f"OSV answered {len(results)} of {len(chunk)} queries")
        for (ecosystem, name, version, _), result in zip(chunk, results):
            ids = [entry["id"] for entry in result.get("vulns", [])]
            if ids:
                advisories[(ecosystem, name, version)] = ids
    for ecosystem, name, version, _ in packages:
        if ecosystem == ACTIONS:
            ids = action_advisories(name, version)
            if ids:
                advisories[(ecosystem, name, version)] = ids
    return advisories


def main(argv=None):
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n", 1)[0])
    parser.add_argument("root", nargs="?", default=".", help="repository root (default: .)")
    parser.add_argument("--list", action="store_true", help="print the pins and ask nothing")
    args = parser.parse_args(argv)
    try:
        packages = pinned(Path(args.root))
    except (OSError, ValueError, ET.ParseError) as error:
        print(f"::error::cannot read the pinned versions: {error}")
        return 2
    if args.list:
        for ecosystem, name, version, source in packages:
            print(f"{ecosystem}: {name} {version} ({source})")
        return 0
    try:
        advisories = query(packages)
    except LookupFailed as error:
        print(f"::error::{error}; the {len(packages)} pinned versions were not checked")
        return 2
    for ecosystem, name, version, source in packages:
        ids = advisories.get((ecosystem, name, version))
        if ids:
            links = ", ".join(f"https://osv.dev/vulnerability/{identifier}" for identifier in ids)
            print(f"::error::{ecosystem} {name} {version}, pinned in {source}, has advisories: {links}")
    if advisories:
        return 1
    print(f"no known advisory for {len(packages)} pinned versions")
    return 0


if __name__ == "__main__":
    sys.exit(main())
