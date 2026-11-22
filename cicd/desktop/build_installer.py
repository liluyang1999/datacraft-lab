"""Builds 数艺工坊's Windows installer, dist/Setup.exe, from the packaged app image.

Run after cicd/desktop/package_desktop.py has built desktop/target/dist/DatacraftLab (build.bat runs
the whole chain). Steps:

1. Read the product version from the single version source (cicd/release/version.py) and check that
   the app image carries the same version (the jar on its class path).
2. Find Inno Setup 6's ISCC.exe (DATACRAFT_ISCC, else the standard install folders) and the reviewed
   ChineseSimplified.isl (next to the compiler when its SHA-256 matches, else downloaded from the
   pinned Inno Setup source commit and checked).
3. Compile deploy/windows/installer.iss with the version, the image, its icon and the messages.
4. Publish Setup.exe to dist/ by an atomic replace (a failed build keeps the previous installer),
   write Setup.exe.sha256 next to it and print both.

--fixture NAME builds the per-user test variant of the installer (its own identity, folder and
uninstall key, no administrator rights) into desktop/target/installer-fixture/ instead, for the
lifecycle check on a workstation (cicd/desktop/verify_install.py --fixture NAME).

Standard library only; Windows x64 only.

Usage: python -B cicd/desktop/build_installer.py [--root REPOSITORY_ROOT] [--fixture NAME]
Exit status: 0 when the installer was built, 1 on failure, 2 on a missing input.
"""

import argparse
import hashlib
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "release"))
import version as version_source  # noqa: E402  (the single version source, cicd/release)

NAME = "DatacraftLab"
INSTALLER = "deploy/windows/installer.iss"
CHINESE_MESSAGES = "ChineseSimplified.isl"
# The reviewed translation at a fixed Inno Setup source commit (the same file datachain-ark ships).
CHINESE_MESSAGES_URL = ("https://raw.githubusercontent.com/jrsoftware/issrc/"
                        "c2913c993858a781fcb455bac8b8949c1c366cb8/Files/Languages/Unofficial/"
                        "ChineseSimplified.isl")
CHINESE_MESSAGES_SHA256 = "7d544b9bb1d142cfa11f2e5d3cc8abe2e55f8e066c5124e3772675aa236e1278"
FIXTURE = re.compile(r"[a-z0-9]{1,24}")
COMPILE_TIMEOUT = 900


class Failed(Exception):
    """A step failed; the message says which and why."""


class Missing(Exception):
    """An input of the installer is not there."""


def sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def image_version(image):
    """The version of the app jar that the image's launcher configuration starts."""
    cfg = image / "app" / f"{NAME}.cfg"
    if not (image / f"{NAME}.exe").is_file() or not cfg.is_file():
        raise Missing(f"no app image at {image}: run cicd/desktop/package_desktop.py first")
    match = re.search(r"datacraft-desktop-(\d+\.\d+\.\d+)\.jar", cfg.read_text(encoding="utf-8"))
    if match is None:
        raise Failed(f"{cfg} names no versioned datacraft-desktop jar")
    return match.group(1)


def compiler():
    """ISCC.exe: DATACRAFT_ISCC, else Inno Setup 6 in the usual install folders."""
    configured = os.environ.get("DATACRAFT_ISCC", "")
    program_files_x86 = os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")
    program_files = os.environ.get("ProgramFiles", r"C:\Program Files")
    candidates = [Path(configured)] if configured else [
        Path(program_files_x86) / "Inno Setup 6" / "ISCC.exe",
        Path(program_files) / "Inno Setup 6" / "ISCC.exe",
        Path(os.environ.get("LOCALAPPDATA", "")) / "Programs" / "Inno Setup 6" / "ISCC.exe",
    ]
    for candidate in candidates:
        if candidate.is_absolute() and candidate.is_file():
            return candidate.resolve()
    raise Missing("Inno Setup 6 not found: install it or set DATACRAFT_ISCC to its ISCC.exe")


def chinese_messages(iscc, work):
    """The reviewed ChineseSimplified.isl: the compiler's own copy when it matches, else a download."""
    local = iscc.parent / "Languages" / CHINESE_MESSAGES
    if local.is_file() and sha256(local) == CHINESE_MESSAGES_SHA256:
        return local
    target = work / CHINESE_MESSAGES
    if target.is_file() and sha256(target) == CHINESE_MESSAGES_SHA256:
        return target
    partial = target.with_suffix(".download")
    with urllib.request.urlopen(CHINESE_MESSAGES_URL, timeout=120) as response:
        partial.write_bytes(response.read())
    if sha256(partial) != CHINESE_MESSAGES_SHA256:
        partial.unlink()
        raise Failed(f"{CHINESE_MESSAGES} does not match the reviewed SHA-256")
    os.replace(partial, target)
    return target


def publish(source, destination):
    """Copies source over destination atomically; the previous file stays until the copy is whole."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    handle, staging = tempfile.mkstemp(prefix=".setup-", suffix=".tmp", dir=destination.parent)
    os.close(handle)
    try:
        shutil.copyfile(source, staging)
        os.replace(staging, destination)
    finally:
        if os.path.exists(staging):
            os.unlink(staging)


def build(root, fixture=None):
    if sys.platform != "win32" or platform.machine().lower() not in ("amd64", "x86_64"):
        raise Failed("the Windows installer is built on x64 Windows")
    if fixture is not None and not FIXTURE.fullmatch(fixture):
        raise Failed("a fixture name is 1 to 24 lowercase letters and digits")
    version = version_source.product_version(root)
    target = root / "desktop" / "target"
    image = target / "dist" / NAME
    packaged = image_version(image)
    if packaged != version:
        raise Failed(f"the app image is version {packaged}, the version source says {version}: "
                     "package the desktop again")
    icon = target / "package" / f"{NAME}.ico"
    if not icon.is_file():
        raise Missing(f"no {icon}: run cicd/desktop/package_desktop.py first")
    iscc = compiler()
    work = target / ("installer-fixture" if fixture else "installer")
    work.mkdir(parents=True, exist_ok=True)
    messages = chinese_messages(iscc, work)
    output = work / "out"
    if output.exists():
        shutil.rmtree(output)
    output.mkdir()
    command = [str(iscc), "/Qp", f"/DAppVersion={version}", f"/DAppImage={image}",
               f"/DSetupIcon={icon}", f"/DOutDir={output}", f"/DChineseMessages={messages}"]
    if fixture:
        command.append(f"/DFixture={fixture}")
    command.append(str(root / INSTALLER))
    completed = subprocess.run(command, cwd=root, capture_output=True, text=True,
                               encoding="utf-8", errors="replace", timeout=COMPILE_TIMEOUT)
    if completed.returncode != 0:
        raise Failed(f"ISCC exited {completed.returncode}:\n{completed.stdout}\n{completed.stderr}")
    setup = output / "Setup.exe"
    if not setup.is_file():
        raise Failed(f"ISCC reported success but wrote no {setup}")
    destination = work / "Setup.exe" if fixture else root / "dist" / "Setup.exe"
    publish(setup, destination)
    digest = sha256(destination)
    checksum = destination.with_name("Setup.exe.sha256")
    checksum.write_text(f"{digest}  Setup.exe\n", encoding="utf-8", newline="\n")
    print(f"Installer: {destination}")
    print(f"Version: {version}")
    print(f"SHA-256: {digest}")
    return destination


def main(argv=None):
    parser = argparse.ArgumentParser(description="Builds dist/Setup.exe from the packaged app image.")
    parser.add_argument("--root", default=Path(__file__).resolve().parents[2], type=Path)
    parser.add_argument("--fixture", help="build the per-user test installer with this identity")
    arguments = parser.parse_args(argv)
    try:
        build(arguments.root.resolve(), arguments.fixture)
    except Missing as problem:
        print(f"::error::{problem}")
        return 2
    except (Failed, version_source.InvalidVersion, OSError, subprocess.TimeoutExpired) as problem:
        print(f"::error::{problem}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
