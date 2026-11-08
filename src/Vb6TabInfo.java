import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * VB6のSSTabからTabControl/TabPageへの変換に必要な情報を保持する。
 *
 * <p>タブ数と見出しの高さ、Tab(n).Control(m)で指定された明示所属、生成するTabPageを管理する。所属推定は変換本体が行う。
 */
final class Vb6TabInfo {
    /** SSTabコントロール本体。 */
    Vb6FormNode tab;
    /** タブの枚数。 */
    int count;
    /** SSTab見出しの高さ（Twips）。 */
    int tabHeightTwips;
    /** 元コントロール識別子と所属タブ番号の対応表。 */
    final Map<String, Integer> assignments = new HashMap<>();
    /** 生成するTabPageのノード一覧。 */
    final List<Vb6FormNode> pages = new ArrayList<>();
}
