#!/usr/bin/env bash
# Runs inside the Airflow image. Any failed migration or user lookup is a failed initialization.
set -euo pipefail

: "${AIRFLOW_ADMIN_USERNAME:?Set AIRFLOW_ADMIN_USERNAME}"
: "${AIRFLOW_ADMIN_PASSWORD:?Set AIRFLOW_ADMIN_PASSWORD}"
: "${AIRFLOW_ADMIN_EMAIL:?Set AIRFLOW_ADMIN_EMAIL}"

airflow db migrate
airflow fab-db migrate

users_file=$(mktemp)
trap 'rm -f -- "$users_file"' EXIT
airflow users list --output json > "$users_file"
# Distinguish a known existing account from a broken lookup or failed creation.
exists=$(python3 - "$users_file" "$AIRFLOW_ADMIN_USERNAME" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as source:
    users = json.load(source)
print("yes" if any(user["username"] == sys.argv[2] for user in users) else "no")
PY
)
if [[ "$exists" == yes ]]; then
  printf 'Airflow admin account already exists; password unchanged.\n'
else
  airflow users create \
    --username="$AIRFLOW_ADMIN_USERNAME" \
    --password="$AIRFLOW_ADMIN_PASSWORD" \
    --firstname Data --lastname Craft --role Admin \
    --email="$AIRFLOW_ADMIN_EMAIL"
fi
