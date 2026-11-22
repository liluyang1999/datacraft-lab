"""Prints the product version, the only one the repository has (docs/versioning.md).

The version is the `revision` property of the root pom.xml. Every module, the desktop console,
the CLI usage header, the engine API, the app image and the Windows installer take it from there;
scripts and workflows call this instead of writing the number again.

The value must be MAJOR.MINOR.PATCH: three decimal numbers of at most 65535 with no pre-release or
build suffix, because Windows version resources and the installer's upgrade and downgrade checks
compare only numbers.

Standard library only; runs on Windows and Linux.

Usage: python3 -B cicd/release/version.py [--root REPOSITORY_ROOT]
Exit status: 0 with the version on stdout, 1 when the POM holds no valid version.
"""

import argparse
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ElementTree

POM_NAMESPACE = "{http://maven.apache.org/POM/4.0.0}"
RELEASE = re.compile(r"(0|[1-9]\d{0,4})\.(0|[1-9]\d{0,4})\.(0|[1-9]\d{0,4})")
MAX_COMPONENT = 65535


class InvalidVersion(Exception):
    """The root POM does not declare a usable product version."""


def product_version(root):
    """The root POM's revision, checked against the release format."""
    pom = Path(root) / "pom.xml"
    try:
        project = ElementTree.parse(pom).getroot()
    except (OSError, ElementTree.ParseError) as error:
        raise InvalidVersion(f"cannot read {pom}: {error}") from error
    if project.findtext(f"{POM_NAMESPACE}version") != "${revision}":
        raise InvalidVersion(f"{pom} must declare <version>${{revision}}</version>")
    revisions = project.findall(f"{POM_NAMESPACE}properties/{POM_NAMESPACE}revision")
    if len(revisions) != 1:
        raise InvalidVersion(f"{pom} must declare exactly one <revision> property")
    return check(revisions[0].text or "")


def check(version):
    """Returns the version when it is MAJOR.MINOR.PATCH with components of at most 65535."""
    match = RELEASE.fullmatch(version.strip())
    if match is None or version != version.strip():
        raise InvalidVersion(f"'{version}' is not MAJOR.MINOR.PATCH without a suffix")
    if any(int(part) > MAX_COMPONENT for part in match.groups()):
        raise InvalidVersion(f"'{version}' has a component above {MAX_COMPONENT}")
    return version


def main(argv=None):
    parser = argparse.ArgumentParser(description="Prints the product version (the root POM revision).")
    parser.add_argument("--root", default=Path(__file__).resolve().parents[2], type=Path)
    arguments = parser.parse_args(argv)
    try:
        print(product_version(arguments.root))
    except InvalidVersion as problem:
        print(f"version: {problem}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
