"""Unpacks the pinned Inno Setup compiler into a new folder, for CI's Windows installer build.

The installer must not depend on whichever Inno Setup a runner image happens to carry. This
downloads Inno Setup 6.7.4 from its GitHub release, checks the archive's SHA-256 against the
reviewed value, extracts it in portable mode for the current user into DIRECTORY, and prints the
path of ISCC.exe. Under GitHub Actions it also appends DATACRAFT_ISCC=<that path> to GITHUB_ENV,
where cicd/desktop/build_installer.py looks first. DIRECTORY must not exist yet.

Standard library only; Windows only.

Usage: python -B cicd/desktop/prepare_inno.py DIRECTORY [--archive INSTALLER]
Exit status: 0 with the compiler path on stdout, 1 on failure.
"""

import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import urllib.request

VERSION = "6.7.4"
URL = f"https://github.com/jrsoftware/issrc/releases/download/is-6_7_4/innosetup-{VERSION}.exe"
SHA256 = "a549929319f9120716677c685535ca4671e4b937b5fdc87e2585f32d8eb30e8c"
EXTRACT_TIMEOUT = 180


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def prepare(directory, archive=None):
    directory = Path(directory).resolve()
    if directory.exists():
        raise RuntimeError(f"{directory} already exists; the compiler goes into a new folder")
    directory.mkdir(parents=True)
    if archive is None:
        archive = directory / f"innosetup-{VERSION}.exe"
        with urllib.request.urlopen(URL, timeout=120) as response:
            archive.write_bytes(response.read())
    archive = Path(archive).resolve()
    if sha256(archive) != SHA256:
        raise RuntimeError(f"{archive.name} does not match the reviewed Inno Setup {VERSION}")
    tools = directory / "compiler"
    completed = subprocess.run(
        [str(archive), "/CURRENTUSER", "/PORTABLE=1", "/VERYSILENT", "/SUPPRESSMSGBOXES",
         "/NORESTART", "/SP-", f"/DIR={tools}"],
        capture_output=True, text=True, timeout=EXTRACT_TIMEOUT)
    if completed.returncode != 0:
        raise RuntimeError(f"the Inno Setup extraction exited {completed.returncode}")
    iscc = tools / "ISCC.exe"
    if not iscc.is_file():
        raise RuntimeError(f"no ISCC.exe in {tools}")
    if os.environ.get("GITHUB_ACTIONS") == "true" and os.environ.get("GITHUB_ENV"):
        with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as env:
            env.write(f"DATACRAFT_ISCC={iscc}\n")
    return iscc


def main(argv=None):
    parser = argparse.ArgumentParser(description="Unpacks the pinned Inno Setup compiler.")
    parser.add_argument("directory")
    parser.add_argument("--archive", help="a local copy of the release installer")
    arguments = parser.parse_args(argv)
    try:
        print(prepare(arguments.directory, arguments.archive))
    except (RuntimeError, OSError, subprocess.TimeoutExpired) as problem:
        print(f"::error::{problem}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
