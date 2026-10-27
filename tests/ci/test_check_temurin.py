"""The Temurin check reads the pin, compares it with Adoptium and never passes unasked.

No test reaches the network: Adoptium's answers are stubbed with the shapes its API returns.
"""

import contextlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock

REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "cicd" / "security" / "check_temurin.py"

_spec = importlib.util.spec_from_file_location("check_temurin", SCRIPT)
assert _spec is not None and _spec.loader is not None, SCRIPT
temurin = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(temurin)

CHECKSUMS = {("jre", "x64"): "a" * 64, ("jre", "aarch64"): "b" * 64,
             ("jdk", "x64"): "c" * 64, ("jdk", "aarch64"): "d" * 64}
DOCKERFILE = f"""ARG TARGETARCH
ARG BASE_IMAGE
ARG TEMURIN_VERSION=25.0.5+8
ARG TEMURIN_FILE=25.0.5_8
ARG TEMURIN_JRE_AMD64_SHA256={CHECKSUMS[("jre", "x64")]}
ARG TEMURIN_JRE_ARM64_SHA256={CHECKSUMS[("jre", "aarch64")]}
ARG TEMURIN_JDK_AMD64_SHA256={CHECKSUMS[("jdk", "x64")]}
ARG TEMURIN_JDK_ARM64_SHA256={CHECKSUMS[("jdk", "aarch64")]}
"""


def adoptium(newest="jdk-25.0.5+8", checksums=None, failing=None):
    """A stand-in for Adoptium's API: the newest GA release name and the assets of a release."""
    checksums = {**CHECKSUMS, **(checksums or {})}

    def answer(path):
        if failing and failing in path:
            raise temurin.LookupFailed(f"Adoptium is not reachable for {path}")
        if path.startswith("/info/release_names"):
            return {"releases": [newest, "jdk-25.0.4.1+1"]}
        kind = "jre" if "image_type=jre" in path else "jdk"
        arch = "aarch64" if "architecture=aarch64" in path else "x64"
        name = f"OpenJDK25U-{kind}_{arch}_linux_hotspot_25.0.5_8.tar.gz"
        return [{"release_name": "jdk-25.0.5+8",
                 "binaries": [{"package": {"name": name, "checksum": checksums[(kind, arch)]}}]}]
    return answer


class CheckTemurinTests(unittest.TestCase):
    def tree(self, dockerfile=DOCKERFILE):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        (root / "deploy" / "docker").mkdir(parents=True)
        (root / "deploy" / "docker" / "Dockerfile.temurin").write_text(dockerfile, encoding="utf-8")
        return root

    def run_main(self, root, answer):
        output = io.StringIO()
        with mock.patch.object(temurin, "call", side_effect=answer), contextlib.redirect_stdout(output):
            status = temurin.main([str(root)])
        return status, output.getvalue()

    def test_a_current_pin_with_the_published_checksums_passes(self):
        status, output = self.run_main(self.tree(), adoptium())
        self.assertEqual(0, status, output)
        self.assertIn("Temurin 25.0.5+8 is the newest Temurin 25 release", output)

    def test_a_newer_release_fails_the_check(self):
        status, output = self.run_main(self.tree(), adoptium(newest="jdk-25.0.6+7"))
        self.assertEqual(1, status, output)
        self.assertIn("::error::Temurin jdk-25.0.6+7 is out; deploy/docker/Dockerfile.temurin pins 25.0.5+8", output)

    def test_a_checksum_that_differs_from_the_published_one_fails(self):
        status, output = self.run_main(self.tree(), adoptium(checksums={("jdk", "aarch64"): "e" * 64}))
        self.assertEqual(1, status, output)
        self.assertIn("::error::TEMURIN_JDK_ARM64_SHA256", output)
        self.assertIn("OpenJDK25U-jdk_aarch64_linux_hotspot_25.0.5_8.tar.gz", output)

    def test_an_unreachable_adoptium_is_not_a_pass(self):
        status, output = self.run_main(self.tree(), adoptium(failing="image_type=jre"))
        self.assertEqual(2, status, output)
        self.assertIn("::error::", output)
        self.assertNotIn("newest", output.split("::error::")[0])

    def test_a_pin_that_cannot_be_read_is_an_error(self):
        for broken in (DOCKERFILE.replace("ARG TEMURIN_FILE=25.0.5_8\n", ""),
                       DOCKERFILE.replace("ARG TEMURIN_FILE=25.0.5_8", "ARG TEMURIN_FILE=25.0.5_9"),
                       DOCKERFILE.replace("a" * 64, "a" * 63)):
            with self.subTest(dockerfile=broken[:120]):
                status, output = self.run_main(self.tree(broken), adoptium())
                self.assertEqual(2, status, output)
                self.assertIn("::error::cannot read the Temurin pin", output)

    def test_the_repository_pin_is_read(self):
        pin = temurin.pin(REPOSITORY)
        self.assertRegex(pin.version, r"^25\.\d+\.\d+(\.\d+)?\+\d+$")
        self.assertEqual(4, len(pin.checksums))


if __name__ == "__main__":
    unittest.main()
