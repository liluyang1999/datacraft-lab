"""Fail when a Maven module executed fewer tests than its committed floor.

Surefire's failIfNoTests only catches a module with zero tests, and scalatest-maven-plugin passes
when it finds no suites, so a renamed test class, a provider mismatch or a misfiring cancel could
silently shrink a module's suite while `./mvnw verify` stays green. This check reads the JUnit XML
reports that both plugins write to <module>/target/surefire-reports/TEST-*.xml, sums them per
module (ScalaTest also writes DiscoverySuite reports with tests="0"), and compares the executed
count (tests minus skipped) with FLOORS. Surefire counts skipped and aborted tests in a suite-level
skipped attribute; ScalaTest writes no such attribute and instead gives each canceled, ignored or
pending <testcase> a <skipped/> child. Both forms are read, and a test marked both ways counts once.

The floors depend on the platform that ran the tests, because the build excludes the other
platform's tagged tests: --platform names it and defaults to the platform this script runs on.

Usage: python3 -B cicd/build/check_test_counts.py [--platform {linux,windows}] [REPOSITORY_ROOT]
Exit status: 0 when every module meets its floor, 1 otherwise.
"""

import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

# Minimum executed tests per reactor module on Linux, where CI runs. Floors are minimums, not exact
# counts: adding tests needs no change, but deliberately removing tests needs a lower floor in the
# same commit.
FLOORS = {
    "datacraft-api": 17,
    "datacraft-common": 7,
    "datacraft-config": 13,
    "datacraft-engine": 32,
    "datacraft-io": 57,
    "datacraft-jobs": 70,
    "datacraft-cli": 28,
    "datacraft-spark": 52,
}

# The same on Windows, where the build excludes the posix-only tests (symbolic links, `?` in file
# names, Hadoop local writes) and runs datacraft-io's windows-only junction tests instead.
WINDOWS_FLOORS = {**FLOORS, "datacraft-io": 59, "datacraft-jobs": 68, "datacraft-spark": 44}

PLATFORM_FLOORS = {"linux": FLOORS, "windows": WINDOWS_FLOORS}


# Repository-relative directory of each reactor module, matching the <modules> list in pom.xml.
MODULE_DIRS = {
    "datacraft-common": "modules/core/datacraft-common",
    "datacraft-config": "modules/core/datacraft-config",
    "datacraft-engine": "modules/core/datacraft-engine",
    "datacraft-io": "modules/io/datacraft-io",
    "datacraft-jobs": "modules/processing/datacraft-jobs",
    "datacraft-spark": "modules/processing/datacraft-spark",
    "datacraft-api": "modules/interfaces/datacraft-api",
    "datacraft-cli": "modules/interfaces/datacraft-cli",
}


class ReportError(Exception):
    """A module's reports are missing or unreadable."""


def _suites(root):
    if root.tag == "testsuite":
        return [root]
    if root.tag == "testsuites":
        return root.findall("testsuite")
    raise ReportError(f"unexpected root element <{root.tag}>")


def _count(suite, attribute):
    value = suite.get(attribute, "0")
    try:
        count = int(value)
    except ValueError:
        raise ReportError(f"non-integer {attribute}={value!r}") from None
    if count < 0:
        raise ReportError(f"negative {attribute}={value!r}")
    return count


def _skipped(suite):
    # Surefire writes both forms for the same tests, so take the larger rather than the sum.
    marked = sum(1 for case in suite.findall("testcase") if case.find("skipped") is not None)
    return max(_count(suite, "skipped"), marked)


def executed_tests(reports_dir):
    """Returns (executed, skipped) summed over every TEST-*.xml in reports_dir."""
    if not reports_dir.is_dir():
        raise ReportError(f"no reports directory {reports_dir}")
    executed = skipped = 0
    for report in sorted(reports_dir.glob("TEST-*.xml")):
        try:
            suites = _suites(ET.parse(report).getroot())
        except ET.ParseError as error:
            raise ReportError(f"cannot parse {report.name}: {error}") from None
        except ReportError as error:
            raise ReportError(f"{report.name}: {error}") from None
        for suite in suites:
            try:
                tests, skips = _count(suite, "tests"), _skipped(suite)
            except ReportError as error:
                raise ReportError(f"{report.name}: {error}") from None
            executed += tests - skips
            skipped += skips
    return executed, skipped


def host_platform():
    """The floors that apply to tests run on this machine; every POSIX system counts as Linux."""
    return "windows" if sys.platform == "win32" else "linux"


def check(root, floors=None):
    """Returns the list of failure messages; empty when every module meets its floor."""
    floors = FLOORS if floors is None else floors
    failures = []
    for module, floor in floors.items():
        try:
            executed, skipped = executed_tests(root / MODULE_DIRS[module] / "target" / "surefire-reports")
        except ReportError as error:
            failures.append(f"{module}: {error}")
            continue
        status = "ok" if executed >= floor else "BELOW FLOOR"
        print(f"{module}: executed {executed} (skipped {skipped}), floor {floor} - {status}")
        if executed < floor:
            failures.append(f"{module} executed {executed} tests, expected at least {floor}")
    return failures


def main(argv=None):
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n", 1)[0])
    parser.add_argument("root", nargs="?", default=".", help="repository root (default: .)")
    parser.add_argument("--platform", choices=sorted(PLATFORM_FLOORS), default=host_platform(),
                        help="platform that ran the tests (default: this one)")
    args = parser.parse_args(argv)
    print(f"floors for tests run on {args.platform}")
    failures = check(Path(args.root), PLATFORM_FLOORS[args.platform])
    for failure in failures:
        print(f"::error::{failure}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
