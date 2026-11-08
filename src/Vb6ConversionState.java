import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1つのVB6フォームに対する変換処理で共有する解析・変換状態。
 *
 * <p>解析したコントロール、識別子の重複管理、SSTab情報、DPI、警告と集計値を集約する。状態はフォーム単位で作成し、静的なグローバル状態を持たない。
 */
final class Vb6ConversionState {
    /** ルートのVB.Form。 */
    Vb6FormNode form;
    /** VB6に定義されたコントロール一覧。 */
    final List<Vb6FormNode> all = new ArrayList<>();
    /** 変換時に判明した警告の一覧。 */
    final List<String> warnings = new ArrayList<>();
    /** 識別子からノードへの参照表。 */
    final Map<String, Vb6FormNode> byId = new LinkedHashMap<>();
    /** SSTabノードとタブ情報の対応表。 */
    final Map<Vb6FormNode, Vb6TabInfo> tabs = new IdentityHashMap<>();
    /** 生成済みフィールド名の集合。 */
    final Set<String> usedFields = new HashSet<>();
    /** 座標変換に使用するDPI。初期値は96。 */
    double dpi = 96.0;
    /** タブ所属を推定した件数。 */
    int unknownTabCount;
    /** SSTabの負座標を補正した件数。 */
    int remappedTabCount;
    /** WinFormsに出力する表示コントロール数。 */
    int visualCount;
    /** 未対応で省略したコントロール数。 */
    int ignoredCount;
    /** 出力するVB.NETフォームのクラス名。 */
    String className;
    /** 元フォームに含まれるVB6コード。参考保存用で実行コードには変換しない。 */
    String vb6Code = "";
    /**
     * 未対応・推定・要確認の警告を移行レポート用に記録する。
     * @param s WARN:を付ける前の警告文
     */
    void warn(String s) { warnings.add("WARN: " + s); }
}
