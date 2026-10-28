"""Packages 数艺工坊 as a self-contained app image with its own Java runtime and an AOT cache.

Run it after `./mvnw -f desktop/pom.xml package`, with the JDK the app was built with (25.0.5 or
newer) on PATH or in JAVA_HOME:

1. jdeps lists the JDK modules the app and its libraries use; jlink builds a trimmed runtime
   from them plus the JavaFX modules of this platform (desktop/target/javafx).
2. jpackage builds the app image `desktop/target/dist/DataCraft` around that runtime, with the
   app jar and its libraries on the class path and the icon drawn here (no image file is kept).
3. A disposable `--smoke` training run visits every view in both themes while the JVM records
   an AOT cache (JEP 483/514). The launcher is then told to use the cache.
4. Ten fresh-profile and ten reused-profile starts must confirm AOT use, exit without runtime
   diagnostics, and each reach the first completed layout pulse within 1500 ms. All timing
   samples are saved in `desktop/target/package/startup.json`, including budget failures.

All starts use a temporary data directory (DATACRAFT_DESKTOP_HOME), never the user's
~/.datacraft. The app opens a window, so a display is needed: Xvfb on a Linux runner.
Standard library only.

Usage: python3 -B cicd/desktop/package_desktop.py [--skip-aot] [--root REPOSITORY_ROOT]
Exit status: 0 when the image passes the selected checks, 1 on failure, 2 on missing input.
"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import sqlite3
import stat
import struct
import subprocess
import sys
import tempfile
import statistics
import zipfile
import zlib

NAME = "DataCraft"
MAIN_CLASS = "com.example.datacraft.desktop.Launcher"
# The console holds bounded lists and logs, not datasets. Do not size its initial heap from the
# host's RAM: on a developer workstation that can commit hundreds of unused megabytes at startup.
# This bounded UI client favors startup latency over C2 throughput; the execution engine is a
# different process. The desktop Maven tests exercise the same compiler policy and UI budgets.
JVM_OPTIONS = ["-Xms32m", "-Xmx256m", "-XX:TieredStopAtLevel=1",
               "--enable-native-access=javafx.graphics,ALL-UNNAMED"]
JAVAFX_MODULES = ("javafx.base", "javafx.graphics", "javafx.controls")
AOT_FILE = "datacraft.aot"
STARTUP_LINE = re.compile(r"Start-up: first frame at (\d+) ms")
BAD_DIAGNOSTIC = re.compile(r"Exception in thread|\b(?:WARNING|SEVERE|ERROR)[: ]|\[warning\]|"
                            r"(?:^|\n)\s*(?:[\w$]+\.)+[\w$]*(?:Exception|Error)(?::|\n|$)")
# HotSpot deliberately excludes JFR event classes and generated JDK proxies from AOT archives.
# Accept only these complete training diagnostics; retain them in output, and reject every
# warning (including these) during normal cached starts. See systemDictionaryShared.cpp in JDK 25.
AOT_EXCLUSION = re.compile(
    r"^\[\d+\.\d+s\]\[warning\]\[aot\] Skipping "
    r"(?:jdk/internal/event/[\w$]+: JFR event class|jdk/proxy\d+/\$Proxy\d+: Unsupported location)$")
PATCH_MARKER = "META-INF/datacraft-javafx-patch.properties"


class Failed(Exception):
    """A step failed; the message says which and why."""


class Missing(Exception):
    """An input of the packaging is not there."""


def tool(name):
    """A JDK tool from JAVA_HOME, else from PATH."""
    suffix = ".exe" if os.name == "nt" else ""
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / (name + suffix)
        if candidate.is_file():
            return str(candidate)
    found = shutil.which(name)
    if not found:
        raise Missing(f"{name} is not on PATH and JAVA_HOME does not provide it")
    return found


def run(command, env=None, timeout=600, diagnostics=False, aot_training=False, cwd=None):
    """Runs a command; fails with its output when it exits with another status than 0."""
    completed = subprocess.run(command, capture_output=True, text=True, env=env, timeout=timeout, cwd=cwd,
                               check=False)
    if completed.returncode != 0:
        raise Failed(f"{Path(command[0]).name} exited with {completed.returncode}:\n"
                     f"{completed.stdout}{completed.stderr}".rstrip())
    output = completed.stdout + completed.stderr
    checked = output
    if diagnostics and aot_training:
        exclusions = [line for line in output.splitlines() if AOT_EXCLUSION.fullmatch(line)]
        if exclusions:
            print("JVM AOT training exclusions (these classes remain uncached):\n"
                  + "\n".join(exclusions), flush=True)
        checked = "\n".join(line for line in output.splitlines()
                            if not AOT_EXCLUSION.fullmatch(line))
    if diagnostics and BAD_DIAGNOSTIC.search(checked):
        raise Failed(f"{Path(command[0]).name} logged an error or warning:\n{output}")
    return output if diagnostics else completed.stdout


def app_version(app_jar):
    """The version in the app jar's name, without -SNAPSHOT: jpackage wants numbers only."""
    match = re.fullmatch(r"datacraft-desktop-(\d+(?:\.\d+){0,2})(?:-SNAPSHOT)?\.jar", app_jar.name)
    if match is None:
        raise Missing(f"{app_jar.name} does not carry a numeric version")
    return match.group(1)


def build_inputs(target):
    """The app jar, its libraries and this platform's JavaFX jars, as built by `package`."""
    jars = sorted(target.glob("datacraft-desktop-*.jar"))
    if len(jars) != 1:
        raise Missing(f"expected one app jar in {target}, found {len(jars)}: run "
                      "./mvnw -f desktop/pom.xml package first")
    libraries = sorted((target / "lib").glob("*.jar"))
    # The platform jars carry a classifier (javafx-base-25.0.5-win.jar); the jars without one
    # are empty placeholders that jlink cannot use.
    javafx = sorted(path for path in (target / "javafx").glob("javafx-*-*-*.jar"))
    names = {re.sub(r"-[\d.]+-[\w-]+\.jar$", "", path.name).replace("-", ".") for path in javafx}
    if not set(JAVAFX_MODULES) <= names:
        raise Missing(f"the JavaFX platform jars are missing from {target / 'javafx'}")
    return jars[0], libraries, javafx


def jdk_modules(jdeps, app_jar, libraries, javafx):
    """The JDK modules the app and, recursively, its libraries need, by jdeps."""
    module_path = os.pathsep.join(str(path) for path in javafx)
    class_path = os.pathsep.join(str(path) for path in libraries)
    output = run([jdeps, "--print-module-deps", "--ignore-missing-deps", "--recursive",
                  "--multi-release", "25", "--module-path", module_path, "--class-path", class_path,
                  str(app_jar)])
    # The module list is the last line; jdeps may print notes above it.
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    if not lines or not re.fullmatch(r"[\w.]+(,[\w.]+)*", lines[-1]):
        raise Failed(f"jdeps printed no module list:\n{output}")
    return sorted(set(lines[-1].split(",")) | set(JAVAFX_MODULES))


def verify_javafx_patch(javafx):
    """Refuse an unguarded package even if a stale/manual build supplied the input jars."""
    graphics = [path for path in javafx if path.name.startswith("javafx-graphics-")]
    if len(graphics) != 1:
        raise Failed("expected exactly one JavaFX graphics module")
    with zipfile.ZipFile(graphics[0]) as jar:
        if (PATCH_MARKER not in jar.namelist()
                or b"patch=accessible-disposal-v1\n" not in jar.read(PATCH_MARKER)):
            raise Failed("JavaFX disposal guard missing: run the desktop Maven build first")
        digest = hashlib.sha256(jar.read("com/sun/glass/ui/Accessible.class")).hexdigest()
        if f"patched.sha256={digest}\n".encode() not in jar.read(PATCH_MARKER):
            raise Failed("JavaFX disposal guard checksum mismatch: rebuild the desktop modules")


def jlink_module_path(jlink, javafx):
    """The JavaFX jars, after the JDK's jmods when it ships them.

    A JDK without jmods links from its own run-time image (JEP 493) and needs no entry for them;
    one that ships them needs them named, or jlink finds no java.base.
    """
    jmods = Path(jlink).resolve().parent.parent / "jmods"
    entries = [str(jmods)] if jmods.is_dir() else []
    return os.pathsep.join(entries + [str(path) for path in javafx])


def png(size, pixels):
    """A PNG of RGBA rows, standard library only."""
    raw = b"".join(b"\x00" + bytes(row) for row in pixels)

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


def icon_pixels(size):
    """The app icon, as the app draws it (AppIcon.java): a Prussian-blue plate and a run ribbon."""
    top, bottom = (0x12, 0x30, 0x4A), (0x0E, 0x4D, 0x5E)
    segments = [(0x4C, 0xC2, 0xB2), (0x4C, 0xC2, 0xB2), (0x5D, 0xB2, 0xE6), (0x4C, 0xC2, 0xB2),
                (0xE6, 0xEE, 0xF5)]
    radius, gap = size * 0.22, size * 0.05
    width = (size * 0.64 - gap * (len(segments) - 1)) / len(segments)
    height, left = size * 0.40, size * 0.18
    y0 = (size - height) / 2
    samples = 4  # 4 x 4 supersampling for smooth edges
    rows = []
    for y in range(size):
        row = []
        for x in range(size):
            rgb_sum, alpha_sum = [0.0, 0.0, 0.0], 0.0
            for sy in range(samples):
                for sx in range(samples):
                    px, py = x + (sx + 0.5) / samples, y + (sy + 0.5) / samples
                    if not inside_rounded(px, py, 0, 0, size, size, radius):
                        continue
                    t = py / size
                    color = tuple(top[i] + (bottom[i] - top[i]) * t for i in range(3))
                    index = int((px - left) // (width + gap))
                    if (0 <= index < len(segments) and px - left - index * (width + gap) <= width
                            and y0 <= py <= y0 + height):
                        color = segments[index]
                    for i in range(3):
                        rgb_sum[i] += color[i]
                    alpha_sum += 1
            if alpha_sum:
                row.extend(int(round(c / alpha_sum)) for c in rgb_sum)
                row.append(int(round(255 * alpha_sum / samples ** 2)))
            else:
                row.extend((0, 0, 0, 0))
        rows.append(row)
    return rows


def inside_rounded(px, py, x, y, w, h, r):
    cx = min(max(px, x + r), x + w - r)
    cy = min(max(py, y + r), y + h - r)
    return (px - cx) ** 2 + (py - cy) ** 2 <= r * r


def write_icon(directory, windows=None):
    """An .ico (Windows, several sizes as PNG entries) or a .png (Linux, macOS uses the default)."""
    if windows is None:
        windows = os.name == "nt"
    if windows:
        sizes = (16, 24, 32, 48, 64, 128, 256)
        images = [png(size, icon_pixels(size)) for size in sizes]
        header = struct.pack("<HHH", 0, 1, len(sizes))
        offset = 6 + 16 * len(sizes)
        entries = b""
        for size, image in zip(sizes, images):
            entries += struct.pack("<BBBBHHII", size % 256, size % 256, 0, 0, 1, 32, len(image), offset)
            offset += len(image)
        path = directory / f"{NAME}.ico"
        path.write_bytes(header + entries + b"".join(images))
        return path
    path = directory / f"{NAME}.png"
    path.write_bytes(png(256, icon_pixels(256)))
    return path


def layout(image):
    """(launcher, cfg file) of a jpackage app image on this platform."""
    if os.name == "nt":
        return image / f"{NAME}.exe", image / "app" / f"{NAME}.cfg"
    if sys.platform == "darwin":
        return (image / "Contents" / "MacOS" / NAME, image / "Contents" / "app" / f"{NAME}.cfg")
    return image / "bin" / NAME, image / "lib" / "app" / f"{NAME}.cfg"


def use_cache(cfg):
    """Adds the AOT cache to the launcher's JVM options ($APPDIR is the launcher's app directory)."""
    text = cfg.read_text(encoding="utf-8")
    option = f"java-options=-XX:AOTCache=$APPDIR/{AOT_FILE}"
    updated, count = re.subn(r"^\[JavaOptions\]\r?\n", lambda match: match.group(0) + option + "\n",
                             text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise Failed(f"{cfg} has no [JavaOptions] section")
    cfg.write_text(updated, encoding="utf-8")


def smoke(launcher, work, extra_options=None, *, home=None, require_aot=False):
    """Starts the image with --smoke in a fresh data directory; returns its start-up time.

    The directory is under the build's own work directory, not the system TEMP: on some Windows
    hosts the AF_UNIX socket files the JDK creates for its NIO pipes cannot be deleted there.
    """
    work.mkdir(parents=True, exist_ok=True)
    if home is None:
        with tempfile.TemporaryDirectory(prefix="home-", dir=work, ignore_cleanup_errors=True) as fresh:
            return smoke(launcher, work, extra_options, home=Path(fresh), require_aot=require_aot)
    home.mkdir(parents=True, exist_ok=True)
    # FileHandler appends when this profile is reused. Remove only this disposable profile's
    # previous current log, so an old first-frame line can never satisfy a later start.
    log = home / "logs" / "desktop-0.log"
    log.unlink(missing_ok=True)
    env = dict(os.environ, DATACRAFT_DESKTOP_HOME=str(home))
    env.pop("JAVA_TOOL_OPTIONS", None)
    options = list(extra_options or [])
    if require_aot:
        options += ["-XX:AOTMode=on", "-Xlog:aot=info"]
    if options:
        # The JVM tokenizes this value, independently of subprocess/shell quoting. Protect
        # paths with spaces (including the AOT output in a checkout such as "My Projects").
        env["JAVA_TOOL_OPTIONS"] = " ".join(quote_jvm_option(option) for option in options)
    training = "-Ddatacraft.desktop.aotTraining=true" in options
    output = run([str(launcher), "--smoke"], env=env, timeout=180, diagnostics=True,
                 aot_training=training)
    if require_aot and "Using AOT-linked classes: true" not in output:
        raise Failed(f"the JVM did not confirm that AOT-linked classes were used:\n{output}")
    text = log.read_text(encoding="utf-8") if log.is_file() else ""
    if BAD_DIAGNOSTIC.search(text):
        raise Failed(f"the app logged a start-up error or warning:\n{text}")
    if training and "AOT training completed: all five views in both themes" not in text:
        raise Failed("the AOT training did not visit all five views in both themes")
    match = STARTUP_LINE.search(text)
    if match is None:
        raise Failed(f"the app ran but logged no first frame:\n{text}")
    phases = [line.split("Start-up: ", 1)[1] for line in text.splitlines() if "Start-up: " in line]
    print("Start-up phases: " + "; ".join(phases), flush=True)
    return int(match.group(1))


def check_startup(launcher, work, samples, budget_ms, require_aot):
    """Every sample counts, including the first; keep a machine-readable report on failure too."""
    results = []
    with tempfile.TemporaryDirectory(prefix="reused-", dir=work,
                                     ignore_cleanup_errors=True) as reused:
        for mode in ("fresh", "reused"):
            for index in range(samples):
                view, theme = "overview", "SYSTEM"
                if mode == "reused":
                    view, theme = restore_startup_view(Path(reused), index)
                # The first fresh sample seeds this profile. All reused samples then use an
                # existing DB, including overview/LIGHT, regardless of the host's system theme.
                home = Path(reused) if mode == "reused" or index == 0 else None
                elapsed = smoke(launcher, work, home=home,
                                require_aot=require_aot)
                results.append({"profile": mode, "sample": index + 1, "view": view, "theme": theme,
                                "first_frame_ms": elapsed})
                print(f"Start-up {mode} {index + 1}/{samples} {view}/{theme}: {elapsed} ms", flush=True)
    times = [result["first_frame_ms"] for result in results]
    report = {"platform": platform.platform(), "aot_verified": require_aot,
              "budget_ms": budget_ms, "samples": results,
              "median_ms": statistics.median(times), "max_ms": max(times),
              "within_budget": max(times) <= budget_ms if budget_ms else None}
    (work / "startup.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    if budget_ms and max(times) > budget_ms:
        raise Failed(f"start-up budget {budget_ms} ms exceeded: max {max(times)} ms; "
                     f"all {len(times)} samples are in {work / 'startup.json'}")
    return report


def restore_startup_view(home, index):
    """Only the disposable benchmark DB, created by a completed app start, is edited."""
    database = home / "datacraft.db"
    if not database.is_file():
        raise Failed("the first start did not create its benchmark database")
    views = ("overview", "jobs", "pipelines", "activity", "settings")
    view = views[index % len(views)]
    theme = "LIGHT" if (index // len(views)) % 2 == 0 else "DARK"
    connection = sqlite3.connect(database)
    try:
        with connection:
            connection.executemany(
                "INSERT INTO settings(key, value, updated_at) VALUES(?, ?, 0) "
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                [("ui.lastView", view), ("ui.theme", theme)])
    finally:
        connection.close()
    return view, theme


def quote_jvm_option(option):
    """One JAVA_TOOL_OPTIONS argument: its quote delimiter must not occur in the value."""
    for quote in ('"', "'"):
        if quote not in option:
            return quote + option + quote
    raise Failed("a JVM option cannot contain both single and double quotes")


def run_jpackage(executable, arguments, work):
    """Keep Unicode and wildcard metadata out of the Windows launcher's ANSI argv conversion.

    jpackage reads this UTF-8 argument file itself. Only its ASCII filename crosses the launcher;
    quoted paths in the file retain spaces, backslashes, quotes and non-ASCII characters.
    """
    filename = "jpackage.args"
    (work / filename).write_text(
        "\n".join(json.dumps(argument, ensure_ascii=False) for argument in arguments) + "\n",
        encoding="utf-8")
    return run([executable, "@" + filename], cwd=work)


def remove(directory):
    """Deletes a previous build's directory; jlink leaves some runtime files read-only."""

    def writable_and_retry(function, path, _error):
        os.chmod(path, stat.S_IWRITE)
        function(path)

    if directory.exists():
        shutil.rmtree(directory, onexc=writable_and_retry)


def make_writable(directory):
    """Gives the owner write access to every file under directory. jpackage copies the launcher
    read-only, and on Windows a read-only file stops `mvn clean` from deleting the image."""
    for path in directory.rglob("*"):
        if path.is_file():
            mode = path.stat().st_mode
            if not mode & stat.S_IWUSR:
                os.chmod(path, mode | stat.S_IWUSR)


def package(root, skip_aot, startup_samples=10, startup_budget_ms=1500):
    desktop = root / "desktop"
    target = desktop / "target"
    app_jar, libraries, javafx = build_inputs(target)
    verify_javafx_patch(javafx)
    jdeps, jlink, jpackage = tool("jdeps"), tool("jlink"), tool("jpackage")
    version = app_version(app_jar)
    work = target / "package"
    dist = target / "dist"
    remove(work)
    remove(dist)
    (work / "input" / "lib").mkdir(parents=True)
    shutil.copy2(app_jar, work / "input")
    for library in libraries:
        shutil.copy2(library, work / "input" / "lib")

    modules = jdk_modules(jdeps, app_jar, libraries, javafx)
    print(f"Runtime modules: {', '.join(modules)}")
    run([jlink, "--module-path", jlink_module_path(jlink, javafx),
         "--add-modules", ",".join(modules), "--strip-debug", "--no-header-files", "--no-man-pages",
         "--compress=zip-6", "--output", str(work / "runtime")])

    icon = write_icon(work)
    command = [jpackage, "--type", "app-image", "--name", NAME, "--app-version", version,
               "--vendor", "datacraft-lab", "--description", "数艺工坊",
               "--input", str(work / "input"), "--main-jar", app_jar.name, "--main-class", MAIN_CLASS,
               "--runtime-image", str(work / "runtime"), "--icon", str(icon), "--dest", str(dist)]
    for option in JVM_OPTIONS:
        command += ["--java-options", option]
    run_jpackage(command[0], command[1:], work)
    image = dist / (f"{NAME}.app" if sys.platform == "darwin" else NAME)
    launcher, cfg = layout(image)
    if not launcher.is_file() or not cfg.is_file():
        raise Failed(f"jpackage did not produce {launcher} and {cfg}")

    if not skip_aot:
        cache = cfg.parent / AOT_FILE
        trained = smoke(launcher, work / "smoke", [f"-XX:AOTCacheOutput={cache}",
                                                  "-Ddatacraft.desktop.aotTraining=true"])
        if not cache.is_file():
            raise Failed("the training start did not write the AOT cache")
        print(f"Trained the AOT cache ({cache.stat().st_size // (1024 * 1024)} MB); "
              f"that start reached its first frame at {trained} ms")
        use_cache(cfg)
    make_writable(image)
    make_writable(work)
    report = check_startup(launcher, work, startup_samples, startup_budget_ms,
                           require_aot=not skip_aot)
    print(f"{image} starts: median {report['median_ms']} ms, max {report['max_ms']} ms"
          + ("" if skip_aot else " with verified AOT-linked classes"))
    make_writable(image)
    make_writable(work)
    return image


def main(argv=None):
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n", 1)[0])
    parser.add_argument("--root", default=".", help="repository root (default: .)")
    parser.add_argument("--skip-aot", action="store_true", help="build the image without an AOT cache")
    parser.add_argument("--startup-samples", type=int, default=10,
                        help="starts per fresh/reused profile group (default: 10, all saved views)")
    parser.add_argument("--startup-budget-ms", type=int, default=1500,
                        help="maximum allowed first-frame time; 0 measures without a budget")
    args = parser.parse_args(argv)
    if args.startup_samples < 1 or args.startup_budget_ms < 0:
        parser.error("startup samples must be positive and the budget must be nonnegative")
    try:
        package(Path(args.root).resolve(), args.skip_aot, args.startup_samples, args.startup_budget_ms)
    except Missing as error:
        print(f"::error::{error}")
        return 2
    except (Failed, OSError, subprocess.TimeoutExpired) as error:
        print(f"::error::{error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
