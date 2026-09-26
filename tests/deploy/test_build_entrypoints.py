"""Every build entry point must use the Maven version the checksum-pinned wrapper pins."""

import os
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
WRAPPER_PROPERTIES = ROOT / ".mvn" / "wrapper" / "maven-wrapper.properties"


def wrapper_properties():
    lines = WRAPPER_PROPERTIES.read_text(encoding="utf-8").splitlines()
    return dict(line.split("=", 1) for line in lines if line and not line.startswith("#"))


class BuildEntrypointTests(unittest.TestCase):
    def test_makefile_defaults_to_wrapper(self):
        makefile = (ROOT / "Makefile").read_text(encoding="utf-8")
        self.assertRegex(makefile, re.compile(r"^MVN \?= \./mvnw$", re.MULTILINE))

    def test_builder_image_uses_wrapper_maven_version(self):
        match = re.search(r"apache-maven-(\d+\.\d+\.\d+)-bin\.zip$",
                          wrapper_properties()["distributionUrl"])
        if match is None:
            self.fail("distributionUrl must name an apache-maven-X.Y.Z-bin.zip")
        dockerfile = (ROOT / "deploy" / "docker" / "Dockerfile.build").read_text(encoding="utf-8")
        image = re.search(r"^ARG MAVEN_IMAGE=(\S+)$", dockerfile, re.MULTILINE)
        if image is None:
            self.fail("Dockerfile.build must declare an ARG MAVEN_IMAGE default")
        self.assertTrue(image.group(1).startswith(f"maven:{match.group(1)}-eclipse-temurin-25"),
                        f"{image.group(1)} does not match wrapper Maven {match.group(1)}")

    def test_build_scripts_prefer_the_pinned_wrapper(self):
        shell = (ROOT / "deploy" / "scripts" / "build-jar.sh").read_text(encoding="utf-8")
        self.assertLess(shell.index("./mvnw -B"), shell.index("  mvn -B"))
        windows = (ROOT / "deploy" / "scripts" / "build.ps1").read_text(encoding="utf-8")
        self.assertLess(windows.index("mvnw.cmd"), windows.index("Get-Command mvn"))
        self.assertIn("Maven not found", windows)
        self.assertIn("$LASTEXITCODE", windows)
        for script in (shell, windows):
            self.assertIn("-pl :datacraft-cli", script, "select the module by artifactId, not path")

    def test_wrapper_is_script_only_and_checksum_pinned(self):
        properties = wrapper_properties()
        self.assertRegex(properties.get("wrapperVersion", ""), r"^3\.")
        self.assertEqual("only-script", properties.get("distributionType"))
        self.assertRegex(properties.get("distributionSha256Sum", ""), r"^[0-9a-f]{64}$")
        self.assertNotIn("wrapperUrl", properties)
        self.assertFalse((ROOT / ".mvn" / "wrapper" / "maven-wrapper.jar").exists())

    def test_unix_wrapper_is_runnable(self):
        mvnw = ROOT / "mvnw"
        self.assertTrue(mvnw.read_bytes().startswith(b"#!/bin/sh\n"))
        self.assertNotIn(b"\r", mvnw.read_bytes())
        if os.name == "posix":
            self.assertTrue(os.access(mvnw, os.X_OK), "mvnw lost its executable bit")


if __name__ == "__main__":
    unittest.main()
