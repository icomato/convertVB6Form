package entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* VB6フォーム定義に含まれるコントロールまたはBeginPropertyの解析結果を保持する。
*
* <p>親子階層と属性、元のTwips座標、WinForms向けPixel座標を別々に記録する。タブ所属と親コンテナは変換時に解決される。変換処理から参照するentityパッケージのデータ保持クラス。
*
* @author convertVB6Form contributors
* @version 1.0 (Javaコーディング規約対応)
* @since 1.0
*/
public final class Vb6FormNodeEntity {
	/** 要素の種別。controlまたはproperty。 */
	private String kind; // control, property
	/** 元のVB6型名と要素名。 */
	private String vbType;
	/** VB6でのコントロール名。 */
	private String name;
	/** VB6定義における親ノード。フォームの場合はnull。 */
	private Vb6FormNodeEntity parent;
	/** 子コントロールの一覧。 */
	private final List<Vb6FormNodeEntity> children = new ArrayList<>();
	/** VB6の属性値（元の文字列）。 */
	private final Map<String, String> props = new LinkedHashMap<>();
	/** Fontなどの入れ子プロパティ。 */
	private final Map<String, Vb6FormNodeEntity> nested = new HashMap<>();
	/** 元識別子、出力フィールド名、WinForms型、変換後の親名。 */
	private String id;
	/** 変換後に使用するVB.NETのフィールド名。 */
	private String field;
	/** 対応するWinFormsの型名。 */
	private String winType;
	/** タブを考慮して解決した親コンテナ名。 */
	private String resolvedParent = "";
	/** タブ番号。タブに所属しない場合は-1。 */
	private int tabIndex = -1;
	/** VB6の元座標とサイズ（Twips）。 */
	private int leftTwips;
	/** 元のTop位置（Twips）。 */
	private int topTwips;
	/** 元の幅（Twips）。 */
	private int widthTwips;
	/** 元の高さ（Twips）。 */
	private int heightTwips;
	/** 変換後の座標とサイズ（Pixel）。 */
	private int pixelLeft;
	/** 変換後のTop位置（Pixel）。 */
	private int pixelTop;
	/** 変換後の幅（Pixel）。 */
	private int pixelWidth;
	/** 変換後の高さ（Pixel）。 */
	private int pixelHeight;
	/** 変換先WinForms型が対応しているかどうか。 */
	private boolean supported = true;

	/**
	* フォーム定義の1要素を作成する。
	* @param kind 要素種別（control/property）
	* @param type VB6コントロール型。propertyの場合は空文字
	* @param name VB6での名称
	* @param parent 親ノード。最上位の場合はnull
	*/
	public Vb6FormNodeEntity(String kind, String type, String name, Vb6FormNodeEntity parent) {
		this.kind = kind;
		this.vbType = type;
		this.name = name;
		this.parent = parent;
	}

	/**
	* VB6の属性値を取得する。
	* @param key 属性名
	* @return 属性値。未設定ならnull
	*/
	public String getProperty(String key) {
		return props.get(key);
	}

	/**
	* 指定属性が存在するか調べる。
	* @param key 属性名
	* @return 属性が存在すればtrue
	*/
	public boolean hasProperty(String key) {
		return props.containsKey(key);
	}

	/**
	* 要素の種別（controlまたはproperty）を取得する。
	* @return 要素の種別（controlまたはproperty）。コレクションの場合は変換処理から変更可能な参照
	*/
	public String getKind() {
		return kind;
	}

	/**
	* 要素の種別（controlまたはproperty）を設定する。
	* @param value 新しい要素の種別（controlまたはproperty）
	*/
	public void setKind(String value) {
		this.kind = value;
	}

	/**
	* 元のVB6コントロール型を取得する。
	* @return 元のVB6コントロール型。コレクションの場合は変換処理から変更可能な参照
	*/
	public String getVbType() {
		return vbType;
	}

	/**
	* 元のVB6コントロール型を設定する。
	* @param value 新しい元のVB6コントロール型
	*/
	public void setVbType(String value) {
		this.vbType = value;
	}

	/**
	* 元のVB6コントロール名を取得する。
	* @return 元のVB6コントロール名。コレクションの場合は変換処理から変更可能な参照
	*/
	public String getName() {
		return name;
	}

	/**
	* 元のVB6コントロール名を設定する。
	* @param value 新しい元のVB6コントロール名
	*/
	public void setName(String value) {
		this.name = value;
	}

	/**
	* 元フォーム定義での親ノードを取得する。
	* @return 元フォーム定義での親ノード。コレクションの場合は変換処理から変更可能な参照
	*/
	public Vb6FormNodeEntity getParent() {
		return parent;
	}

	/**
	* 元フォーム定義での親ノードを設定する。
	* @param value 新しい元フォーム定義での親ノード
	*/
	public void setParent(Vb6FormNodeEntity value) {
		this.parent = value;
	}

	/**
	* 子コントロールの一覧を取得する。
	* @return 子コントロールの一覧。コレクションの場合は変換処理から変更可能な参照
	*/
	public List<Vb6FormNodeEntity> getChildren() {
		return children;
	}

	/**
	* VB6の通常属性の対応表を取得する。
	* @return VB6の通常属性の対応表。コレクションの場合は変換処理から変更可能な参照
	*/
	public Map<String, String> getProps() {
		return props;
	}

	/**
	* Fontなどの入れ子属性を取得する。
	* @return Fontなどの入れ子属性。コレクションの場合は変換処理から変更可能な参照
	*/
	public Map<String, Vb6FormNodeEntity> getNested() {
		return nested;
	}

	/**
	* VB6コントロールの識別子を取得する。
	* @return VB6コントロールの識別子。コレクションの場合は変換処理から変更可能な参照
	*/
	public String getId() {
		return id;
	}

	/**
	* VB6コントロールの識別子を設定する。
	* @param value 新しいVB6コントロールの識別子
	*/
	public void setId(String value) {
		this.id = value;
	}

	/**
	* 生成後のVB.NETフィールド名を取得する。
	* @return 生成後のVB.NETフィールド名。コレクションの場合は変換処理から変更可能な参照
	*/
	public String getField() {
		return field;
	}

	/**
	* 生成後のVB.NETフィールド名を設定する。
	* @param value 新しい生成後のVB.NETフィールド名
	*/
	public void setField(String value) {
		this.field = value;
	}

	/**
	* 対応するWinFormsのコントロール型を取得する。
	* @return 対応するWinFormsのコントロール型。コレクションの場合は変換処理から変更可能な参照
	*/
	public String getWinType() {
		return winType;
	}

	/**
	* 対応するWinFormsのコントロール型を設定する。
	* @param value 新しい対応するWinFormsのコントロール型
	*/
	public void setWinType(String value) {
		this.winType = value;
	}

	/**
	* TabPageへの配置を解決した親コンテナ名を取得する。
	* @return TabPageへの配置を解決した親コンテナ名。コレクションの場合は変換処理から変更可能な参照
	*/
	public String getResolvedParent() {
		return resolvedParent;
	}

	/**
	* TabPageへの配置を解決した親コンテナ名を設定する。
	* @param value 新しいTabPageへの配置を解決した親コンテナ名
	*/
	public void setResolvedParent(String value) {
		this.resolvedParent = value;
	}

	/**
	* 所属するタブの番号を取得する。
	* @return 所属するタブの番号。コレクションの場合は変換処理から変更可能な参照
	*/
	public int getTabIndex() {
		return tabIndex;
	}

	/**
	* 所属するタブの番号を設定する。
	* @param value 新しい所属するタブの番号
	*/
	public void setTabIndex(int value) {
		this.tabIndex = value;
	}

	/**
	* 元の左座標（Twips）を取得する。
	* @return 元の左座標（Twips）。コレクションの場合は変換処理から変更可能な参照
	*/
	public int getLeftTwips() {
		return leftTwips;
	}

	/**
	* 元の左座標（Twips）を設定する。
	* @param value 新しい元の左座標（Twips）
	*/
	public void setLeftTwips(int value) {
		this.leftTwips = value;
	}

	/**
	* 元の上座標（Twips）を取得する。
	* @return 元の上座標（Twips）。コレクションの場合は変換処理から変更可能な参照
	*/
	public int getTopTwips() {
		return topTwips;
	}

	/**
	* 元の上座標（Twips）を設定する。
	* @param value 新しい元の上座標（Twips）
	*/
	public void setTopTwips(int value) {
		this.topTwips = value;
	}

	/**
	* 元の幅（Twips）を取得する。
	* @return 元の幅（Twips）。コレクションの場合は変換処理から変更可能な参照
	*/
	public int getWidthTwips() {
		return widthTwips;
	}

	/**
	* 元の幅（Twips）を設定する。
	* @param value 新しい元の幅（Twips）
	*/
	public void setWidthTwips(int value) {
		this.widthTwips = value;
	}

	/**
	* 元の高さ（Twips）を取得する。
	* @return 元の高さ（Twips）。コレクションの場合は変換処理から変更可能な参照
	*/
	public int getHeightTwips() {
		return heightTwips;
	}

	/**
	* 元の高さ（Twips）を設定する。
	* @param value 新しい元の高さ（Twips）
	*/
	public void setHeightTwips(int value) {
		this.heightTwips = value;
	}

	/**
	* 変換した左座標（Pixel）を取得する。
	* @return 変換した左座標（Pixel）。コレクションの場合は変換処理から変更可能な参照
	*/
	public int getPixelLeft() {
		return pixelLeft;
	}

	/**
	* 変換した左座標（Pixel）を設定する。
	* @param value 新しい変換した左座標（Pixel）
	*/
	public void setPixelLeft(int value) {
		this.pixelLeft = value;
	}

	/**
	* 変換した上座標（Pixel）を取得する。
	* @return 変換した上座標（Pixel）。コレクションの場合は変換処理から変更可能な参照
	*/
	public int getPixelTop() {
		return pixelTop;
	}

	/**
	* 変換した上座標（Pixel）を設定する。
	* @param value 新しい変換した上座標（Pixel）
	*/
	public void setPixelTop(int value) {
		this.pixelTop = value;
	}

	/**
	* 変換した幅（Pixel）を取得する。
	* @return 変換した幅（Pixel）。コレクションの場合は変換処理から変更可能な参照
	*/
	public int getPixelWidth() {
		return pixelWidth;
	}

	/**
	* 変換した幅（Pixel）を設定する。
	* @param value 新しい変換した幅（Pixel）
	*/
	public void setPixelWidth(int value) {
		this.pixelWidth = value;
	}

	/**
	* 変換した高さ（Pixel）を取得する。
	* @return 変換した高さ（Pixel）。コレクションの場合は変換処理から変更可能な参照
	*/
	public int getPixelHeight() {
		return pixelHeight;
	}

	/**
	* 変換した高さ（Pixel）を設定する。
	* @param value 新しい変換した高さ（Pixel）
	*/
	public void setPixelHeight(int value) {
		this.pixelHeight = value;
	}

	/**
	* WinFormsへ対応付け可能かどうかを取得する。
	* @return WinFormsへ対応付け可能かどうか。コレクションの場合は変換処理から変更可能な参照
	*/
	public boolean isSupported() {
		return supported;
	}

	/**
	* WinFormsへ対応付け可能かどうかを設定する。
	* @param value 新しいWinFormsへ対応付け可能かどうか
	*/
	public void setSupported(boolean value) {
		this.supported = value;
	}
}
