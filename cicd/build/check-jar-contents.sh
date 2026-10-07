#!/usr/bin/env bash
# Checks how the shaded jar is packaged:
# - the manifest declares Multi-Release, because JSch keeps its Ed25519/X25519/ML-KEM classes under
#   META-INF/versions and the JVM loads them only from a Multi-Release jar;
# - no bundled module-info.class poses as the fat jar's module descriptor;
# - JSch's modern algorithms work from the jar itself (JschAlgorithmsProbe);
# - the jar bundles exactly the jackson-core and jackson-databind that pom.xml's jackson.version
#   manages, not an older copy shaded in from another dependency.
# Run after the jar is built; CLI_JAR overrides its path.
set -euo pipefail
# shellcheck source=cicd/lib.sh
. "$(dirname "$0")/../lib.sh"
cd "$REPO_ROOT"

jar=$CLI_JAR
[ -f "$jar" ] || fail "CLI jar missing at $jar"

manifest=$(unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r') || fail "cannot read the manifest of $jar"
echo "$manifest"
grep -qx 'Multi-Release: true' <<<"$manifest" || fail "$jar manifest lacks 'Multi-Release: true'"
# Listed first: in a pipeline, a failing unzip would make the grep below find nothing and pass.
entries=$(unzip -Z1 "$jar") || fail "cannot list the entries of $jar"
if grep -E '(^|/)module-info\.class$' <<<"$entries"; then
  fail "$jar must not contain module-info.class entries"
fi
java -cp "$jar" cicd/build/JschAlgorithmsProbe.java

expected=$(sed -n 's:^[[:space:]]*<jackson\.version>\([^<]*\)</jackson\.version>[[:space:]]*$:\1:p' pom.xml)
if [ -z "$expected" ] || [ "$(wc -l <<<"$expected")" -ne 1 ]; then
  fail "pom.xml must define exactly one jackson.version property"
fi
for artifact in jackson-core jackson-databind; do
  # A jar without the entry leaves the version empty, which the comparison below reports.
  actual=$(unzip -p "$jar" "META-INF/maven/com.fasterxml.jackson.core/$artifact/pom.properties" \
    2> /dev/null | tr -d '\r' | sed -n 's/^version=//p') || actual=
  echo "$artifact ${actual:-<missing>} (pom.xml jackson.version $expected)"
  [ "$actual" = "$expected" ] || fail "$jar bundles $artifact ${actual:-<missing>}, expected $expected"
done
