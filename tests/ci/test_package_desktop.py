"""The desktop packaging script's own logic: inputs, versions, module lists, the icon, the cache.

Running jlink, jpackage and the app needs a JDK and a display; the desktop CI job does that. These
tests cover what can go wrong without them.
"""

import importlib.util
import os
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock
import zlib

REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "cicd" / "desktop" / "package_desktop.py"

_spec = importlib.util.spec_from_file_location("package_desktop", SCRIPT)
assert _spec is not None and _spec.loader is not None, SCRIPT
packaging = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(packaging)


def touch(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"")
    return path


class InputTests(unittest.TestCase):
    def test_the_version_comes_from_the_jar_name_without_snapshot(self):
        self.assertEqual("0.1.0", packaging.app_version(Path("datacraft-desktop-0.1.0-SNAPSHOT.jar")))
        self.assertEqual("1.2", packaging.app_version(Path("datacraft-desktop-1.2.jar")))
        with self.assertRaises(packaging.Missing):
            packaging.app_version(Path("datacraft-desktop-${project.version}.jar"))

    def test_the_build_outputs_are_found_and_the_empty_javafx_placeholders_left_out(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            jar = touch(target / "datacraft-desktop-0.1.0-SNAPSHOT.jar")
            library = touch(target / "lib" / "sqlite-jdbc-3.53.4.0.jar")
            for module in ("base", "graphics", "controls"):
                touch(target / "javafx" / f"javafx-{module}-25.0.5.jar")
                touch(target / "javafx" / f"javafx-{module}-25.0.5-linux.jar")
            found_jar, libraries, javafx = packaging.build_inputs(target)
        self.assertEqual(jar, found_jar)
        self.assertEqual([library], libraries)
        self.assertEqual(3, len(javafx))
        self.assertTrue(all(path.name.endswith("-linux.jar") for path in javafx))

    def test_a_missing_build_or_javafx_platform_jar_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory)
            with self.assertRaisesRegex(packaging.Missing, "package first"):
                packaging.build_inputs(target)
            touch(target / "datacraft-desktop-0.1.0.jar")
            touch(target / "javafx" / "javafx-base-25.0.5-win.jar")
            with self.assertRaisesRegex(packaging.Missing, "JavaFX platform jars"):
                packaging.build_inputs(target)


class ModuleTests(unittest.TestCase):
    def test_the_module_list_is_the_last_line_and_always_holds_javafx(self):
        output = "Warning: split package com.example\njava.base,java.net.http,java.sql\n"
        with mock.patch.object(packaging, "run", return_value=output):
            modules = packaging.jdk_modules("jdeps", Path("app.jar"), [], [])
        self.assertEqual(["java.base", "java.net.http", "java.sql", "javafx.base",
                          "javafx.controls", "javafx.graphics"], modules)

    def test_output_without_a_module_list_fails(self):
        with mock.patch.object(packaging, "run", return_value="Error: something\n"):
            with self.assertRaises(packaging.Failed):
                packaging.jdk_modules("jdeps", Path("app.jar"), [], [])

    def test_jmods_are_named_only_when_the_jdk_ships_them(self):
        with tempfile.TemporaryDirectory() as directory:
            jdk = Path(directory)
            jlink = touch(jdk / "bin" / "jlink")
            javafx = [Path("javafx-base-25.0.5-linux.jar")]
            self.assertEqual("javafx-base-25.0.5-linux.jar",
                             packaging.jlink_module_path(str(jlink), javafx))
            (jdk / "jmods").mkdir()
            self.assertEqual(os.pathsep.join([str((jdk / "jmods").resolve()), "javafx-base-25.0.5-linux.jar"]),
                             packaging.jlink_module_path(str(jlink), javafx))


class IconTests(unittest.TestCase):
    def test_the_png_is_well_formed(self):
        image = packaging.png(16, packaging.icon_pixels(16))
        self.assertTrue(image.startswith(b"\x89PNG\r\n\x1a\n"))
        width, height, depth, color = struct.unpack(">IIBB", image[16:26])
        self.assertEqual((16, 16, 8, 6), (width, height, depth, color))
        data = image[image.index(b"IDAT") + 4:image.index(b"IEND") - 8]
        self.assertEqual(16 * (1 + 16 * 4), len(zlib.decompress(data)))

    def test_linux_gets_a_png(self):
        with tempfile.TemporaryDirectory() as directory:
            icon = packaging.write_icon(Path(directory), windows=False)
            self.assertEqual(".png", icon.suffix)
            self.assertEqual(b"PNG", icon.read_bytes()[1:4])

    def test_the_icon_is_transparent_in_the_corners_and_opaque_in_the_middle(self):
        pixels = packaging.icon_pixels(32)
        self.assertEqual(0, pixels[0][3])
        self.assertEqual(255, pixels[16][16 * 4 + 3])

    def test_windows_gets_an_ico_with_every_size(self):
        with tempfile.TemporaryDirectory() as directory:
            icon = packaging.write_icon(Path(directory), windows=True)
            data = icon.read_bytes()
        self.assertEqual(".ico", icon.suffix)
        reserved, kind, count = struct.unpack("<HHH", data[:6])
        self.assertEqual((0, 1, 7), (reserved, kind, count))


class CacheTests(unittest.TestCase):
    CFG = ("[Application]\napp.classpath=$APPDIR/datacraft-desktop-0.1.0.jar\n\n"
           "[JavaOptions]\njava-options=--enable-native-access=javafx.graphics,ALL-UNNAMED\n")

    def test_the_launcher_is_told_to_use_the_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            cfg = Path(directory) / "DataCraft.cfg"
            cfg.write_text(self.CFG, encoding="utf-8")
            packaging.use_cache(cfg)
            text = cfg.read_text(encoding="utf-8")
        self.assertIn("[JavaOptions]\njava-options=-XX:AOTCache=$APPDIR/datacraft.aot\n", text)
        self.assertIn("--enable-native-access", text)

    def test_a_cfg_without_java_options_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            cfg = Path(directory) / "DataCraft.cfg"
            cfg.write_text("[Application]\n", encoding="utf-8")
            with self.assertRaises(packaging.Failed):
                packaging.use_cache(cfg)

    def test_the_finished_image_can_be_deleted_by_a_clean_build(self):
        # jpackage copies the launcher read-only; on Windows that stops `mvn clean` from deleting it.
        with tempfile.TemporaryDirectory() as directory:
            image = Path(directory) / "DataCraft"
            launcher = touch(image / "DataCraft.exe")
            nested = touch(image / "runtime" / "lib" / "modules")
            for path in (launcher, nested):
                os.chmod(path, 0o444)
            packaging.make_writable(image)
            for path in (launcher, nested):
                self.assertTrue(os.access(path, os.W_OK), path)
                path.unlink()  # what the clean plugin does

    def test_the_start_up_time_is_read_from_the_log(self):
        line = "2026-10-27 17:09:27.455 INFO com.example.datacraft.desktop.DesktopApp: Start-up: first frame at 1094 ms"
        match = packaging.STARTUP_LINE.search(line)
        self.assertIsNotNone(match)
        assert match is not None
        self.assertEqual("1094", match.group(1))


if __name__ == "__main__":
    unittest.main()
