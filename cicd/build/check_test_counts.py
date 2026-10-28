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

On a GitHub runner a passing check also records the counts as a notice annotation: annotations can
be read through the public API, so the number of tests a run executed is visible without its log.

--project desktop checks the desktop console (desktop/pom.xml) instead of the reactor's modules.

Usage: python3 -B cicd/build/check_test_counts.py [--platform {linux,windows}]
       [--project {backend,desktop}] [REPOSITORY_ROOT]
Exit status: 0 when every module meets its floor, 1 otherwise.
"""

import argparse
import os
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

# Minimum executed tests per reactor module on Linux, where CI runs. Floors are minimums, not exact
# counts: adding tests needs no change, but deliberately removing tests needs a lower floor in the
# same commit.
FLOORS = {
    "datacraft-api": 29,
    "datacraft-common": 7,
    "datacraft-config": 14,
    "datacraft-engine": 34,
    "datacraft-io": 64,
    "datacraft-jobs": 72,
    "datacraft-cli": 31,
    "datacraft-spark": 59,
}

# The same on Windows, where the build excludes the posix-only tests (symbolic links, `?` in file
# names, Hadoop local writes) and runs datacraft-io's windows-only junction tests instead.
WINDOWS_FLOORS = {**FLOORS, "datacraft-io": 66, "datacraft-jobs": 70, "datacraft-spark": 49}

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

# The desktop console (数艺工坊) is outside the default reactor. Its opt-in CI reactor builds only
# the parent and desktop/pom.xml, then runs this check with --project desktop. Each platform runs one
# of VaultPlatformTest's two platform tests and `verify` never runs the e2e-tagged ones, so the
# floor is the same on both.
DESKTOP_DIRS = {"datacraft-desktop": "desktop"}
DESKTOP_FLOORS = {"datacraft-desktop": 97}

# What --project selects: the module directories, the floors per platform, and the notice's title.
PROJECTS = {
    "backend": (MODULE_DIRS, PLATFORM_FLOORS, "JVM tests"),
    "desktop": (DESKTOP_DIRS, {"linux": DESKTOP_FLOORS, "windows": DESKTOP_FLOORS},
                "Desktop console tests"),
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


def check(root, floors=None, executed_by_module=None, module_dirs=None):
    """Returns the list of failure messages; empty when every module meets its floor.

    executed_by_module, when given, receives the executed count of each module that has reports.
    module_dirs maps each module of floors to its directory (default: the reactor's MODULE_DIRS).
    """
    floors = FLOORS if floors is None else floors
    module_dirs = MODULE_DIRS if module_dirs is None else module_dirs
    failures = []
    for module, floor in floors.items():
        try:
            executed, skipped = executed_tests(root / module_dirs[module] / "target" / "surefire-reports")
        except ReportError as error:
            failures.append(f"{module}: {error}")
            continue
        if executed_by_module is not None:
            executed_by_module[module] = executed
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
    parser.add_argument("--project", choices=sorted(PROJECTS), default="backend",
                        help="the reactor's modules or the desktop console (default: backend)")
    args = parser.parse_args(argv)
    module_dirs, platform_floors, title = PROJECTS[args.project]
    print(f"floors for tests run on {args.platform}" +
          ("" if args.project == "backend" else f" ({args.project})"))
    executed = {}
    failures = check(Path(args.root), platform_floors[args.platform], executed, module_dirs)
    for failure in failures:
        print(f"::error::{failure}")
    if not failures and os.environ.get("GITHUB_ACTIONS"):
        modules = ", ".join(f"{module} {count}" for module, count in sorted(executed.items()))
        print(f"::notice title={title} executed on {args.platform}::"
              f"{sum(executed.values())} in total: {modules}")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
