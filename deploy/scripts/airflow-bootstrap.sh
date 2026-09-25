#!/usr/bin/env bash
# Runs inside the Airflow image (or any Linux host with Airflow and util-linux's setsid).
# Any failed migration or user lookup is a failed initialization.
set -euo pipefail

: "${AIRFLOW_ADMIN_USERNAME:?Set AIRFLOW_ADMIN_USERNAME}"
: "${AIRFLOW_ADMIN_PASSWORD:?Set AIRFLOW_ADMIN_PASSWORD}"
: "${AIRFLOW_ADMIN_EMAIL:?Set AIRFLOW_ADMIN_EMAIL}"

airflow db migrate
airflow fab-db migrate

users_file=$(mktemp)
trap 'rm -f -- "$users_file"' EXIT
# `users export` writes the JSON to the file itself: Airflow 3.3.2 can print import-time warnings on
# stdout (e.g. the starlette/httpx2 deprecation), which would corrupt `users list --output json`.
airflow users export "$users_file"
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
  # Without --password, the FAB `users create` prompts twice through getpass, so the password never
  # appears in argv (/proc/<pid>/cmdline, `ps`); printf is a builtin and starts no process. getpass
  # prefers a controlling terminal (compose run, exec -it) over stdin: setsid detaches from it, so
  # both prompts read the pipe. Its "input may be echoed" warning is harmless: nothing echoes a pipe.
  # pipefail keeps a failed creation's own exit status.
  printf '%s\n%s\n' "$AIRFLOW_ADMIN_PASSWORD" "$AIRFLOW_ADMIN_PASSWORD" |
    setsid -w airflow users create \
      --username="$AIRFLOW_ADMIN_USERNAME" \
      --firstname Data --lastname Craft --role Admin \
      --email="$AIRFLOW_ADMIN_EMAIL"
fi
