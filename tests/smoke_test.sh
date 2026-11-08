#!/usr/bin/env bash
set -Eeuo pipefail

# Java 17ビルドと基本的なVB6フォーム変換を検査する（外部ライブラリ不要）。
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

bash build.sh
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

cat > "$work_dir/Basic.frm" <<'FORM'
VERSION 5.00
Begin VB.Form Basic
   Caption = "Test Form"
   ClientWidth = 3000
   ClientHeight = 1800
   Begin VB.Label Label1
      Caption = "Hello"
      Left = 150
      Top = 300
      Width = 900
      Height = 300
   End
End
Attribute VB_Name = "Basic"
FORM

java -Dfile.encoding=UTF-8 -cp bin Vb6WinFormsConverter \
    "$work_dir/Basic.frm" "$work_dir/output" >/dev/null

grep -Fq 'Me.Label1.Location = New System.Drawing.Point(10, 20)' \
    "$work_dir/output/Basic.Designer.vb"
grep -Fq 'Me.Label1.AutoSize = True' \
    "$work_dir/output/Basic.Designer.vb"
grep -Fq 'Me.Label1.Text = "Hello"' \
    "$work_dir/output/Basic.Designer.vb"
test -s "$work_dir/output/Basic_coordinate_audit.csv"
test -s "$work_dir/output/Basic_migration_report.txt"

printf 'Smoke test passed.\n'
