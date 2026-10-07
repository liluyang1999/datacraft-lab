"""Build settings whose mistakes surface far from the POM, checked here without running Maven.

The builder image packages with -Dmaven.test.skip=true in a Docker build context that has no tests/.
That only works while Surefire keeps the default of its `skip` parameter and scalatest-maven-plugin,
which knows only `skipTests`, is switched off by the same flag. cicd/build/check-image-build.sh
proves it with Maven; these tests catch the regression in the scripts job, and on any machine.

Standard library only, like the other suites here: the XML parsed is this repository's own POMs.
"""

from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

REPOSITORY = Path(__file__).resolve().parents[2]
SUREFIRE = "maven-surefire-plugin"
SCALATEST = "scalatest-maven-plugin"
# Surefire parameters that decide whether tests run; an explicit value replaces the user property.
SKIP_PARAMETERS = ("skip", "skipTests", "skipExec")


def pom(path):
    return ET.parse(path).getroot()


def plugins(project, *location):
    """{artifactId: <plugin>} below project/<location>/plugins."""
    path = "/".join(f"{{*}}{name}" for name in (*location, "plugins", "plugin"))
    return {plugin.findtext("{*}artifactId"): plugin for plugin in project.findall(path)}


def configuration(plugin):
    """{parameter: text} of a plugin's own <configuration>, without its executions."""
    node = plugin.find("{*}configuration")
    return {} if node is None else {child.tag.rpartition("}")[2]: (child.text or "").strip()
                                    for child in node}


def module_poms():
    root = pom(REPOSITORY / "pom.xml")
    modules = [(module.text or "").strip() for module in root.findall("{*}modules/{*}module")]
    return {module: pom(REPOSITORY / module / "pom.xml") for module in modules}


class TestSkipFlagTests(unittest.TestCase):
    def setUp(self):
        self.root = pom(REPOSITORY / "pom.xml")

    def test_root_surefire_configuration_leaves_the_skip_parameters_at_their_defaults(self):
        managed = plugins(self.root, "build", "pluginManagement")
        self.assertIn(SUREFIRE, managed)
        for plugin in (managed[SUREFIRE], plugins(self.root, "build").get(SUREFIRE)):
            if plugin is not None:
                explicit = sorted(set(configuration(plugin)) & set(SKIP_PARAMETERS))
                self.assertEqual([], explicit, "an explicit value overrides -Dmaven.test.skip")

    def test_only_the_scalatest_modules_skip_surefire_and_only_with_a_literal_true(self):
        modules = module_poms()
        self.assertTrue(modules)
        scalatest_only = []
        for module, project in modules.items():
            declared = plugins(project, "build")
            skip = configuration(declared[SUREFIRE]).get("skip") if SUREFIRE in declared else None
            with self.subTest(module=module):
                if SCALATEST in declared:
                    scalatest_only.append(module)
                    # A literal true can only skip; a property could resolve to false.
                    self.assertEqual("true", skip)
                else:
                    self.assertIsNone(skip, "a module with JUnit tests must not skip Surefire")
        self.assertTrue(scalatest_only, "no module declares scalatest-maven-plugin")

    def test_maven_test_skip_also_switches_scalatest_off(self):
        switching = []
        for profile in self.root.findall("{*}profiles/{*}profile"):
            activation = profile.find("{*}activation/{*}property")
            if activation is not None and activation.findtext("{*}name") == "maven.test.skip":
                switching.append((activation.findtext("{*}value"),
                                  profile.findtext("{*}properties/{*}skipTests")))
        self.assertEqual([("true", "true")], switching,
                         "one profile must map maven.test.skip=true to skipTests=true")


if __name__ == "__main__":
    unittest.main()
