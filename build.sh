#!/usr/bin/env bash
set -Eeuo pipefail

# Build the Eclipse Java project with JDK 17+.
# Explicit UTF-8 prevents Japanese comments/strings from failing on ASCII locales.
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

if ! command -v javac >/dev/null 2>&1; then
  printf 'ERROR: javac not found. Install JDK 17 or later.\n' >&2
  exit 1
fi

mkdir -p bin
javac -encoding UTF-8 --release 17 -d bin src/Vb6WinFormsConverter.java
printf 'Build completed: bin/Vb6WinFormsConverter.class\n'
