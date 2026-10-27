"""Packages 数艺工坊 as a self-contained app image with its own Java runtime and an AOT cache.

Run it after `./mvnw -f desktop/pom.xml package`, with the JDK the app was built with (25.0.5 or
newer) on PATH or in JAVA_HOME:

1. jdeps lists the JDK modules the app and its libraries use; jlink builds a trimmed runtime
   from them plus the JavaFX modules of this platform (desktop/target/javafx).
2. jpackage builds the app image `desktop/target/dist/DataCraft` around that runtime, with the
   app jar and its libraries on the class path and the icon drawn here (no image file is kept).
3. The image is started once with `--smoke`, which quits two seconds after the first frame, while
   the JVM records an AOT cache (JEP 483/514) of the classes that start-up loaded and linked.
   The launcher is then told to use the cache, which cuts the start-up by about a third.
4. The image is started once more with `--smoke`, now with the cache, and must exit with 0; its
   start-up log line is printed.

Both starts use a temporary data directory (DATACRAFT_DESKTOP_HOME), never the user's
~/.datacraft. The app opens a window, so a display is needed: Xvfb on a Linux runner.
Standard library only.

Usage: python3 -B cicd/desktop/package_desktop.py [--skip-aot] [--root REPOSITORY_ROOT]
Exit status: 0 when the image is built and starts, 1 when a step fails, 2 when an input is missing.
"""

import argparse
import os
from pathlib import Path
import re
import shutil
import stat
import struct
import subprocess
import sys
import tempfile
import zlib

NAME = "DataCraft"
MAIN_CLASS = "com.example.datacraft.desktop.Launcher"
JVM_OPTIONS = ["--enable-native-access=javafx.graphics,ALL-UNNAMED"]
JAVAFX_MODULES = ("javafx.base", "javafx.graphics", "javafx.controls")
AOT_FILE = "datacraft.aot"
STARTUP_LINE = re.compile(r"Start-up: first frame at (\d+) ms")


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


def run(command, env=None, timeout=600):
    """Runs a command; fails with its output when it exits with another status than 0."""
    completed = subprocess.run(command, capture_output=True, text=True, env=env, timeout=timeout,
                               check=False)
    if completed.returncode != 0:
        raise Failed(f"{Path(command[0]).name} exited with {completed.returncode}:\n"
                     f"{completed.stdout}{completed.stderr}".rstrip())
    return completed.stdout


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


def smoke(launcher, work, extra_options=None):
    """Starts the image with --smoke in a fresh data directory; returns its start-up time.

    The directory is under the build's own work directory, not the system TEMP: on some Windows
    hosts the AF_UNIX socket files the JDK creates for its NIO pipes cannot be deleted there.
    """
    work.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="home-", dir=work, ignore_cleanup_errors=True) as home:
        env = dict(os.environ, DATACRAFT_DESKTOP_HOME=home)
        env.pop("JAVA_TOOL_OPTIONS", None)
        if extra_options:
            env["JAVA_TOOL_OPTIONS"] = " ".join(extra_options)
        run([str(launcher), "--smoke"], env=env, timeout=180)
        log = Path(home) / "logs" / "desktop-0.log"
        text = log.read_text(encoding="utf-8") if log.is_file() else ""
        match = STARTUP_LINE.search(text)
        if match is None:
            raise Failed(f"the app ran but logged no first frame:\n{text}")
        return int(match.group(1))


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


def package(root, skip_aot):
    desktop = root / "desktop"
    target = desktop / "target"
    app_jar, libraries, javafx = build_inputs(target)
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
    run(command)
    image = dist / (f"{NAME}.app" if sys.platform == "darwin" else NAME)
    launcher, cfg = layout(image)
    if not launcher.is_file() or not cfg.is_file():
        raise Failed(f"jpackage did not produce {launcher} and {cfg}")

    if not skip_aot:
        cache = cfg.parent / AOT_FILE
        trained = smoke(launcher, work / "smoke", [f"-XX:AOTCacheOutput={cache}"])
        if not cache.is_file():
            raise Failed("the training start did not write the AOT cache")
        print(f"Trained the AOT cache ({cache.stat().st_size // (1024 * 1024)} MB); "
              f"that start reached its first frame at {trained} ms")
        use_cache(cfg)
    started = smoke(launcher, work / "smoke")
    print(f"{image} starts: first frame at {started} ms"
          + ("" if skip_aot else " with the AOT cache"))
    make_writable(image)
    make_writable(work)
    return image


def main(argv=None):
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n", 1)[0])
    parser.add_argument("--root", default=".", help="repository root (default: .)")
    parser.add_argument("--skip-aot", action="store_true", help="build the image without an AOT cache")
    args = parser.parse_args(argv)
    try:
        package(Path(args.root).resolve(), args.skip_aot)
    except Missing as error:
        print(f"::error::{error}")
        return 2
    except (Failed, OSError, subprocess.TimeoutExpired) as error:
        print(f"::error::{error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
