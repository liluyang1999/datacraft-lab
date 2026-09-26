"""Fail when the installed Airflow or FAB provider is older than the security floor.

Airflow 3.3.2 and the FAB provider 3.9.0 fix published session, logout and token CVEs, so the DAG
tests must never run against an older resolution than the datacraft/airflow image ships.

Usage: python -B cicd/airflow/check_security_floor.py (in the environment that runs the DAG tests)
Exit status: 0 when every package meets its floor, 1 otherwise.
"""

from importlib.metadata import version
import sys

from packaging.version import Version

FLOORS = {"apache-airflow": "3.3.2", "apache-airflow-providers-fab": "3.9.0"}


def main():
    status = 0
    for name, floor in FLOORS.items():
        installed = version(name)
        print(f"{name} {installed} (floor {floor})")
        if Version(installed) < Version(floor):
            print(f"::error::{name} {installed} is below the security floor {floor}")
            status = 1
    return status


if __name__ == "__main__":
    sys.exit(main())
