package entity;

import lombok.Data;
import lombok.ToString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
* VB6フォーム定義に含まれるコントロールまたはBeginPropertyの解析結果を保持する。
*
* <p>親子階層と属性、元のTwips座標、
* WinForms向けPixel座標を別々に記録する。
* タブ所属と親コンテナは変換時に解決される。
* 変換処理から参照するentityパッケージのデータ保持クラス。
*
* @author convertVB6Form contributors
* @version 1.0 (Javaコーディング規約対応)
* @since 1.0
*/
@Data
@ToString(onlyExplicitlyIncluded = true)
public final class Vb6FormNodeEntity {
	/**
	* 要素の種別。
	* controlまたはproperty。
	*/
	private String kind; // control, property
	/** 元のVB6型名と要素名。 */
	private String vbType;
	/** VB6でのコントロール名。 */
	@ToString.Include
	private String name;
	/**
	* VB6定義における親ノード。
	* フォームの場合はnull。
	*/
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
	/**
	* タブ番号。
	* タブに所属しない場合は-1。
	*/
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
	* @param type VB6コントロール型。
	* propertyの場合は空文字
	* @param name VB6での名称
	* @param parent 親ノード。
	* 最上位の場合はnull
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
	* @return 属性値。
	* 未設定ならnull
	*/
	public String getProperty(String key) {
		// 指定された属性名の値を返し、未設定の場合はnullを返す。
		return props.get(key);
	}

	/**
	* 指定属性が存在するか調べる。
	* @param key 属性名
	* @return 属性が存在すればtrue
	*/
	public boolean hasProperty(String key) {
		// 属性名が登録されているかを返し、値が空でも存在を判定できるようにする。
		return props.containsKey(key);
	}

	/**
	* 可変の解析ノードは値で比較せず、同じインスタンスかどうかで判定する。
	* @param other 比較するオブジェクト
	* @return 同じインスタンスの場合だけtrue
	*/
	@Override
	public boolean equals(Object other) {
		// 属性の値では比較せず、同じインスタンスである場合だけ真を返す。
		return this == other;
	}

	/**
	* 属性変更や親子の循環参照に影響されない、従来どおりのハッシュ値を返す。
	* @return インスタンスの識別用ハッシュ値
	*/
	@Override
	public int hashCode() {
		// 属性の変更や循環参照に影響されない、インスタンス単位のハッシュ値を返す。
		return System.identityHashCode(this);
	}
}
