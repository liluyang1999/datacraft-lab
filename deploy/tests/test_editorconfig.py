""".editorconfig must agree with the formatters, make and .gitattributes that the build enforces."""

import configparser
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


def editorconfig():
    parser = configparser.ConfigParser(interpolation=None)
    # Top-level keys such as `root = true` sit before the first section.
    parser.read_string("[preamble]\n" + (ROOT / ".editorconfig").read_text(encoding="utf-8"))
    return parser


class EditorConfigTests(unittest.TestCase):
    def test_overrides_match_enforced_tools(self):
        config = editorconfig()
        expected = {"*.{java,scala}": ("indent_size", "2"),
                    "Makefile": ("indent_style", "tab"),
                    "*.{cmd,bat}": ("end_of_line", "crlf")}
        sections = config.sections()
        for section, (key, value) in expected.items():
            with self.subTest(section=section):
                self.assertIn(section, sections)
                self.assertEqual(value, config[section].get(key))
                # EditorConfig applies later sections last, so an override must follow [*].
                self.assertGreater(sections.index(section), sections.index("*"))

    def test_makefile_recipes_are_tab_indented(self):
        lines = (ROOT / "Makefile").read_text(encoding="utf-8").splitlines()
        indented = [(number, line) for number, line in enumerate(lines, 1)
                    if line[:1].isspace() and line.strip() and not line.lstrip().startswith("#")]
        self.assertTrue(indented, "Makefile has no recipe lines")
        for number, line in indented:
            with self.subTest(line=number):
                self.assertTrue(line.startswith("\t"), f"Makefile:{number} is not tab-indented")


if __name__ == "__main__":
    unittest.main()
