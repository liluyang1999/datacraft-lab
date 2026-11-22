"""Walks 数艺工坊's Windows installer through its whole lifecycle and checks every step.

Machine mode (the default; CI's elevated Windows runner) uses dist/Setup.exe from build.bat.
Fixture mode (--fixture NAME) uses the per-user installer that build_installer.py --fixture NAME
builds, with its own identity, folder and uninstall key, so a workstation can run the same journey
without administrator rights and without touching a real installation.

1.  Nothing of the product is installed: no uninstall key, no default folder.
2.  A silent install into the default folder exits 0 and leaves the launcher, the app jar, the
    runtime, one Start menu shortcut and one uninstall key whose name, publisher, version (the
    single version source) and location are right.
3.  The installed app starts (--smoke) with a temporary data folder and reaches its first frame.
4.  Machine mode: while the installed app runs, Setup refuses and the program stays as it was.
5.  Running the same Setup again repairs the installation in place.
6.  With a higher version registered (the key's DisplayVersion raised, then restored), Setup refuses
    to downgrade and changes nothing.
7.  The uninstaller removes the folder, the shortcut and the key, and leaves the data folder the app
    used (settings and caches in the profile survive an uninstall).
8.  A silent install into a chosen folder (/DIR) registers that folder, and 9. uninstalls cleanly.

Every data folder is a new temporary directory, never the user's ~/.datacraft. A JSON report of the
steps is written to desktop/target/installer/lifecycle.json (or --report). Standard library only.

Usage: python -B cicd/desktop/verify_install.py [--installer SETUP] [--fixture NAME] [--report FILE]
Exit status: 0 when every step passed, 1 on the first failed step.
"""

import argparse
import ctypes
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "release"))
import version as version_source  # noqa: E402  (the single version source, cicd/release)

PRODUCT = "数艺工坊"
PROGRAM = "DatacraftLab.exe"
MUTEX = "com.datacraft.lab"
QUIET = ["/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/SP-"]
SETUP_TIMEOUT = 600
REMOVAL_TIMEOUT = 180
SMOKE_TIMEOUT = 180
SYNCHRONIZE = 0x00100000
UNINSTALL_ROOT = r"Software\Microsoft\Windows\CurrentVersion\Uninstall"


class Failed(Exception):
    """A lifecycle step did not hold; the message names it."""


class Layout:
    """Where one installer variant puts things."""

    def __init__(self, fixture=None):
        self.fixture = fixture
        if fixture:
            self.identity = f"com.datacraft.lab.fixture.{fixture}"
            self.machine = False
            self.default_dir = (Path(os.environ.get("LOCALAPPDATA", "")) / "Temp"
                                / f"datacraft-install-{fixture}" / "DatacraftLab")
            programs = Path(os.environ.get("APPDATA", "")) / "Microsoft/Windows/Start Menu/Programs"
            self.shortcut = programs / f"{PRODUCT} {fixture}.lnk"
        else:
            self.identity = "com.datacraft.lab"
            self.machine = True
            self.default_dir = Path(os.environ.get("ProgramFiles", r"C:\Program Files")) / "DatacraftLab"
            programs = (Path(os.environ.get("ProgramData", r"C:\ProgramData"))
                        / "Microsoft/Windows/Start Menu/Programs")
            self.shortcut = programs / f"{PRODUCT}.lnk"
        self.key = f"{UNINSTALL_ROOT}\\{self.identity}_is1"


def registry(layout):
    """The uninstall key's values, or None when the key does not exist."""
    assert sys.platform == "win32"
    import winreg

    hive = winreg.HKEY_LOCAL_MACHINE if layout.machine else winreg.HKEY_CURRENT_USER
    try:
        with winreg.OpenKey(hive, layout.key, 0,
                            winreg.KEY_READ | winreg.KEY_WOW64_64KEY) as key:
            values = {}
            index = 0
            while True:
                try:
                    name, value, _kind = winreg.EnumValue(key, index)
                except OSError:
                    return values
                values[name] = value
                index += 1
    except FileNotFoundError:
        return None


def set_registered_version(layout, value):
    assert sys.platform == "win32"
    import winreg

    hive = winreg.HKEY_LOCAL_MACHINE if layout.machine else winreg.HKEY_CURRENT_USER
    with winreg.OpenKey(hive, layout.key, 0,
                        winreg.KEY_SET_VALUE | winreg.KEY_WOW64_64KEY) as key:
        winreg.SetValueEx(key, "DisplayVersion", 0, winreg.REG_SZ, value)


def same_path(left, right):
    return os.path.normcase(os.path.normpath(str(left))) == os.path.normcase(os.path.normpath(str(right)))


def run_setup(setup, log, extra=()):
    completed = subprocess.run([str(setup), *QUIET, f"/LOG={log}", *extra],
                               capture_output=True, timeout=SETUP_TIMEOUT)
    return completed.returncode


def setup_log(log):
    try:
        return Path(log).read_text(encoding="utf-8", errors="replace")[-4000:]
    except OSError:
        return "(no setup log)"


def install(setup, work, step, extra=()):
    log = work / f"{step}.log"
    code = run_setup(setup, log, extra)
    if code != 0:
        raise Failed(f"{step}: Setup exited {code}\n{setup_log(log)}")


def refused(setup, work, step, extra=()):
    log = work / f"{step}.log"
    code = run_setup(setup, log, extra)
    if code == 0:
        raise Failed(f"{step}: Setup should have refused but exited 0\n{setup_log(log)}")
    return code


def check_installed(layout, directory, version):
    values = registry(layout)
    if values is None:
        raise Failed(f"no uninstall key {layout.key}")
    expected = {"DisplayName": PRODUCT, "DisplayVersion": version, "Publisher": PRODUCT}
    for name, value in expected.items():
        if values.get(name) != value:
            raise Failed(f"uninstall key {name} is {values.get(name)!r}, expected {value!r}")
    if not same_path(values.get("InstallLocation", ""), directory):
        raise Failed(f"InstallLocation is {values.get('InstallLocation')!r}, expected {directory}")
    program = directory / PROGRAM
    jar = directory / "app" / f"datacraft-desktop-{version}.jar"
    runtime = directory / "runtime"
    for path in (program, jar, runtime, directory / "unins000.exe"):
        if not path.exists():
            raise Failed(f"the installation lacks {path}")
    if not layout.shortcut.is_file():
        raise Failed(f"no Start menu shortcut {layout.shortcut}")
    return program


def check_removed(layout, directory, step):
    deadline = time.monotonic() + REMOVAL_TIMEOUT
    # The uninstaller hands over to a copy of itself in TEMP and returns at once: wait for it.
    while time.monotonic() < deadline:
        if registry(layout) is None and not directory.exists() and not layout.shortcut.exists():
            return
        time.sleep(1)
    raise Failed(f"{step}: after {REMOVAL_TIMEOUT} s the key exists={registry(layout) is not None}, "
                 f"folder exists={directory.exists()}, shortcut exists={layout.shortcut.exists()}")


def uninstall(layout, directory, step):
    uninstaller = directory / "unins000.exe"
    completed = subprocess.run([str(uninstaller), "/VERYSILENT", "/SUPPRESSMSGBOXES", "/NORESTART"],
                               capture_output=True, timeout=SETUP_TIMEOUT)
    if completed.returncode != 0:
        raise Failed(f"{step}: the uninstaller exited {completed.returncode}")
    check_removed(layout, directory, step)


def app_environment(home):
    environment = dict(os.environ)
    environment["DATACRAFT_DESKTOP_HOME"] = str(home)
    return environment


def smoke(program, home):
    completed = subprocess.run([str(program), "--smoke"], env=app_environment(home),
                               capture_output=True, timeout=SMOKE_TIMEOUT)
    log = home / "logs" / "desktop-0.log"
    text = log.read_text(encoding="utf-8", errors="replace") if log.is_file() else ""
    if completed.returncode != 0 or "first frame" not in text:
        raise Failed(f"the installed app did not start cleanly (exit {completed.returncode}):\n"
                     f"{text[-3000:]}")


def mutex_held():
    assert sys.platform == "win32"
    handle = ctypes.windll.kernel32.OpenMutexW(SYNCHRONIZE, False, MUTEX)
    if handle:
        ctypes.windll.kernel32.CloseHandle(handle)
        return True
    return False


def refuses_while_running(setup, program, home, work):
    if mutex_held():
        raise Failed(f"another process already holds {MUTEX}: close 数艺工坊 before this check")
    before = program.stat().st_mtime_ns
    app = subprocess.Popen([str(program)], env=app_environment(home),
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        deadline = time.monotonic() + SMOKE_TIMEOUT
        while not mutex_held():
            if app.poll() is not None:
                raise Failed(f"the installed app exited {app.returncode} before holding {MUTEX}")
            if time.monotonic() > deadline:
                raise Failed(f"the installed app did not take {MUTEX} within {SMOKE_TIMEOUT} s")
            time.sleep(0.5)
        code = refused(setup, work, "4-running")
        if program.stat().st_mtime_ns != before:
            raise Failed("Setup refused but replaced the program of the running app")
        return code
    finally:
        app.terminate()  # this check started the app, so it ends it
        app.wait(timeout=60)


def verify(setup, fixture=None, report=None, root=None):
    if sys.platform != "win32":
        raise Failed("the installer lifecycle runs on Windows")
    root = Path(root or Path(__file__).resolve().parents[2])
    is_admin = ctypes.windll.shell32.IsUserAnAdmin()
    layout = Layout(fixture)
    if fixture is None and not is_admin:
        raise Failed("the machine installation needs an elevated process; use --fixture locally")
    version = version_source.product_version(root)
    setup = Path(setup).resolve()
    if not setup.is_file():
        raise Failed(f"no installer at {setup}")
    steps = []
    work = Path(tempfile.mkdtemp(prefix="datacraft-install-"))
    homes = Path(tempfile.mkdtemp(prefix="datacraft-install-home-"))
    custom = layout.default_dir.parent / f"DatacraftLab-test-{uuid.uuid4().hex[:12]}"
    try:
        if registry(layout) is not None or layout.default_dir.exists():
            raise Failed(f"{PRODUCT} is already installed ({layout.key} or {layout.default_dir}): "
                         "this check needs a clean machine")
        steps.append("1 clean before install")

        install(setup, work, "2-install")
        program = check_installed(layout, layout.default_dir, version)
        steps.append(f"2 installed {version} into {layout.default_dir}")

        home = homes / "smoke"
        smoke(program, home)
        steps.append("3 the installed app reached its first frame")

        if fixture is None:
            code = refuses_while_running(setup, program, homes / "running", work)
            steps.append(f"4 Setup refused while the app ran (exit {code})")

        install(setup, work, "5-repair")
        check_installed(layout, layout.default_dir, version)
        steps.append("5 repaired in place")

        set_registered_version(layout, "65535.0.0")
        try:
            before = program.stat().st_mtime_ns
            code = refused(setup, work, "6-downgrade")
            if program.stat().st_mtime_ns != before:
                raise Failed("Setup refused the downgrade but replaced the program")
        finally:
            set_registered_version(layout, version)
        steps.append(f"6 Setup refused to downgrade (exit {code})")

        uninstall(layout, layout.default_dir, "7-uninstall")
        if not (home / "datacraft.db").is_file():
            raise Failed("the uninstall removed the app's data folder")
        steps.append("7 uninstalled; the data folder is kept")

        install(setup, work, "8-install-chosen", [f"/DIR={custom}"])
        check_installed(layout, custom, version)
        steps.append(f"8 installed into the chosen folder {custom}")
        uninstall(layout, custom, "9-uninstall-chosen")
        steps.append("9 uninstalled from the chosen folder")
    except Failed:
        print("\n".join(f"passed: {step}" for step in steps))
        raise
    finally:
        shutil.rmtree(homes, ignore_errors=True)
        shutil.rmtree(work, ignore_errors=True)
        if fixture:
            # The fixture's own folders only, and only once nothing is registered there.
            if registry(layout) is None:
                shutil.rmtree(layout.default_dir.parent, ignore_errors=True)
    result = {"installer": str(setup), "version": version, "fixture": fixture, "steps": steps}
    report = Path(report or root / "desktop" / "target" / "installer" / "lifecycle.json")
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("\n".join(f"passed: {step}" for step in steps))
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description="Checks the installer's whole lifecycle.")
    parser.add_argument("--installer", default="dist/Setup.exe")
    parser.add_argument("--fixture", help="the per-user test installer of this identity")
    parser.add_argument("--report", help="where the JSON report goes")
    arguments = parser.parse_args(argv)
    setup = arguments.installer
    if arguments.fixture and arguments.installer == "dist/Setup.exe":
        setup = (Path(__file__).resolve().parents[2] / "desktop" / "target" / "installer-fixture"
                 / "Setup.exe")
    try:
        verify(setup, arguments.fixture, arguments.report)
    except (Failed, version_source.InvalidVersion, OSError, subprocess.TimeoutExpired) as problem:
        print(f"::error::{problem}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
