#!/usr/bin/env bash
set -Eeuo pipefail

# EclipseのJavaプロジェクト内にある全ソースファイルをビルドする。
# 日本語のJavadocと文字列があるため、UTF-8を明示してコンパイルする。
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

if ! command -v javac >/dev/null 2>&1; then
  printf 'ERROR: javac not found. Install JDK 21 or later.\n' >&2
  exit 1
fi

mkdir -p bin
javac -encoding UTF-8 --release 21 -cp lib/lombok-1.18.48.jar \
  -processorpath lib/lombok-1.18.48.jar -d bin src/*.java src/constants/*.java src/entity/*.java
printf 'Build completed: bin/Vb6WinFormsConverter.class\n'
