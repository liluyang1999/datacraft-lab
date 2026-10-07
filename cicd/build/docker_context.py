"""The Docker build context of this repository, computed without Docker.

`docker build .` sends the repository minus what .dockerignore excludes, so a builder image only
sees part of the checkout (no tests/, docs/, design/ or cicd/). The functions here apply
.dockerignore with the rules of Docker's pattern matcher, which lets the checks in this directory
and the tests in tests/deploy reason about, and build in, exactly the files an image build sees.

Usage:
  python3 -B cicd/build/docker_context.py copy DESTINATION
      Copy the build context of the repository into DESTINATION (which must not exist or be empty).
  python3 -B cicd/build/docker_context.py maven-args DOCKERFILE
      Print the Maven arguments of DOCKERFILE's packaging command, one per line.
Exit status: 0 on success, 1 on a usage or content error.
"""

import argparse
import os
from pathlib import Path
import posixpath
import re
import shlex
import shutil
import sys

REPOSITORY = Path(__file__).resolve().parents[2]


def dockerignore_patterns(text):
    """Parse .dockerignore like Docker (moby/patternmatcher): Clean, strip a leading '/', keep '!'.

    Returns (exclude, pattern) pairs in file order; exclude is False for a '!' re-inclusion."""
    patterns = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        exclude = not line.startswith("!")
        pattern = posixpath.normpath(line.lstrip("!").strip())
        if len(pattern) > 1 and pattern.startswith("/"):
            pattern = pattern[1:]
        patterns.append((exclude, pattern))
    return patterns


def pattern_regex(pattern):
    out, index = "^", 0
    while index < len(pattern):
        if pattern.startswith("**", index):
            index += 3 if pattern.startswith("**/", index) else 2
            out += ".*" if index >= len(pattern) else "(.*/)?"
        elif pattern[index] == "*":
            out, index = out + "[^/]*", index + 1
        elif pattern[index] == "?":
            out, index = out + "[^/]", index + 1
        else:
            out, index = out + re.escape(pattern[index]), index + 1
    return re.compile(out + "$")


def is_excluded(patterns, path):
    """The last matching pattern wins; a pattern also matches a path through any parent directory."""
    excluded, parts = False, path.split("/")
    parents = ["/".join(parts[:depth]) for depth in range(1, len(parts))]
    for exclude, pattern in patterns:
        regex = pattern_regex(pattern)
        if regex.match(path) or any(regex.match(parent) for parent in parents):
            excluded = exclude
    return excluded


def may_reinclude_below(patterns, directory):
    """Whether a '!' pattern can match a path below directory. A re-inclusion that starts with a
    wildcard can match anywhere; any other one only below its own literal leading directories."""
    for exclude, pattern in patterns:
        if exclude:
            continue
        literal = re.split(r"[*?\[]", pattern, maxsplit=1)[0]
        if literal != pattern and "/" not in literal:
            return True
        parent = literal if literal == pattern else literal.rsplit("/", 1)[0]
        if (parent + "/").startswith(directory + "/") or (directory + "/").startswith(parent + "/"):
            return True
    return False


def context_files(root):
    """Repository-relative POSIX paths of the files `docker build <root>` would send, sorted."""
    root = Path(root)
    ignore = root / ".dockerignore"
    patterns = dockerignore_patterns(ignore.read_text(encoding="utf-8")) if ignore.is_file() else []
    files = []
    for directory, subdirectories, names in os.walk(root):
        relative = Path(directory).relative_to(root).as_posix()
        prefix = "" if relative == "." else relative + "/"
        # An excluded directory is skipped whole unless a '!' pattern may bring a child back.
        subdirectories[:] = [name for name in subdirectories
                             if not is_excluded(patterns, prefix + name)
                             or may_reinclude_below(patterns, prefix + name)]
        files.extend(prefix + name for name in names if not is_excluded(patterns, prefix + name))
    return sorted(files)


def copy_context(root, destination):
    """Copies the build context of root into destination and returns the number of files copied."""
    root, destination = Path(root).resolve(), Path(destination).resolve()
    if destination == root or root in destination.parents:
        raise ValueError(f"{destination} is inside {root}; the copy would land in its own context")
    if destination.exists() and any(destination.iterdir()):
        raise ValueError(f"{destination} is not empty")
    files = context_files(root)
    for name in files:
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(root / name, target)
    return len(files)


def maven_args(dockerfile_text):
    """Arguments of the one `RUN mvn ... package ...` command in a Dockerfile, without `mvn`."""
    joined = re.sub(r"\\\r?\n", " ", dockerfile_text)
    commands = []
    for line in joined.splitlines():
        if not line.startswith("RUN "):
            continue
        for command in line[len("RUN "):].split("&&"):
            words = shlex.split(command)
            if words[:1] == ["mvn"] and "package" in words:
                commands.append(words[1:])
    if len(commands) != 1:
        raise ValueError(f"expected one 'RUN mvn ... package' command, found {len(commands)}")
    return commands[0]


def main(argv=None):
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n", 1)[0])
    actions = parser.add_subparsers(dest="action", required=True)
    copy = actions.add_parser("copy", help="copy the Docker build context")
    copy.add_argument("destination")
    copy.add_argument("--root", default=str(REPOSITORY), help="repository root (default: this one)")
    args = actions.add_parser("maven-args", help="print the Dockerfile's Maven packaging arguments")
    args.add_argument("dockerfile")
    options = parser.parse_args(argv)
    try:
        if options.action == "copy":
            count = copy_context(options.root, options.destination)
            print(f"copied {count} build-context files to {options.destination}")
        else:
            text = Path(options.dockerfile).read_text(encoding="utf-8")
            print("\n".join(maven_args(text)))
    except (OSError, ValueError) as error:
        print(f"::error::{error}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
