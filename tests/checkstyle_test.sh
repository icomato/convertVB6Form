#!/usr/bin/env bash
set -Eeuo pipefail

# 実行にはJava 21以上とCheckstyle 14.0.0（依存同梱JAR推奨）が必要。
# CHECKSTYLE_CLASSPATHは依存ライブラリを含むクラスパスでも指定可能。
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
: "${CHECKSTYLE_CLASSPATH:?Checkstyle 14.0.0のJARの絶対パスを指定してください}"
work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT

lint() {
    java -Duser.language=en -cp "$CHECKSTYLE_CLASSPATH" \
        com.puppycrawl.tools.checkstyle.Main -c checkstyle.xml -f xml "$@"
}

# warningはCLIの終了コードを失敗にしないので、XMLの違反要素も確認する。
lint -o "$work_dir/sources.xml" src
if grep -q '<error ' "$work_dir/sources.xml"; then
    cat "$work_dir/sources.xml"
    exit 1
fi

cat > "$work_dir/Bad.java" <<'JAVA'
import java.util.*;
class bad {
    public int BadField;
    class Inner { }
    void BadMethod(int BadParameter) {
        class Local { }
        Object value = new Object() { };
        if (true) System.out.println(value);
    }
}
class Extra { }
/** タグ不足の検知用クラス。 */
class TagBad {
    /** タグ不足の検知用メソッド。 */
    int value(int input) {
        return input;
    }
}
JAVA
lint -o "$work_dir/bad.xml" "$work_dir/Bad.java"
for rule in TypeName MemberName ParameterName MethodName AvoidStarImport \
    VisibilityModifier NeedBraces MissingJavadocType MissingJavadocMethod JavadocType JavadocMethod \
    OneTopLevelClass OuterTypeFilename MatchXpath; do
    grep -q "\.${rule}Check" "$work_dir/bad.xml" || {
        printf 'Expected violation missing: %s\n' "$rule"
        cat "$work_dir/bad.xml"
        exit 1
    }
done
# 内部・ローカル・匿名クラスの3種類すべてを検出する。
test "$(grep -c '\.MatchXpathCheck' "$work_dir/bad.xml")" -eq 3
printf 'Checkstyle test passed (sources clean; invalid fixture detected).\n'
