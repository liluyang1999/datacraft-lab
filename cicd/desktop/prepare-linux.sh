#!/usr/bin/env bash
# Ubuntu CI needs Chinese glyphs and a working accessibility bus, not disabled accessibility.
set -euo pipefail

sudo apt-get update
sudo apt-get install --yes --no-install-recommends at-spi2-core dbus-daemon fonts-noto-cjk
