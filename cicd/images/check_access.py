"""Checks that a deployed stack answers its own credentials, and only those.

  --airflow URL  the Airflow API server: the admin user gets an access token for its password
                 (HTTP 201) and none for another password (401)
  --api URL      the engine API: /health answers without a token (200); /jobs is refused without
                 the token and with another token (401), and listed with the token (200)

The credentials are AIRFLOW_ADMIN_USERNAME, AIRFLOW_ADMIN_PASSWORD and DATACRAFT_API_TOKEN. They come
from --env-file (KEY=value lines, as deployment_env.py writes them) or, without it, from the
environment, which is how the check runs inside a container that received the stack's .env.
Nothing this script prints holds a credential.

Usage: python3 -B cicd/images/check_access.py [--env-file FILE] [--airflow URL] [--api URL]
Exit status: 0 when every check holds; 1 with one line per check that does not; 2 when nothing was
asked for, the env file cannot be read or a credential the checks need is missing.
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

TIMEOUT_SECONDS = 30
# The stack is addressed directly: a proxy from the environment has no part in the check.
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def credentials(path):
    """The KEY=value pairs of an env file, or the environment without one."""
    if path is None:
        return dict(os.environ)
    with open(path, encoding="utf-8") as source:
        return dict(line.split("=", 1) for line in source.read().splitlines()
                    if "=" in line and not line.lstrip().startswith("#"))


def answer(url, token=None, body=None):
    """The HTTP status of one request, or the kind of error when there was no HTTP answer."""
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method="GET" if data is None else "POST")
    try:
        with OPENER.open(request, timeout=TIMEOUT_SECONDS) as response:
            return response.status
    except urllib.error.HTTPError as error:
        return error.code
    except (OSError, ValueError) as error:
        # The type only: a message could repeat a URL, which must not hold a credential but may.
        return f"no answer ({type(error).__name__})"


def airflow_failures(url, values):
    """What is wrong with the sign-in at url; nothing when only the admin password gets a token."""
    name, password = values["AIRFLOW_ADMIN_USERNAME"], values["AIRFLOW_ADMIN_PASSWORD"]
    failures = []
    for attempt, secret, expected in (("the admin password", password, 201),
                                      ("another password", password + "-wrong", 401)):
        found = answer(f"{url}/auth/token", body={"username": name, "password": secret})
        if found != expected:
            failures.append(f"Airflow at {url}: signing in with {attempt} got {found}, expected {expected}")
    return failures


def api_failures(url, values):
    """What is wrong with the engine API at url; nothing when only its token opens /jobs."""
    token = values["DATACRAFT_API_TOKEN"]
    failures = []
    for request, path, presented, expected in (("/health without a token", "/health", None, 200),
                                               ("/jobs without a token", "/jobs", None, 401),
                                               ("/jobs with another token", "/jobs", token + "0", 401),
                                               ("/jobs with the token", "/jobs", token, 200)):
        found = answer(url + path, token=presented)
        if found != expected:
            failures.append(f"engine API at {url}: {request} got {found}, expected {expected}")
    return failures


def main(argv=None):
    parser = argparse.ArgumentParser(description="Checks that a deployed stack answers its own credentials only.")
    parser.add_argument("--env-file", metavar="FILE", help="read the credentials from FILE, not the environment")
    parser.add_argument("--airflow", metavar="URL", help="base URL of the Airflow API server")
    parser.add_argument("--api", metavar="URL", help="base URL of the engine API")
    args = parser.parse_args(argv)
    checks = [(args.airflow, airflow_failures, ("AIRFLOW_ADMIN_USERNAME", "AIRFLOW_ADMIN_PASSWORD")),
              (args.api, api_failures, ("DATACRAFT_API_TOKEN",))]
    checks = [(url.rstrip("/"), check, keys) for url, check, keys in checks if url]
    if not checks:
        parser.error("name at least one of --airflow and --api")
    try:
        values = credentials(args.env_file)
    except (OSError, ValueError) as error:
        print(f"cannot read the env file: {type(error).__name__}", file=sys.stderr)
        return 2
    missing = sorted(key for _, _, keys in checks for key in keys if not values.get(key))
    if missing:
        print(f"no value for {', '.join(missing)}", file=sys.stderr)
        return 2
    failures = [failure for url, check, _ in checks for failure in check(url, values)]
    for failure in failures:
        print(failure, file=sys.stderr)
    if failures:
        return 1
    if args.airflow:
        print(f"Airflow at {args.airflow.rstrip('/')} gives a token for the admin password only")
    if args.api:
        print(f"the engine API at {args.api.rstrip('/')} lists its jobs for its token only")
    return 0


if __name__ == "__main__":
    sys.exit(main())
