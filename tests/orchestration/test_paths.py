"""Real worker filesystem boundaries, runnable with only the Python standard library."""

from pathlib import Path
import sys
import tempfile
import unittest

DAGS = Path(__file__).resolve().parents[2] / "orchestration" / "airflow" / "dags"
sys.path.insert(0, str(DAGS))
from datacraft_paths import download_destination


class DownloadPathTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="datacraft-sftp-path-")
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / "data"
        self.outside = self.base / "outside"
        self.root.mkdir()
        self.outside.mkdir()
        self.sentinel = self.outside / "victim.csv"
        self.sentinel.write_bytes(b"unchanged outside data")

    def test_existing_parent_symlink_cannot_redirect_a_download_outside(self):
        link = self.root / "linked"
        link.symlink_to(self.outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "outside DATACRAFT_DATA_ROOT"):
            download_destination(str(link / "victim.csv"), str(self.root))
        self.assertEqual(b"unchanged outside data", self.sentinel.read_bytes())

    def test_existing_destination_symlink_cannot_overwrite_an_outside_file(self):
        link = self.root / "input.csv"
        link.symlink_to(self.sentinel)
        with self.assertRaisesRegex(ValueError, "outside DATACRAFT_DATA_ROOT"):
            download_destination(str(link), str(self.root))
        self.assertEqual(b"unchanged outside data", self.sentinel.read_bytes())

    def test_missing_directories_remain_uncreated_until_download(self):
        destination = self.root / "new" / "nested" / "input.csv"
        self.assertEqual(destination, download_destination(str(destination), str(self.root)))
        self.assertFalse(destination.parent.exists())

    def test_links_within_the_root_return_their_canonical_target(self):
        target = self.root / "existing"
        target.mkdir()
        link = self.root / "linked"
        link.symlink_to(target, target_is_directory=True)
        self.assertEqual(target / "input.csv", download_destination(str(link / "input.csv"), str(self.root)))

    def test_aliases_of_the_root_and_unresolvable_links_are_refused(self):
        root_link = self.root / "root-link"
        root_link.symlink_to(self.root, target_is_directory=True)
        loop = self.root / "loop"
        loop.symlink_to(loop)
        for path in (root_link, loop / "input.csv"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                download_destination(str(path), str(self.root))

    def test_relative_outside_and_missing_root_paths_are_refused(self):
        for path in ("input.csv", str(self.root), str(self.sentinel), str(self.root / ".." / "outside" / "victim.csv")):
            with self.subTest(path=path), self.assertRaises(ValueError):
                download_destination(path, str(self.root))
        with self.assertRaises(ValueError):
            download_destination(str(self.base / "missing" / "input.csv"), str(self.base / "missing"))


if __name__ == "__main__":
    unittest.main()
