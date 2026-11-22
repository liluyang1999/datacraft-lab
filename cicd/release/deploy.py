"""The release gate of the CI/CD entry (.github/workflows/cicd.yml), before and after the build.

request   runs first on a manual run that asks for a deploy. It fails at once, before any stage
          spends runner time, unless the run is on main, the entered version equals the single
          version source (cicd/release/version.py) and the tag v<version> does not already name
          another commit. It writes version=<version> to GITHUB_OUTPUT.
publish   runs last, after check, test and build passed on the same commit. It checks the branch
          again and that the installer build produced the confirmed version, then publishes.
          The publication target is not decided yet: it only writes the job summary (version,
          artifact, SHA-256, commit) and publishes nothing, tags nothing.

Inputs come from the environment, as the workflow passes them: REF (github.ref), SHA
(github.sha), REQUESTED (the entered version); for publish also PACKAGED, ARTIFACT and SHA256.
Standard library only.

Usage: python3 -B cicd/release/deploy.py {request,publish}
Exit status: 0 when the gate passes, 1 when it refuses.
"""

import argparse
import os
from pathlib import Path
import subprocess
import sys

import version as version_source

ROOT = Path(__file__).resolve().parents[2]
MAIN = "refs/heads/main"


class Refused(Exception):
    """The request or the publication does not meet the release rules."""


def tagged_commit(tag, root=ROOT):
    """The commit a tag names, or None when there is no such tag."""
    completed = subprocess.run(["git", "rev-parse", "-q", "--verify", f"refs/tags/{tag}^{{commit}}"],
                               cwd=root, capture_output=True, text=True, timeout=60)
    return completed.stdout.strip() if completed.returncode == 0 else None


def request(environment, root=ROOT, tag_lookup=tagged_commit):
    ref = environment.get("REF", "")
    if ref != MAIN:
        raise Refused(f"deployments are built from main only (requested: {ref or 'no branch'})")
    actual = version_source.product_version(root)
    requested = environment.get("REQUESTED", "")
    if requested != actual:
        raise Refused(f"requested version '{requested}' differs from the version source '{actual}'")
    tag = f"v{actual}"
    existing = tag_lookup(tag, root)
    if existing and existing != environment.get("SHA", ""):
        raise Refused(f"tag {tag} already names {existing}: raise the version before deploying again")
    return actual


def publish(environment):
    if environment.get("REF", "") != MAIN:
        raise Refused(f"deployments are published from main only (requested: {environment.get('REF')})")
    confirmed = environment.get("REQUESTED", "")
    packaged = environment.get("PACKAGED", "")
    if not confirmed or confirmed != packaged:
        raise Refused(f"the build produced '{packaged}', the request confirmed '{confirmed}'")
    return "\n".join([
        f"### Deploy {confirmed} (publication placeholder)",
        "",
        f"- Installer artifact: `{environment.get('ARTIFACT', '')}` (Setup.exe and Setup.exe.sha256)",
        f"- SHA-256: `{environment.get('SHA256', '')}`",
        f"- Commit: `{environment.get('SHA', '')}`",
        f"- Publication target and tag v{confirmed}: not configured yet; nothing was published.",
        "",
    ])


def append(variable, text):
    path = os.environ.get(variable)
    if path:
        with open(path, "a", encoding="utf-8") as stream:
            stream.write(text)


def main(argv=None):
    parser = argparse.ArgumentParser(description="The CI/CD release gate.")
    parser.add_argument("action", choices=["request", "publish"])
    arguments = parser.parse_args(argv)
    try:
        if arguments.action == "request":
            confirmed = request(os.environ)
            append("GITHUB_OUTPUT", f"version={confirmed}\n")
            print(f"deploy request for {confirmed} accepted")
        else:
            summary = publish(os.environ)
            append("GITHUB_STEP_SUMMARY", summary)
            print(summary)
    except (Refused, version_source.InvalidVersion, OSError, subprocess.TimeoutExpired) as problem:
        print(f"::error::{problem}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
