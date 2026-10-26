"""The checks that the API's data mount is read-only can fail.

The API runs as uid 10001 and the volume's directory belongs to airflow:0 with mode 0775, so `touch`
fails with EACCES on a writable mount as well: on a real engine (2026-10-26) `! touch` passed for a
writable mount. Only the mount options tell the two apart, so each smoke test reads them from
/proc/self/mountinfo inside the API container, as the Compose smoke test already did.
"""

from pathlib import Path
import re
import unittest

IMAGES = Path(__file__).resolve().parents[2] / "cicd" / "images"
# The awk program each check runs: exit 0 only when the first mount option of the data root is ro.
PROGRAM = '$5 == "/opt/datacraft/data" { split($6, options, ","); ro = options[1] == "ro" } END { exit !ro }'


class ReadOnlyMountProbeTests(unittest.TestCase):
    def test_every_api_mount_check_reads_the_mount_options(self):
        for name in ("smoke-images.sh", "smoke-compose.sh", "smoke-swarm.sh"):
            text = (IMAGES / name).read_text(encoding="utf-8")
            with self.subTest(script=name):
                self.assertIn(PROGRAM, text)
                self.assertIn("/proc/self/mountinfo", text)
                self.assertIsNone(re.search(r"!\s*touch\s+/opt/datacraft/data/", text))


if __name__ == "__main__":
    unittest.main()
