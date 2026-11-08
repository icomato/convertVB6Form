import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * VB6フォーム定義に含まれるコントロールまたはBeginPropertyの解析結果を保持する。
 *
 * <p>親子階層と属性、元のTwips座標、WinForms向けPixel座標を別々に記録する。タブ所属と親コンテナは変換時に解決される。外部には公開しないパッケージ内データクラス。
 */
final class Vb6FormNode {
    /** 要素の種別。controlまたはproperty。 */
    String kind; // control, property
    /** 元のVB6型名と要素名。 */
    String vbType, name;
    /** VB6定義における親ノード。フォームの場合はnull。 */
    Vb6FormNode parent;
    /** 子コントロールの一覧。 */
    final List<Vb6FormNode> children = new ArrayList<>();
    /** VB6の属性値（元の文字列）。 */
    final Map<String, String> props = new LinkedHashMap<>();
    /** Fontなどの入れ子プロパティ。 */
    final Map<String, Vb6FormNode> nested = new HashMap<>();
    /** 元識別子、出力フィールド名、WinForms型、変換後の親名。 */
    String id, field, winType, resolvedParent = "";
    /** タブ番号。タブに所属しない場合は-1。 */
    int tabIndex = -1;
    /** VB6の元座標とサイズ（Twips）。 */
    int leftTwips, topTwips, widthTwips, heightTwips;
    /** 変換後の座標とサイズ（Pixel）。 */
    int pixelLeft, pixelTop, pixelWidth, pixelHeight;
    /** 変換先WinForms型が対応しているかどうか。 */
    boolean supported = true;
    /**
     * フォーム定義の1要素を作成する。
     * @param kind 要素種別（control/property）
     * @param type VB6コントロール型。propertyの場合は空文字
     * @param name VB6での名称
     * @param parent 親ノード。最上位の場合はnull
     */
    Vb6FormNode(String kind, String type, String name, Vb6FormNode parent) {
        this.kind = kind; this.vbType = type; this.name = name; this.parent = parent;
    }
    /**
     * VB6の属性値を取得する。
     * @param key 属性名
     * @return 属性値。未設定ならnull
     */
    String prop(String key) { return props.get(key); }
    /**
     * 指定属性が存在するか調べる。
     * @param key 属性名
     * @return 属性が存在すればtrue
     */
    boolean has(String key) { return props.containsKey(key); }
}
