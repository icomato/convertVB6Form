#!/usr/bin/env bash
set -Eeuo pipefail

# Java 21ビルドと基本的なVB6フォーム変換を検査する（コンパイル時は同梱Lombokを使用）。
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."

bash build.sh
# Lombokを実行時クラスパスに含めず、循環参照・同一性・生成アクセサも検証する。
java -ea -cp bin tests/EntityBehaviorTest.java
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

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp bin Vb6WinFormsConverter \
    "$work_dir/Basic.frm" "$work_dir/output" >"$work_dir/stdout.txt" 2>"$work_dir/stderr.txt"

# 完了メッセージが標準出力とUTF-8ログの両方にあり、標準エラーには重複しないこと。
test "$(wc -l < "$work_dir/stdout.txt")" -eq 7
test ! -s "$work_dir/stderr.txt"
while IFS= read -r message; do
    grep -Fq -- "${message%$'\r'}" "$work_dir/output/convertVB6Form.log"
done < "$work_dir/stdout.txt"
grep -Fq 'コントロール:' "$work_dir/output/convertVB6Form.log"
grep -Fq '<Project Path="Basic_Migrated.vbproj" />' "$work_dir/output/Basic_Migrated.slnx"

# ユーザーが編集した既存ソリューションは、再実行しても上書きしないこと。
printf '\n<!-- user customization -->\n' >> "$work_dir/output/Basic_Migrated.slnx"
cp "$work_dir/output/Basic_Migrated.slnx" "$work_dir/expected.slnx"

# 再実行ではログを追記し、ハンドラを閉じた後にロックファイルが残らないこと。
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp bin Vb6WinFormsConverter \
    "$work_dir/Basic.frm" "$work_dir/output" >"$work_dir/stdout-second.txt" 2>"$work_dir/stderr-second.txt"
test ! -s "$work_dir/stderr-second.txt"
test "$(grep -Fc 'コントロール:' "$work_dir/output/convertVB6Form.log")" -eq 2
test ! -e "$work_dir/output/convertVB6Form.log.lck"
cmp "$work_dir/expected.slnx" "$work_dir/output/Basic_Migrated.slnx"

grep -Fq 'Me.Label1.Location = New System.Drawing.Point(10, 20)' \
    "$work_dir/output/Basic.Designer.vb"
grep -Fq 'Me.Label1.AutoSize = True' \
    "$work_dir/output/Basic.Designer.vb"
grep -Fq 'Me.Label1.Text = "Hello"' \
    "$work_dir/output/Basic.Designer.vb"
test -s "$work_dir/output/Basic_coordinate_audit.csv"
test -s "$work_dir/output/Basic_migration_report.txt"

# 実行フォルダ基準の相対パスでsample/inputを探し、HowToと終了コードを確認する。
classes_dir="$(pwd)/bin"
mkdir -p "$work_dir/default-run"
run_default() {
    (
        cd "$work_dir/default-run"
        java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
            -cp "$classes_dir" Vb6WinFormsConverter "$@"
    ) >"$work_dir/default-stdout.txt" 2>"$work_dir/default-stderr.txt"
}
expect_howto() {
    if run_default "$@"; then
        printf 'Expected HowTo error.\n' >&2
        exit 1
    else
        test "$?" -eq 2
    fi
    test ! -s "$work_dir/default-stdout.txt"
    grep -Fq 'HowTo ERROR:' "$work_dir/default-stderr.txt"
    grep -Fq 'java -cp bin Vb6WinFormsConverter' "$work_dir/default-stderr.txt"
    if grep -Fq 'Exception' "$work_dir/default-stderr.txt"; then
        cat "$work_dir/default-stderr.txt"
        exit 1
    fi
}
expect_howto
test ! -e "$work_dir/default-run/sample/output"
mkdir -p "$work_dir/default-run/sample/input/ignored.frm"
printf 'not a form\n' > "$work_dir/default-run/sample/input/readme.txt"
# 旧配置のsample直下の.frmは、入力候補として選択しない。
cp "$work_dir/Basic.frm" "$work_dir/default-run/sample/Legacy.frm"
expect_howto
test ! -e "$work_dir/default-run/sample/output"
cp "$work_dir/Basic.frm" "$work_dir/default-run/sample/input/Basic.FRM"
run_default
test ! -s "$work_dir/default-stderr.txt"
test -s "$work_dir/default-run/sample/output/Basic_Migrated.slnx"
test ! -e "$work_dir/default-run/output"
cp "$work_dir/Basic.frm" "$work_dir/default-run/sample/input/Other.frm"
run_default
test ! -s "$work_dir/default-stderr.txt"
combined="$work_dir/default-run/sample/output/combined"
test -s "$combined/VB6Migrated_Migrated.slnx"
test -s "$combined/VB6Migrated_Migrated.vbproj"
test "$(find "$combined" -maxdepth 1 -name '*.vbproj' | wc -l)" -eq 1
test "$(find "$combined" -maxdepth 1 -name '*.slnx' | wc -l)" -eq 1
grep -Fq '入力: Basic.FRM' "$combined/Basic_migration_report.txt"
grep -Fq '入力: Other.frm' "$combined/Basic_2_migration_report.txt"
grep -Fq '<DependentUpon>Basic_2.vb</DependentUpon>' "$combined/VB6Migrated_Migrated.vbproj"
grep -Fq 'Application.Run(New Basic())' "$combined/Program.vb"
expect_howto only-one-argument

printf 'Smoke test passed.\n'
