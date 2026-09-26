#!/usr/bin/env bash
# Installs Airflow, the providers in orchestration/airflow/requirements.txt and pyspark into the
# current Python environment, pinned the way deploy/docker/Dockerfile.airflow pins the image:
# apache-airflow==3.3.2 with the official constraints file for this interpreter's Python version,
# then pyspark==4.2.0 to match the JVM Spark version. PYTHON selects the interpreter (default:
# python). Outside a CI runner, run it inside a virtual environment.
set -euo pipefail
# shellcheck source=cicd/lib.sh
. "$(dirname "$0")/../lib.sh"
cd "$REPO_ROOT"

python=${PYTHON:-python}
python_minor=$("$python" -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')
constraints="https://raw.githubusercontent.com/apache/airflow/constraints-3.3.2/constraints-${python_minor}.txt"

echo "Python $python_minor, constraints $constraints"
"$python" -m pip install 'apache-airflow==3.3.2' -r orchestration/airflow/requirements.txt \
  --constraint "$constraints"
"$python" -m pip check
"$python" -m pip install 'apache-airflow==3.3.2' 'pyspark==4.2.0'
"$python" -m pip check
