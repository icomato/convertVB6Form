#!/usr/bin/env bash
set -Eeuo pipefail

cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

if ! command -v javac >/dev/null 2>&1; then
  printf 'ERROR: javac not found. Install JDK 17 or later.\n' >&2
  exit 1
fi

# Java source contains UTF-8 Japanese strings/comments.
# Do not rely on the system locale (which may default to US-ASCII).
javac -encoding UTF-8 Vb6WinFormsConverter.java
printf 'Build completed: Vb6WinFormsConverter.class\n'
