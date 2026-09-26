#!/usr/bin/env bash
# The script-only Maven Wrapper (no committed jar) must pin the SHA-256 of the Maven distribution and
# refuse a download that does not match it. The wrapper checks only fresh downloads and reuses an
# existing wrapper/dists directory unchecked, so the negative run gets its own empty MAVEN_USER_HOME.
# Needs network access: the wrapper downloads the distribution before it rejects it.
set -euo pipefail
# shellcheck source=cicd/lib.sh
. "$(dirname "$0")/../lib.sh"
cd "$REPO_ROOT"

props=.mvn/wrapper/maven-wrapper.properties
grep -Eq '^distributionSha256Sum=[0-9a-f]{64}$' "$props" || fail "$props must pin distributionSha256Sum"
[ ! -e .mvn/wrapper/maven-wrapper.jar ] || fail ".mvn/wrapper/maven-wrapper.jar must not be committed"

work=$(scratch_dir mvnw-wrong-checksum)
trap 'rm -rf -- "$work"' EXIT
cp -p mvnw "$work/"
cp -R .mvn "$work/"
sed -i 's/^distributionSha256Sum=.*/distributionSha256Sum=0000000000000000000000000000000000000000000000000000000000000000/' \
  "$work/.mvn/wrapper/maven-wrapper.properties"

rc=0
(cd "$work" && MAVEN_USER_HOME="$work/m2" ./mvnw -v) > "$work/output.txt" 2>&1 || rc=$?
cat "$work/output.txt"
[ "$rc" -ne 0 ] || fail "mvnw ran a Maven distribution with the wrong SHA-256"
grep -q 'Failed to validate Maven distribution SHA-256' "$work/output.txt" \
  || fail "mvnw failed, but not on the SHA-256 check"
echo "mvnw refused a distribution with the wrong SHA-256"
