"""build.bat's work: builds 数艺工坊's Windows installer from a clean checkout.

1. Maven builds the desktop console from scratch with the parent POM (./mvnw -Pdesktop -pl
   :datacraft-desktop -am clean package; tests skipped: CI's test stage and the developer's verify
   run them). Clean, so no output of an earlier version can end up in the installer.
2. cicd/desktop/package_desktop.py builds the app image with its own runtime and AOT cache and
   checks twenty starts against the start-up budget.
3. cicd/desktop/build_installer.py compiles deploy/windows/installer.iss into dist/Setup.exe and
   prints its path, version and SHA-256.

Only one build runs per checkout at a time: a second one stops at once instead of overwriting the
first one's outputs. The version is the single version source (cicd/release/version.py); nothing
here writes it again. Standard library only; Windows x64 only.

`outputs` is CI's last step of the installer job: it reads the version source and
dist/Setup.exe.sha256 and writes version, artifact (ARTIFACT_PREFIX-DatacraftLab-<version>-windows-x64
-run<number>-attempt<attempt>) and sha256 to GITHUB_OUTPUT, plus a line to the job summary.

Usage: python -B cicd/desktop/release.py {build [--startup-budget-ms N] | outputs}
Exit status: 0 when dist/Setup.exe was built (or its outputs written), otherwise the failed step's
status.
"""

import argparse
import os
from pathlib import Path
import platform
import re
import subprocess
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "release"))
import version as version_source  # noqa: E402  (the single version source, cicd/release)

ROOT = Path(__file__).resolve().parents[2]
# Outside desktop/target, which the clean build deletes; dist/ is ignored by git.
LOCK = ROOT / "dist" / ".build.lock"


def run(command):
    print("> " + " ".join(str(part) for part in command), flush=True)
    return subprocess.run([str(part) for part in command], cwd=ROOT).returncode


def hold_lock():
    """An exclusive lock on a file in the build folder, released by the OS when this process ends."""
    assert sys.platform == "win32"
    import msvcrt

    LOCK.parent.mkdir(parents=True, exist_ok=True)
    handle = open(LOCK, "a+b")  # noqa: SIM115  (held for the whole build)
    try:
        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
    except OSError:
        handle.close()
        return None
    return handle


def build(startup_budget_ms):
    if sys.platform != "win32" or platform.machine().lower() not in ("amd64", "x86_64"):
        print("::error::build.bat builds the x64 Windows installer on x64 Windows")
        return 1
    lock = hold_lock()
    if lock is None:
        print("::error::another build of this checkout is running; wait for it to finish")
        return 1
    try:
        steps = [
            [ROOT / "mvnw.cmd", "-B", "-ntp", "-Pdesktop", "-pl", ":datacraft-desktop", "-am",
             "clean", "package", "-DskipTests"],
            [sys.executable, "-B", ROOT / "cicd/desktop/package_desktop.py", "--root", ROOT,
             "--startup-budget-ms", startup_budget_ms],
            [sys.executable, "-B", ROOT / "cicd/desktop/build_installer.py", "--root", ROOT],
        ]
        for step in steps:
            status = run(step)
            if status != 0:
                print(f"::error::the build stopped: {Path(str(step[0])).name} exited {status}")
                return status
        return 0
    finally:
        lock.close()


def outputs(environment, root=ROOT):
    """CI's outputs of a verified installer: version, artifact name and SHA-256."""
    version = version_source.product_version(root)
    line = (root / "dist" / "Setup.exe.sha256").read_text(encoding="utf-8").strip()
    match = re.fullmatch(r"([0-9a-f]{64})  Setup\.exe", line)
    if match is None:
        print("::error::dist/Setup.exe.sha256 holds no SHA-256 of Setup.exe")
        return 1
    artifact = (f"{environment.get('ARTIFACT_PREFIX', 'build')}-DatacraftLab-{version}-windows-x64"
                f"-run{environment.get('GITHUB_RUN_NUMBER', '0')}"
                f"-attempt{environment.get('GITHUB_RUN_ATTEMPT', '0')}")
    values = {"version": version, "artifact": artifact, "sha256": match.group(1)}
    lines = [f"{key}={value}" for key, value in values.items()]
    summary = f"### Setup.exe {version}\n\nSHA-256 `{match.group(1)}`\n"
    for variable, text in (("GITHUB_OUTPUT", "\n".join(lines) + "\n"),
                           ("GITHUB_STEP_SUMMARY", summary)):
        path = environment.get(variable)
        if path:
            with open(path, "a", encoding="utf-8") as stream:
                stream.write(text)
    print("\n".join(lines))
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description="Builds dist/Setup.exe (build.bat).")
    parser.add_argument("action", choices=["build", "outputs"])
    parser.add_argument("--startup-budget-ms", type=int, default=1500,
                        help="the packaged app's start-up budget (0 measures without one)")
    arguments = parser.parse_args(argv)
    os.environ.setdefault("PYTHONUTF8", "1")
    if arguments.action == "outputs":
        return outputs(os.environ)
    return build(arguments.startup_budget_ms)


if __name__ == "__main__":
    sys.exit(main())
