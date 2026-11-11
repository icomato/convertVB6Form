package entity;

import lombok.Data;
import lombok.ToString;

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
* <p>解析したコントロール、識別子の重複管理、SSTab情報、DPI、
* 警告と集計値を集約する。
* 状態はフォーム単位で作成し、静的なグローバル状態を持たない。
*
* @author convertVB6Form contributors
* @version 1.0 (Javaコーディング規約対応)
* @since 1.0
*/
@Data
@ToString(onlyExplicitlyIncluded = true)
public final class Vb6ConversionStateEntity {
	/** ルートのVB.Form。 */
	private Vb6FormNodeEntity form;

	/** VB6に定義されたコントロール一覧。 */
	private final List<Vb6FormNodeEntity> all = new ArrayList<>();

	/** 変換時に判明した警告の一覧。 */
	private final List<String> warnings = new ArrayList<>();

	/** 識別子からノードへの参照表。 */
	private final Map<String, Vb6FormNodeEntity> byId = new LinkedHashMap<>();

	/** SSTabノードとタブ情報の対応表。 */
	private final Map<Vb6FormNodeEntity, Vb6TabInfoEntity> tabs = new IdentityHashMap<>();

	/** 生成済みフィールド名の集合。 */
	private final Set<String> usedFields = new HashSet<>();

	/**
	* 座標変換に使用するDPI。
	* 初期値は96。
	*/
	private double dpi = 96.0;

	/** タブ所属を推定した件数。 */
	private int unknownTabCount;

	/** SSTabの負座標を補正した件数。 */
	private int remappedTabCount;

	/** WinFormsに出力する表示コントロール数。 */
	private int visualCount;

	/** 未対応で省略したコントロール数。 */
	private int ignoredCount;

	/** 出力するVB.NETフォームのクラス名。 */
	@ToString.Include
	private String className;

	/**
	* 元フォームに含まれるVB6コード。
	* 参考保存用で実行コードには変換しない。
	*/
	private String vb6Code = "";

	/**
	* 1フォーム分の解析・変換状態を初期値で作成する。
	*/
	public Vb6ConversionStateEntity() {
	}

	/**
	* 未対応・推定・要確認の警告を移行レポート用に記録する。
	* @param s WARN:を付ける前の警告文
	*/
	public void addWarning(String s) {
		warnings.add("WARN: " + s);
	}

	/** 所属タブを推定した件数を1増加させる。 */
	public void incrementUnknownTabCount() {
		unknownTabCount++;
	}

	/** 非選択タブの座標を補正した件数を1増加させる。 */
	public void incrementRemappedTabCount() {
		remappedTabCount++;
	}

	/** 出力した表示コントロール数を1増加させる。 */
	public void incrementVisualCount() {
		visualCount++;
	}

	/** 非対応として省略した件数を1増加させる。 */
	public void incrementIgnoredCount() {
		ignoredCount++;
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
