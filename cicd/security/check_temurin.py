"""Fails when the pinned Temurin release is not the newest one, or a pinned checksum is wrong.

deploy/docker/Dockerfile.temurin pins the Java every image runs: a Temurin 25 release and the
SHA-256 of its JRE and JDK archives for amd64 and arm64. A pin stays in place after a newer
release fixes vulnerabilities, so this check asks Adoptium's API (https://api.adoptium.net):

- which Temurin 25 GA release is the newest; a newer one than the pin fails the check;
- which checksums Adoptium publishes for the four archives of the pinned release; one that
  differs from the pin fails the check (BuildKit would refuse that archive at build time).

The advisories workflow runs it every Monday and on a push that changes the pin. Standard
library only.

Usage: python3 -B cicd/security/check_temurin.py [REPOSITORY_ROOT]
Exit status: 0 when the pin is current and correct, 1 when it is not, 2 when the pin cannot be
read or Adoptium could not be asked.
"""

import argparse
from dataclasses import dataclass
import json
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.adoptium.net/v3"
DOCKERFILE = "deploy/docker/Dockerfile.temurin"
# (image type, Adoptium architecture) -> the build argument that pins its SHA-256.
ARCHIVES = {("jre", "x64"): "TEMURIN_JRE_AMD64_SHA256", ("jre", "aarch64"): "TEMURIN_JRE_ARM64_SHA256",
            ("jdk", "x64"): "TEMURIN_JDK_AMD64_SHA256", ("jdk", "aarch64"): "TEMURIN_JDK_ARM64_SHA256"}


class LookupFailed(Exception):
    """Adoptium could not be asked, so nothing is known about the pin."""


@dataclass(frozen=True)
class Pin:
    version: str
    file: str
    checksums: dict


def pin(root):
    """The pin of Dockerfile.temurin; a missing or inconsistent value is a ValueError."""
    text = (Path(root) / DOCKERFILE).read_text(encoding="utf-8")
    args = dict(re.findall(r"(?m)^ARG (\w+)=(\S+)$", text))
    version, file = args.get("TEMURIN_VERSION", ""), args.get("TEMURIN_FILE", "")
    if not re.fullmatch(r"25\.\d+\.\d+(\.\d+)?\+\d+", version):
        raise ValueError(f"{DOCKERFILE}: TEMURIN_VERSION is missing or not a Temurin 25 release")
    if file != version.replace("+", "_"):
        raise ValueError(f"{DOCKERFILE}: TEMURIN_FILE must be TEMURIN_VERSION with _ for +")
    checksums = {}
    for archive, name in ARCHIVES.items():
        value = args.get(name, "")
        if not re.fullmatch(r"[0-9a-f]{64}", value):
            raise ValueError(f"{DOCKERFILE}: {name} is missing or not a SHA-256")
        checksums[archive] = (name, value)
    return Pin(version, file, checksums)


def call(path):
    """One Adoptium request, retried while the connection fails; raises LookupFailed in the end."""
    request = urllib.request.Request(API + path, headers={"User-Agent": "datacraft-lab-temurin-check"})
    failure = None
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code < 500:
                raise LookupFailed(f"Adoptium answered HTTP {error.code} for {path}") from None
            failure = error
        except (urllib.error.URLError, TimeoutError, ConnectionError, json.JSONDecodeError) as error:
            failure = error
        time.sleep(2 * (attempt + 1))
    raise LookupFailed(f"Adoptium is not reachable: {failure}")


def problems(current):
    """What is wrong with the pin, as messages; empty when it is current and correct."""
    found = []
    releases = call("/info/release_names?release_type=ga&version=%5B25%2C26%29&vendor=eclipse"
                    "&sort_order=DESC&sort_method=DEFAULT&page=0&page_size=1").get("releases", [])
    if not releases:
        raise LookupFailed("Adoptium listed no Temurin 25 release")
    newest = releases[0]
    if newest != f"jdk-{current.version}":
        found.append(f"Temurin {newest} is out; {DOCKERFILE} pins {current.version}")
    release = urllib.parse.quote(f"jdk-{current.version}")
    for (kind, arch), (name, pinned) in current.checksums.items():
        expected = f"OpenJDK25U-{kind}_{arch}_linux_hotspot_{current.file}.tar.gz"
        assets = call(f"/assets/release_name/eclipse/{release}?os=linux&architecture={arch}"
                      f"&image_type={kind}&jvm_impl=hotspot&heap_size=normal&project=jdk")
        packages = [binary["package"] for asset in (assets if isinstance(assets, list) else [assets])
                    for binary in asset.get("binaries", []) if binary.get("package", {}).get("name") == expected]
        if not packages:
            raise LookupFailed(f"Adoptium lists no {expected}")
        if packages[0].get("checksum") != pinned:
            found.append(f"{name} in {DOCKERFILE} is {pinned}, but Adoptium publishes "
                         f"{packages[0].get('checksum')} for {expected}")
    return found


def main(argv=None):
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n", 1)[0])
    parser.add_argument("root", nargs="?", default=".", help="repository root (default: .)")
    args = parser.parse_args(argv)
    try:
        current = pin(args.root)
    except (OSError, ValueError) as error:
        print(f"::error::cannot read the Temurin pin: {error}")
        return 2
    try:
        found = problems(current)
    except LookupFailed as error:
        print(f"::error::{error}; the Temurin pin {current.version} was not checked")
        return 2
    for message in found:
        print(f"::error::{message}")
    if found:
        return 1
    print(f"Temurin {current.version} is the newest Temurin 25 release, and its four pinned checksums"
          " are the ones Adoptium publishes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
