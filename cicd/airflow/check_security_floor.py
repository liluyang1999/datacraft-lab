"""Fail when an installed package is older than the security floor.

Airflow 3.3.2 and the FAB provider 3.9.0 fix published session, logout and token CVEs, so the DAG
tests must never run against an older resolution than the datacraft/airflow image ships. Each
fixed release in orchestration/airflow/security-overrides.txt, installed over the Airflow
constraints, is a floor as well: this is where an override that did not take effect shows.

Usage: python -B cicd/airflow/check_security_floor.py (in the environment that runs the DAG tests)
Exit status: 0 when every package meets its floor, 1 otherwise.
"""

from importlib.metadata import version
from pathlib import Path
import re
import sys

from packaging.version import Version

FLOORS = {"apache-airflow": "3.3.2", "apache-airflow-providers-fab": "3.9.0"}
OVERRIDES = Path(__file__).resolve().parents[2] / "orchestration" / "airflow" / "security-overrides.txt"
PIN = re.compile(r"^([A-Za-z0-9][A-Za-z0-9._-]*)==([0-9][^\s#]*)\s*(?:#.*)?$")


def override_floors(path=OVERRIDES):
    """{name: release} of the overrides file; a line that is not name==release is an error."""
    found = {}
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        text = line.strip()
        if not text or text.startswith("#"):
            continue
        match = PIN.match(text)
        if not match:
            raise ValueError(f"{path.name}:{number}: expected name==release, got {text!r}")
        found[match.group(1)] = match.group(2)
    if not found:
        raise ValueError(f"{path.name}: no pinned release")
    return found


def floors():
    return {**FLOORS, **override_floors()}


def main():
    status = 0
    for name, floor in floors().items():
        installed = version(name)
        print(f"{name} {installed} (floor {floor})")
        if Version(installed) < Version(floor):
            print(f"::error::{name} {installed} is below the security floor {floor}")
            status = 1
    return status


if __name__ == "__main__":
    sys.exit(main())
