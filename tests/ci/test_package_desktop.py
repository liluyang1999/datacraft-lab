"""The desktop packaging script's own logic: inputs, versions, module lists, the icon, the cache.

Running jlink, jpackage and the app needs a JDK and a display; the desktop CI job does that. These
tests cover what can go wrong without them.
"""

import importlib.util
import hashlib
import json
import os
from pathlib import Path
import struct
import shlex
import tempfile
import unittest
from unittest import mock
import subprocess
import zipfile
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
    def test_compiler_policy_keeps_c2_for_non_windows_rendering(self):
        for system, level in (("Windows", 1), ("Linux", 4), ("Darwin", 4)):
            with self.subTest(system=system), mock.patch.object(packaging.platform, "system", return_value=system):
                options = packaging.jvm_options()
                self.assertIn(f"-XX:TieredStopAtLevel={level}", options)
                self.assertIn("-Xmx256m", options)

    def test_jpackage_preserves_unicode_wildcards_and_paths_in_a_utf8_argument_file(self):
        arguments = ["--description", '数艺工坊 ???? "quoted"', "--input", r'C:\构建 目录\input']
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            with mock.patch.object(packaging, "run", return_value="packaged") as run:
                self.assertEqual("packaged", packaging.run_jpackage("jpackage", arguments, work))
            run.assert_called_once_with(["jpackage", "@jpackage.args"], cwd=work)
            # The argument-file grammar consumes quoted whitespace and escaped slash/quote pairs.
            self.assertEqual(arguments, shlex.split((work / "jpackage.args").read_text(encoding="utf-8")))

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


class NativeLibraryTests(unittest.TestCase):
    def test_platform_natives_are_bundled_byte_for_byte_at_the_jrt_loader_location(self):
        for system, name, folder in (("Windows", "glass.dll", "bin/javafx"),
                                     ("Linux", "libglass.so", "lib"),
                                     ("Darwin", "libglass.dylib", "lib")):
            with self.subTest(system=system), tempfile.TemporaryDirectory() as directory:
                work = Path(directory)
                jar = work / "javafx-graphics.jar"
                with zipfile.ZipFile(jar, "w") as archive:
                    archive.writestr(name, b"native bytes")
                    archive.writestr("module-info.class", b"class bytes")
                with mock.patch.object(packaging.platform, "system", return_value=system):
                    manifest = packaging.install_javafx_natives([jar], work / "runtime")
                self.assertEqual(b"native bytes", (work / "runtime" / folder / name).read_bytes())
                self.assertEqual([{"path": f"{folder}/{name}", "source": jar.name,
                                   "sha256": hashlib.sha256(b"native bytes").hexdigest()}], manifest)
                packaging.verify_native_manifest(work / "runtime", manifest)

    def test_native_archive_paths_cannot_escape_the_runtime(self):
        for name in ("../glass.dll", "nested/glass.dll", r"..\glass.dll", "/glass.dll", "C:glass.dll", "CON.dll"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                work = Path(directory)
                jar = work / "javafx.jar"
                with zipfile.ZipFile(jar, "w") as archive:
                    archive.writestr(name, b"native")
                with mock.patch.object(packaging.platform, "system", return_value="Windows"):
                    with self.assertRaisesRegex(packaging.Failed, "native library name"):
                        packaging.install_javafx_natives([jar], work / "runtime")

    def test_conflicting_libraries_never_overwrite_runtime_or_another_module(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            jars = [work / "first.jar", work / "second.jar"]
            for jar in jars:
                with zipfile.ZipFile(jar, "w") as archive:
                    archive.writestr("glass.dll", b"native")
            with mock.patch.object(packaging.platform, "system", return_value="Windows"):
                with self.assertRaisesRegex(packaging.Failed, "duplicate native"):
                    packaging.install_javafx_natives(jars, work / "runtime")
                protected = work / "runtime/bin/javafx/glass.dll"
                protected.parent.mkdir(parents=True, exist_ok=True)
                protected.write_bytes(b"existing")
                with self.assertRaisesRegex(packaging.Failed, "already exists"):
                    packaging.install_javafx_natives(jars[:1], work / "runtime")
                self.assertEqual(b"existing", protected.read_bytes())

    def test_missing_platform_natives_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "javafx.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("module-info.class", b"class")
            with self.assertRaisesRegex(packaging.Failed, "no JavaFX native"):
                packaging.install_javafx_natives([jar], Path(directory) / "runtime")

    def test_a_modified_native_in_the_final_image_fails_verification(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory)
            library = touch(runtime / "bin/javafx/glass.dll")
            manifest = [{"path": "bin/javafx/glass.dll", "source": "javafx.jar",
                         "sha256": hashlib.sha256(b"original").hexdigest()}]
            with self.assertRaisesRegex(packaging.Failed, "native checksum"):
                packaging.verify_native_manifest(runtime, manifest)
            library.unlink()
            with self.assertRaisesRegex(packaging.Failed, "native.*missing"):
                packaging.verify_native_manifest(runtime, manifest)

    def test_smoke_rejects_javafx_falling_back_to_the_user_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)

            def launch(command, env=None, **kwargs):
                assert env is not None
                home = Path(env["DATACRAFT_DESKTOP_HOME"])
                touch(home / "javafx-cache/glass.dll")
                log = touch(home / "logs/desktop-0.log")
                log.write_text("Start-up: first frame at 100 ms", encoding="utf-8")
                return ""

            with mock.patch.object(packaging, "run", side_effect=launch):
                with self.assertRaisesRegex(packaging.Failed, "JavaFX.*cache"):
                    packaging.smoke(Path("launcher"), work)


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

    def test_smoke_uses_parent_monotonic_time_instead_of_estimated_process_age(self):
        with tempfile.TemporaryDirectory() as directory:
            def launch(command, env=None, **kwargs):
                assert env is not None
                log = Path(env["DATACRAFT_DESKTOP_HOME"]) / "logs" / "desktop-0.log"
                log.parent.mkdir()
                log.write_text("Start-up: first frame at 9999 ms\n", encoding="utf-8")
                return ""

            with (mock.patch.object(packaging, "run", side_effect=launch),
                  mock.patch("time.perf_counter_ns", side_effect=[1_000_000_000, 2_234_000_001])):
                self.assertEqual(1235, packaging.smoke(Path("launcher"), Path(directory)))

    def test_smoke_preserves_a_jvm_option_with_spaces_as_one_argument(self):
        # The JVM parses JAVA_TOOL_OPTIONS itself, even when no shell is used to launch it.
        option = "-Ddatacraft.probe=folder with spaces"
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)

            def launch(command, env=None, timeout=600, diagnostics=False, aot_training=False):
                assert env is not None
                parsed = shlex.split(env["JAVA_TOOL_OPTIONS"])
                self.assertEqual(option, parsed[0])
                self.assertEqual(f"-Djavafx.cachedir={Path(env['DATACRAFT_DESKTOP_HOME']) / 'javafx-cache'}", parsed[1])
                log = Path(env["DATACRAFT_DESKTOP_HOME"]) / "logs" / "desktop-0.log"
                log.parent.mkdir()
                log.write_text("Start-up: first frame at 100 ms", encoding="utf-8")
                return ""

            with (mock.patch.object(packaging, "run", side_effect=launch),
                  mock.patch("time.perf_counter_ns", side_effect=[0, 100_000_000])):
                self.assertEqual(100, packaging.smoke(Path("launcher"), work, [option]))

    def test_observation_keeps_a_slow_start_and_excludes_time_after_readiness(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "desktop.log"
            with mock.patch("time.perf_counter_ns", side_effect=[1_000_000_000, 2_600_000_001]) as clock:
                with packaging.FirstFrameTimer(log) as timer:
                    log.write_text("Start-up: first frame at 100 ms\n", encoding="utf-8")
                    self.assertTrue(timer.ready.wait(5), "observer did not see the frame")
                    self.assertEqual(1601, timer.elapsed_ms)
                    # Exit/cleanup can happen much later; it must not change this measurement.
                self.assertEqual(2, clock.call_count)
                self.assertEqual(1601, timer.elapsed_ms)
                self.assertFalse(timer.worker.is_alive())

    def test_observer_stops_when_launch_fails_and_propagates_log_read_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "desktop.log"
            timer = packaging.FirstFrameTimer(log)
            with self.assertRaisesRegex(packaging.Failed, "launch failed"):
                with timer:
                    raise packaging.Failed("launch failed")
            self.assertFalse(timer.worker.is_alive())
            self.assertIsNone(timer.elapsed_ms)
            failed = packaging.FirstFrameTimer(log)
            with mock.patch.object(Path, "read_text", side_effect=PermissionError("unreadable")):
                with self.assertRaisesRegex(PermissionError, "unreadable"):
                    with failed:
                        self.assertTrue(failed.ready.wait(5))
            self.assertFalse(failed.worker.is_alive())

    def test_an_uncaught_native_callback_fails_even_when_the_process_exits_zero(self):
        for error in ('Exception in thread "JavaFX Application Thread" java.lang.NullPointerException',
                      'java.lang.OutOfMemoryError: Java heap space',
                      'WARNING: native access is not enabled'):
            with self.subTest(error=error):
                result = subprocess.CompletedProcess(["launcher"], 0, "", error)
                with mock.patch.object(packaging.subprocess, "run", return_value=result):
                    with self.assertRaisesRegex(packaging.Failed, "logged an error"):
                        packaging.run(["launcher"], diagnostics=True)

    def test_a_fallback_start_cannot_be_reported_as_using_aot(self):
        with tempfile.TemporaryDirectory() as directory:
            with mock.patch.object(packaging, "run", return_value="AOT cache was not used"):
                with self.assertRaisesRegex(packaging.Failed, "did not confirm"):
                    packaging.smoke(Path("launcher"), Path(directory), require_aot=True)

    def test_jdk_aot_exclusions_are_visible_and_allowed_only_during_training(self):
        output = ("[3.157s][warning][aot] Skipping jdk/internal/event/Event: JFR event class\n"
                  "[3.159s][warning][aot] Skipping jdk/proxy1/$Proxy0: Unsupported location\n")
        result = subprocess.CompletedProcess(["launcher"], 0, output, "")
        with mock.patch.object(packaging.subprocess, "run", return_value=result):
            with self.assertRaises(packaging.Failed):
                packaging.run(["launcher"], diagnostics=True)
            with mock.patch("builtins.print") as printed:
                self.assertEqual(output, packaging.run(["launcher"], diagnostics=True, aot_training=True))
                self.assertIn(output.strip(), printed.call_args.args[0])

    def test_training_still_rejects_unexpected_warnings_and_exceptions(self):
        for output in ("[3.157s][warning][aot] Skipping app/Broken: Unsupported location\n",
                       "[3.157s][warning][aot] Skipping jdk/internal/event/Event: bad version\n",
                       "java.lang.NullPointerException: missing handler\n"):
            with self.subTest(output=output):
                result = subprocess.CompletedProcess(["launcher"], 0, output, "")
                with mock.patch.object(packaging.subprocess, "run", return_value=result):
                    with self.assertRaises(packaging.Failed):
                        packaging.run(["launcher"], diagnostics=True, aot_training=True)

    def test_training_accepts_an_incomplete_event_only_with_its_jfr_exclusion(self):
        for name in ("VirtualThreadStartEvent", "VirtualThreadEndEvent"):
            with self.subTest(name=name):
                output = (f"[2.852s][warning][aot] Skipping jdk/internal/event/{name}: Not in loaded state\n"
                          f"[2.852s][warning][aot] Skipping jdk/internal/event/{name}: JFR event class\n")
                result = subprocess.CompletedProcess(["launcher"], 0, output, "")
                with mock.patch.object(packaging.subprocess, "run", return_value=result):
                    with self.assertRaises(packaging.Failed):
                        packaging.run(["launcher"], diagnostics=True)
                    with mock.patch("builtins.print") as printed:
                        self.assertEqual(output, packaging.run(["launcher"], diagnostics=True, aot_training=True))
                        self.assertIn(output.strip(), printed.call_args.args[0])

    def test_training_does_not_hide_unconfirmed_or_failed_event_loading(self):
        confirmed = "[2.852s][warning][aot] Skipping jdk/internal/event/VirtualThreadEndEvent: JFR event class\n"
        for output in (
                "[2.852s][warning][aot] Skipping jdk/internal/event/VirtualThreadEndEvent: Not in loaded state\n",
                confirmed + "[2.852s][warning][aot] Skipping jdk/internal/event/VirtualThreadStartEvent: Not in loaded state\n",
                confirmed + "[2.852s][warning][aot] Skipping app/Broken: Not in loaded state\n",
                confirmed + "[2.852s][warning][aot] Skipping jdk/internal/event/VirtualThreadEndEvent: In error state\n"):
            with self.subTest(output=output):
                result = subprocess.CompletedProcess(["launcher"], 0, output, "")
                with mock.patch.object(packaging.subprocess, "run", return_value=result):
                    with self.assertRaises(packaging.Failed):
                        packaging.run(["launcher"], diagnostics=True, aot_training=True)

    def test_a_reused_profile_cannot_reuse_an_earlier_first_frame(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory) / "home"
            log = home / "logs" / "desktop-0.log"
            log.parent.mkdir(parents=True)
            log.write_text("Start-up: first frame at 100 ms\n", encoding="utf-8")
            with mock.patch.object(packaging, "run", return_value=""):
                with self.assertRaisesRegex(packaging.Failed, "logged no first frame"):
                    packaging.smoke(Path("launcher"), Path(directory), home=home)

    def test_saved_view_selection_uses_the_real_benchmark_database(self):
        with tempfile.TemporaryDirectory() as directory:
            home = Path(directory)
            database = packaging.sqlite3.connect(home / "datacraft.db")
            try:
                database.execute("CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT, updated_at INTEGER)")
            finally:
                database.close()
            self.assertEqual(("settings", "DARK"), packaging.restore_startup_view(home, 9))
            database = packaging.sqlite3.connect(home / "datacraft.db")
            try:
                values = dict(database.execute("SELECT key, value FROM settings"))
            finally:
                database.close()
            self.assertEqual({"ui.lastView": "settings", "ui.theme": "DARK"}, values)

    def test_every_start_counts_and_a_slow_first_sample_preserves_failure_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            with (mock.patch.object(packaging, "smoke", side_effect=[1700, 900, 950, 1000]) as run,
                  mock.patch.object(packaging, "restore_startup_view", return_value=("jobs", "LIGHT")) as restore):
                with self.assertRaisesRegex(packaging.Failed, "budget 1500 ms exceeded"):
                    packaging.check_startup(Path("launcher"), work, 2, 1500, True)
                self.assertEqual(4, run.call_count)
                # A fresh sample seeds the shared profile; both reused starts restore it,
                # including the first, which must not depend on the OS theme.
                shared_home = run.call_args_list[0].kwargs["home"]
                self.assertIsNotNone(shared_home)
                self.assertIsNone(run.call_args_list[1].kwargs["home"])
                self.assertEqual([shared_home, shared_home],
                                 [call.kwargs["home"] for call in run.call_args_list[2:]])
                self.assertEqual([mock.call(shared_home, 0), mock.call(shared_home, 1)],
                                 restore.call_args_list)
            report = json.loads((work / "startup.json").read_text(encoding="utf-8"))
            self.assertFalse(report["within_budget"])
            self.assertEqual(1700, report["max_ms"])
            self.assertEqual([1700, 900, 950, 1000],
                             [sample["first_frame_ms"] for sample in report["samples"]])

    def test_a_measurement_without_a_budget_does_not_claim_budget_success(self):
        with tempfile.TemporaryDirectory() as directory:
            with (mock.patch.object(packaging, "smoke", return_value=2000),
                  mock.patch.object(packaging, "restore_startup_view", return_value=("overview", "LIGHT"))):
                report = packaging.check_startup(Path("launcher"), Path(directory), 1, 0, False)
            self.assertIsNone(report["within_budget"])
            self.assertFalse(report["aot_verified"])

    def test_an_unpatched_javafx_module_cannot_be_packaged(self):
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "javafx-graphics-25.0.5-win.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("module-info.class", b"module")
            with self.assertRaisesRegex(packaging.Failed, "accessibility patch missing"):
                packaging.verify_javafx_patch([jar])

    def test_a_stale_or_incomplete_windows_patch_cannot_be_packaged(self):
        common = b"guarded Accessible"
        windows = b"contract-correct WinAccessible"
        common_hash = hashlib.sha256(common).hexdigest()
        windows_hash = hashlib.sha256(windows).hexdigest()
        marker = (f"patch=accessible-contracts-v2\npatched.sha256={common_hash}\n"
                  f"windows.patched.sha256={windows_hash}\n")
        with tempfile.TemporaryDirectory() as directory:
            jar = Path(directory) / "javafx-graphics-25.0.5-win.jar"
            for label, properties, body in (
                ("old", marker.replace("accessible-contracts-v2", "accessible-disposal-v1"), windows),
                ("missing", marker, None),
                ("modified", marker, b"unpatched Windows class"),
                ("valid", marker, windows),
            ):
                with self.subTest(label=label):
                    with zipfile.ZipFile(jar, "w") as archive:
                        archive.writestr(packaging.PATCH_MARKER, properties)
                        archive.writestr("com/sun/glass/ui/Accessible.class", common)
                        if body is not None:
                            archive.writestr("com/sun/glass/ui/win/WinAccessible.class", body)
                    if label == "valid":
                        packaging.verify_javafx_patch([jar])
                    else:
                        with self.assertRaises(packaging.Failed):
                            packaging.verify_javafx_patch([jar])


if __name__ == "__main__":
    unittest.main()
