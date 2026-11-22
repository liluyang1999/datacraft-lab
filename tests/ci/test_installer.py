"""The Windows installer's sources and helpers: deploy/windows/installer.iss and the scripts that
build it (cicd/desktop/build_installer.py, prepare_inno.py) and walk its lifecycle
(cicd/desktop/verify_install.py). The lifecycle itself runs in CI's build stage and, with a
per-user fixture, on a workstation (docs/desktop/README.md)."""

import importlib.util
from pathlib import Path
import re
import sys
import tempfile
import unittest

REPOSITORY = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPOSITORY / "cicd" / "release"))


def load(name):
    path = REPOSITORY / "cicd" / "desktop" / f"{name}.py"
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec is not None and spec.loader is not None, path
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


builder = load("build_installer")
lifecycle = load("verify_install")
inno = load("prepare_inno")
INSTALLER = (REPOSITORY / "deploy" / "windows" / "installer.iss").read_text(encoding="utf-8")
SINGLE_INSTANCE = (REPOSITORY / "desktop/src/main/java/com/example/datacraft/desktop/app/"
                   "SingleInstance.java").read_text(encoding="utf-8")


class InstallerSourceTests(unittest.TestCase):
    def test_the_installer_takes_its_version_and_payload_from_the_build_only(self):
        for name in ("AppVersion", "AppImage", "SetupIcon", "OutDir", "ChineseMessages"):
            with self.subTest(define=name):
                self.assertIn(f"#ifndef {name}\n  #error {name} is required", INSTALLER)
        self.assertNotRegex(INSTALLER, r"#define AppVersion\b")

    def test_one_machine_identity_with_a_chinese_wizard_and_the_product_name(self):
        self.assertIn('#define Product "数艺工坊"', INSTALLER)
        self.assertIn('#define Identity "com.datacraft.lab"', INSTALLER)
        self.assertIn("PrivilegesRequired=admin", INSTALLER)
        self.assertIn("DefaultDirName={autopf}\\{#Folder}", INSTALLER)
        self.assertIn('MessagesFile: "{#ChineseMessages}"', INSTALLER)
        self.assertIn("UninstallDisplayName={#Product}", INSTALLER)
        self.assertIn("ArchitecturesInstallIn64BitMode=x64os", INSTALLER)

    def test_the_running_app_holds_the_mutex_the_installer_waits_for(self):
        mutex = re.search(r'MUTEX = "([^"]+)"', SINGLE_INSTANCE)
        self.assertIsNotNone(mutex)
        assert mutex is not None
        self.assertIn(f"AppMutex={mutex.group(1)}", INSTALLER)
        self.assertEqual(mutex.group(1), lifecycle.MUTEX)

    def test_a_downgrade_and_a_foreign_folder_are_refused_and_repairs_replace_the_program(self):
        self.assertIn("(Theirs > Mine)", INSTALLER)
        self.assertIn("不会降级", INSTALLER)
        self.assertIn("function FolderProblem(Directory: String): String;", INSTALLER)
        self.assertIn("Result := FolderProblem(ExpandConstant('{app}'));", INSTALLER)
        self.assertIn('Type: filesandordirs; Name: "{app}\\app"', INSTALLER)
        self.assertIn('Type: filesandordirs; Name: "{app}\\runtime"', INSTALLER)
        # Nothing outside {app} and the shortcuts: the data folder in the profile is never named.
        self.assertNotIn(".datacraft\"", INSTALLER)
        self.assertNotRegex(INSTALLER, r"(?mi)^Type: .*\{(?:userprofile|localappdata|appdata)\}")

    def test_the_fixture_installs_per_user_under_its_own_identity(self):
        fixture = INSTALLER[INSTALLER.index("#ifdef Fixture\nPrivilegesRequired"):]
        self.assertIn("PrivilegesRequired=lowest", fixture)
        layout = lifecycle.Layout("abc")
        self.assertEqual("com.datacraft.lab.fixture.abc", layout.identity)
        self.assertFalse(layout.machine)
        self.assertTrue(layout.key.endswith("\\com.datacraft.lab.fixture.abc_is1"))
        self.assertTrue(lifecycle.Layout().machine)


class BuilderTests(unittest.TestCase):
    def test_the_image_version_is_the_jar_its_launcher_starts(self):
        with tempfile.TemporaryDirectory() as directory:
            image = Path(directory) / "DatacraftLab"
            (image / "app").mkdir(parents=True)
            with self.assertRaises(builder.Missing):
                builder.image_version(image)
            (image / "DatacraftLab.exe").write_bytes(b"MZ")
            cfg = image / "app" / "DatacraftLab.cfg"
            cfg.write_text("[Application]\napp.classpath=$APPDIR\\datacraft-desktop-2.3.4.jar\n",
                           encoding="utf-8")
            self.assertEqual("2.3.4", builder.image_version(image))
            cfg.write_text("[Application]\napp.classpath=$APPDIR\\other.jar\n", encoding="utf-8")
            with self.assertRaises(builder.Failed):
                builder.image_version(image)

    def test_publishing_replaces_the_installer_whole(self):
        with tempfile.TemporaryDirectory() as directory:
            source, destination = Path(directory) / "new.exe", Path(directory) / "dist" / "Setup.exe"
            destination.parent.mkdir()
            destination.write_bytes(b"old")
            source.write_bytes(b"new")
            builder.publish(source, destination)
            self.assertEqual(b"new", destination.read_bytes())
            self.assertEqual(["Setup.exe"], sorted(path.name for path in destination.parent.iterdir()))

    def test_the_pinned_inputs_are_full_sha256_digests(self):
        for digest in (builder.CHINESE_MESSAGES_SHA256, inno.SHA256):
            self.assertRegex(digest, r"^[0-9a-f]{64}$")
        self.assertIn(inno.VERSION.replace(".", "_"), inno.URL)
        self.assertRegex(builder.CHINESE_MESSAGES_URL, r"/issrc/[0-9a-f]{40}/")


if __name__ == "__main__":
    unittest.main()
