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

# 命名等の違反と分離し、スペース・混在を検出しタブを許容することを確認する。
printf 'class TabsOnly {\n\tint value;\n}\n' > "$work_dir/TabsOnly.java"
printf 'class Spaces {\n    int value;\n}\n' > "$work_dir/Spaces.java"
printf 'class Mixed {\n\t int value;\n}\n' > "$work_dir/Mixed.java"
lint -o "$work_dir/tabs.xml" "$work_dir/TabsOnly.java"
if grep -q '\.RegexpSinglelineCheck' "$work_dir/tabs.xml"; then
    cat "$work_dir/tabs.xml"
    exit 1
fi
for fixture in Spaces Mixed; do
    lint -o "$work_dir/$fixture.xml" "$work_dir/$fixture.java"
    grep -q '\.RegexpSinglelineCheck' "$work_dir/$fixture.xml"
done
# if・通常for・拡張for・else if・ネストと、コメント直後の空行を確認する。
cat > "$work_dir/BlankLines.java" <<'JAVA'
class BlankLines {
    void run() {

        if (true) {

            for (int i = 0; i < 1; i++) {
                // 確認用コメント。

                if (i == 0) {
                    System.out.println("if (文字列は対象外)");
                }
            }

        } else if (false) {
            System.out.println("else");
        }

        for (int value : new int[0]) {
            System.out.println(value);
        }
    }
}
JAVA
lint -o "$work_dir/blank-lines.xml" "$work_dir/BlankLines.java"
if grep -q '\.RegexpMultilineCheck' "$work_dir/blank-lines.xml"; then
    cat "$work_dir/blank-lines.xml"
    exit 1
fi
# 空行を各1箇所だけ取り除き、5箇所すべてで検出することを確認する。
for line in 3 5 8 13 17; do
    sed "${line}d" "$work_dir/BlankLines.java" > "$work_dir/MissingBlank.java"
    lint -o "$work_dir/missing-blank.xml" "$work_dir/MissingBlank.java"
    grep -q '\.RegexpMultilineCheck' "$work_dir/missing-blank.xml"
done
# メソッド間・コンストラクタ後の空行（Javadoc・アノテーションあり）を検査する。
cat > "$work_dir/MethodSpacing.java" <<'JAVA'
class MethodSpacing {
    MethodSpacing() {
    }

    /** 最初のメソッド。 */
    void first() {
    }

    /** 次のメソッド。 */
    @Deprecated
    void second() {
    }
}
JAVA
lint -o "$work_dir/method-spacing.xml" "$work_dir/MethodSpacing.java"
if grep -q '\.EmptyLineSeparatorCheck' "$work_dir/method-spacing.xml"; then
    cat "$work_dir/method-spacing.xml"
    exit 1
fi
for line in 4 8; do
    sed "${line}d" "$work_dir/MethodSpacing.java" > "$work_dir/MissingMethodSpace.java"
    lint -o "$work_dir/missing-method-space.xml" "$work_dir/MissingMethodSpace.java"
    grep -q '\.EmptyLineSeparatorCheck' "$work_dir/missing-method-space.xml"
done
printf 'Checkstyle test passed (sources clean; invalid fixture detected).\n'
