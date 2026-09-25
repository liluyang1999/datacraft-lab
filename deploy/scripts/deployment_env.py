#!/usr/bin/env python3
"""Create private deployment secrets, or check Compose's resolved configuration."""

import argparse
import base64
import binascii
import json
import os
from pathlib import Path
import re
import secrets
import sys

TEMPLATE = Path(__file__).resolve().parents[1] / "compose" / ".env.example"
SECRET_KEYS = ("POSTGRES_PASSWORD", "AIRFLOW_ADMIN_PASSWORD", "AIRFLOW_API_SECRET_KEY",
               "AIRFLOW_JWT_SECRET", "AIRFLOW_FERNET_KEY")
LOOPBACK = ("127.0.0.1", "::1")


def initialize(path: Path) -> None:
    """Never replace an existing file or symlink, including on a repeated invocation."""
    values = {key: secrets.token_hex(32) for key in SECRET_KEYS}
    values["AIRFLOW_FERNET_KEY"] = base64.urlsafe_b64encode(secrets.token_bytes(32)).decode()
    content = TEMPLATE.read_text(encoding="utf-8")
    for key, value in values.items():
        content, count = re.subn(rf"^{key}=.*$", f"{key}={value}", content, flags=re.MULTILINE)
        if count != 1:
            raise ValueError(f"Template must contain exactly one {key} assignment")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as stream:
            stream.write(content)
    except OSError:
        path.unlink()  # Only the file exclusively created by this invocation.
        raise


def validate(config: dict) -> None:
    """Validate effective values (including shell overrides), without logging any secrets.

    The unauthenticated datacraft-api, when present, may only be published on loopback and may only
    mount persistent storage read-only. The Airflow UI binding stays configurable (AIRFLOW_WEB_BIND).
    """
    services = config.get("services", {})
    if not isinstance(services, dict):
        raise ValueError("Compose services must be an object")

    def environment(name: str) -> dict:
        service = services.get(name, {})
        env = service.get("environment", {}) if isinstance(service, dict) else {}
        if not isinstance(env, dict):
            raise ValueError(f"Missing environment for {name}")
        return env

    postgres = environment("postgres")
    password = postgres.get("POSTGRES_PASSWORD", "")
    # The URI in Compose interpolates credentials; reserved characters need URI encoding.
    if not isinstance(password, str) or not re.fullmatch(r"[A-Za-z0-9_-]{32,}", password):
        raise ValueError("POSTGRES_PASSWORD must contain at least 32 URL-safe letters, digits, _ or -")
    for key in ("POSTGRES_USER", "POSTGRES_DB"):
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", postgres.get(key, "airflow")):
            raise ValueError(f"{key} must be a simple SQL identifier for the connection URI")
    keys = ("AIRFLOW__API__SECRET_KEY", "AIRFLOW__API_AUTH__JWT_SECRET", "AIRFLOW__CORE__FERNET_KEY")
    reference = environment("airflow-scheduler")
    for name in services:
        if not name.startswith("airflow-"):
            continue
        env = environment(name)
        for key in (*keys, "AIRFLOW_ADMIN_PASSWORD"):
            value = env.get(key, "")
            if not isinstance(value, str) or len(value) < 32 or value.lower().startswith(("change-me", "please-change")):
                raise ValueError(f"Set a generated {key} for {name}")
            if key in keys and value != reference.get(key):
                raise ValueError(f"{key} must match across Airflow components")
        try:
            decoded = base64.b64decode(env[keys[2]], altchars=b"-_", validate=True)
        except (ValueError, binascii.Error) as error:
            raise ValueError("AIRFLOW_FERNET_KEY must be a URL-safe base64 Fernet key") from error
        if len(decoded) != 32:
            raise ValueError("AIRFLOW_FERNET_KEY must encode exactly 32 bytes")
        if len({password, env["AIRFLOW_ADMIN_PASSWORD"], *(env[key] for key in keys)}) != 5:
            raise ValueError("Database, admin, API, JWT and Fernet secrets must be independent")
    if "airflow-scheduler" not in services or "airflow-init" not in services:
        raise ValueError("Expected the datacraft Compose scheduler and initializer")

    api = services.get("datacraft-api", {})
    if not isinstance(api, dict):
        raise ValueError("Compose datacraft-api must be an object")
    ports = api.get("ports", [])
    if not isinstance(ports, list) or any(
            not isinstance(port, dict) or port.get("host_ip") not in LOOPBACK for port in ports):
        raise ValueError("The unauthenticated datacraft-api may only be published on loopback")
    mounts = api.get("volumes", [])
    if not isinstance(mounts, list) or any(
            not isinstance(mount, dict) or (mount.get("type") != "tmpfs" and mount.get("read_only") is not True)
            for mount in mounts):
        raise ValueError("The unauthenticated datacraft-api may only mount volumes read-only")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("init", "check"))
    parser.add_argument("path", type=Path, nargs="?", default=TEMPLATE.with_name(".env"))
    args = parser.parse_args()
    try:
        if args.command == "init":
            initialize(args.path)
            print("Created private environment file. Read the admin password there; keep a secure backup.")
        else:
            try:
                config = json.load(sys.stdin)
            except json.JSONDecodeError:
                raise ValueError("Expected JSON from docker compose config --format json") from None
            if not isinstance(config, dict):
                raise ValueError("Expected a Compose configuration object")
            validate(config)
            print("Deployment secrets validated.")
    except (OSError, ValueError) as error:
        if isinstance(error, OSError):
            print("Cannot create environment file; check permissions and keep existing files unchanged.", file=sys.stderr)
        else:
            print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
