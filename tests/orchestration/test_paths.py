"""Real worker filesystem boundaries, runnable with only the Python standard library."""

import errno
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

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

    def test_dangling_links_respect_the_canonical_root(self):
        for name, target in (("inside", self.root / "missing" / "input.csv"),
                             ("outside", self.outside / "missing" / "input.csv")):
            link = self.root / name
            link.symlink_to(target)
            with self.subTest(target=target):
                if target.is_relative_to(self.root):
                    self.assertEqual(target, download_destination(str(link), str(self.root)))
                else:
                    with self.assertRaisesRegex(ValueError, "outside DATACRAFT_DATA_ROOT"):
                        download_destination(str(link), str(self.root))
                self.assertFalse(target.parent.exists())

    def test_file_parents_are_not_treated_as_missing_directories(self):
        parent = self.root / "file.csv"
        parent.write_bytes(b"unchanged file")
        with self.assertRaisesRegex(ValueError, "cannot be resolved"):
            download_destination(str(parent / "input.csv"), str(self.root))
        self.assertEqual(b"unchanged file", parent.read_bytes())

    def test_stat_errors_are_not_treated_as_missing_destinations(self):
        destination = self.root / "input.csv"
        real_stat = Path.stat
        for error in (PermissionError(errno.EACCES, "permission denied"),
                      OSError(errno.EIO, "input/output error"), RuntimeError("unresolvable path")):
            def fail_destination_stat(path, *args, **kwargs):
                if path == destination:
                    raise error
                return real_stat(path, *args, **kwargs)

            with self.subTest(error=error), patch.object(Path, "stat", fail_destination_stat):
                with self.assertRaisesRegex(ValueError, "cannot be resolved") as caught:
                    download_destination(str(destination), str(self.root))
                self.assertIs(error, caught.exception.__cause__)

    def test_aliases_of_the_root_and_unresolvable_links_are_refused(self):
        root_link = self.root / "root-link"
        root_link.symlink_to(self.root, target_is_directory=True)
        loop = self.root / "loop"
        loop.symlink_to(loop)
        for path in (root_link, loop / "input.csv"):
            with self.subTest(path=path), self.assertRaises(ValueError):
                download_destination(str(path), str(self.root))

        real_resolve = Path.resolve

        def non_strict_loop_resolution(path, strict=False):
            if path == loop / "input.csv" and not strict:
                return path
            return real_resolve(path, strict=strict)

        # Python 3.13 leaves loops unresolved in non-strict mode. Keep this boundary covered on 3.12.
        with patch.object(Path, "resolve", non_strict_loop_resolution):
            with self.assertRaisesRegex(ValueError, "cannot be resolved"):
                download_destination(str(loop / "input.csv"), str(self.root))

    def test_relative_outside_and_missing_root_paths_are_refused(self):
        for path in ("input.csv", str(self.root), str(self.sentinel), str(self.root / ".." / "outside" / "victim.csv")):
            with self.subTest(path=path), self.assertRaises(ValueError):
                download_destination(path, str(self.root))
        with self.assertRaises(ValueError):
            download_destination(str(self.base / "missing" / "input.csv"), str(self.base / "missing"))


if __name__ == "__main__":
    unittest.main()
