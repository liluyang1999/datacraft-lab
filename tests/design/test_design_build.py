"""数艺工坊's design tokens are the only source of the interface's colours and scales.

design/ui/build.py writes the desktop themes and the prototype's token CSS from
design/ui/tokens.json and checks base.css against the scales. These tests pin that contract: the
committed outputs are current, malformed tokens are refused, and drift in base.css is reported.
Standard library only; runs on Windows and Linux.
"""

import importlib.util
import json
from pathlib import Path
import re
import shutil
import tempfile
import unittest

REPOSITORY = Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "design" / "ui" / "build.py"
_spec = importlib.util.spec_from_file_location("design_build", SCRIPT)
assert _spec is not None and _spec.loader is not None, SCRIPT
design = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(design)


def contrast(foreground, background):
    def luminance(colour):
        channels = [int(colour[index:index + 2], 16) / 255 for index in (1, 3, 5)]
        linear = [value / 12.92 if value <= 0.03928 else ((value + 0.055) / 1.055) ** 2.4
                  for value in channels]
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]

    lighter, darker = sorted((luminance(foreground), luminance(background)), reverse=True)
    return (lighter + 0.05) / (darker + 0.05)


class GeneratedOutputTests(unittest.TestCase):
    def test_the_committed_themes_and_prototype_tokens_are_current(self):
        tokens = design.load(REPOSITORY)
        for path, text in design.outputs(REPOSITORY, tokens).items():
            with self.subTest(file=path.relative_to(REPOSITORY).as_posix()):
                self.assertEqual(text, path.read_text(encoding="utf-8"),
                                 "run python3 -B design/ui/build.py")

    def test_base_css_stays_on_the_scales_and_uses_only_defined_tokens(self):
        self.assertEqual([], design.contract_problems(REPOSITORY, design.load(REPOSITORY)))

    def test_each_theme_defines_every_token_under_its_own_selector(self):
        tokens = design.load(REPOSITORY)
        names = [name for group in tokens["colors"] for name in group["tokens"]]
        light = design.javafx_theme(tokens, "light")
        dark = design.javafx_theme(tokens, "dark")
        self.assertIn("\n.root {\n", light)
        self.assertIn("\n.root:dark {\n", dark)
        for name in names:
            self.assertEqual(1, light.count(f"    -dc-{name}: "), name)
            self.assertEqual(1, dark.count(f"    -dc-{name}: "), name)
        web = design.web_tokens(tokens)
        self.assertIn(':root[data-theme="dark"] {', web)
        self.assertIn('@media (prefers-color-scheme: dark) {', web)
        self.assertIn("--dc-radius-tile: 12px;", web)

    def test_the_badge_and_state_text_pairs_are_readable_in_both_themes(self):
        tokens = design.load(REPOSITORY)
        table = [{name: values[index] for group in tokens["colors"]
                  for name, values in group["tokens"].items()} for index in (0, 1)]
        pairs = [("on-badge", "badge"), ("success-text", "success-wash"),
                 ("failed-text", "failed-wash"), ("warning-text", "warning-wash"),
                 ("text", "surface"), ("text-secondary", "bg-top")]
        for theme, colours in zip(("light", "dark"), table):
            for foreground, background in pairs:
                with self.subTest(theme=theme, pair=(foreground, background)):
                    self.assertGreaterEqual(
                        contrast(colours[foreground], colours[background]), 4.5)


class ContractTests(unittest.TestCase):
    def scratch(self, tokens=None, base=None, java="class A {}"):
        root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, root, True)
        (root / "design/ui").mkdir(parents=True)
        source = json.loads((REPOSITORY / design.TOKENS).read_text(encoding="utf-8"))
        if tokens:
            tokens(source)
        (root / design.TOKENS).write_text(json.dumps(source), encoding="utf-8")
        (root / design.THEME_DIR).mkdir(parents=True)
        used = " ".join(f"-dc-{name}" for group in source["colors"] for name in group["tokens"])
        (root / design.BASE_CSS).write_text(base if base is not None else f"/* {used} */\n",
                                            encoding="utf-8")
        (root / design.JAVA_SOURCES).mkdir(parents=True)
        (root / design.JAVA_SOURCES / "A.java").write_text(java, encoding="utf-8")
        return root

    def test_a_token_without_both_themes_or_with_a_loose_colour_is_refused(self):
        def single(source):
            source["colors"][0]["tokens"]["surface"] = ["#FFFFFF"]

        def loose(source):
            source["colors"][0]["tokens"]["surface"] = ["white", "#000000"]

        def dangling(source):
            source["modena"]["tokens"]["-fx-base"] = ["@missing", "@surface"]

        for change in (single, loose, dangling):
            with self.subTest(change=change.__name__), self.assertRaises(design.DesignError):
                design.load(self.scratch(change))

    def test_drift_in_base_css_is_reported_with_its_line(self):
        base = (".a {\n    -fx-font-size: 14px;\n    -fx-background-radius: 7;\n"
                "    -fx-padding: 3 4 3 4;\n    -fx-text-fill: -dc-unknown;\n}\n")
        root = self.scratch(base=base)
        problems = design.contract_problems(root, design.load(root))
        self.assertTrue(any(re.search(r":2: font size 14px", p) for p in problems), problems)
        self.assertTrue(any(re.search(r":3: radius 7", p) for p in problems), problems)
        self.assertTrue(any(re.search(r":4: spacing 3", p) for p in problems), problems)
        self.assertTrue(any("-dc-unknown" in p for p in problems), problems)
        # Every defined token is now unused: base.css names none of them.
        self.assertTrue(any("is used neither" in p for p in problems), problems)

    def test_write_mode_writes_the_outputs_and_check_mode_then_passes(self):
        root = self.scratch()
        self.assertEqual(1, design.main(["--check", "--root", str(root)]))
        self.assertEqual(0, design.main(["--root", str(root)]))
        self.assertEqual(0, design.main(["--check", "--root", str(root)]))
        self.assertTrue((root / design.WEB_TOKENS).is_file())


if __name__ == "__main__":
    unittest.main()
