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
*
* @author convertVB6Form contributors
* @version 1.0 (Javaコーディング規約対応)
* @since 1.0
*/
final class Vb6ConversionState {
	/** ルートのVB.Form。 */
	private Vb6FormNode form;
	/** VB6に定義されたコントロール一覧。 */
	private final List<Vb6FormNode> all = new ArrayList<>();
	/** 変換時に判明した警告の一覧。 */
	private final List<String> warnings = new ArrayList<>();
	/** 識別子からノードへの参照表。 */
	private final Map<String, Vb6FormNode> byId = new LinkedHashMap<>();
	/** SSTabノードとタブ情報の対応表。 */
	private final Map<Vb6FormNode, Vb6TabInfo> tabs = new IdentityHashMap<>();
	/** 生成済みフィールド名の集合。 */
	private final Set<String> usedFields = new HashSet<>();
	/** 座標変換に使用するDPI。初期値は96。 */
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
	private String className;
	/** 元フォームに含まれるVB6コード。参考保存用で実行コードには変換しない。 */
	private String vb6Code = "";
	/**
	* 未対応・推定・要確認の警告を移行レポート用に記録する。
	* @param s WARN:を付ける前の警告文
	*/
	void addWarning(String s) {
		warnings.add("WARN: " + s);
	}

	/**
	* ルートのVB.Formノードを取得する。
	* @return ルートのVB.Formノード。コレクションの場合はパッケージ内で変更可能な参照
	*/
	Vb6FormNode getForm() {
		return form;
	}

	/**
	* ルートのVB.Formノードを設定する。
	* @param value 新しいルートのVB.Formノード
	*/
	void setForm(Vb6FormNode value) {
		this.form = value;
	}

	/**
	* 解析した全コントロールを取得する。
	* @return 解析した全コントロール。コレクションの場合はパッケージ内で変更可能な参照
	*/
	List<Vb6FormNode> getAll() {
		return all;
	}

	/**
	* 変換時に発生した警告の一覧を取得する。
	* @return 変換時に発生した警告の一覧。コレクションの場合はパッケージ内で変更可能な参照
	*/
	List<String> getWarnings() {
		return warnings;
	}

	/**
	* 元識別子からノードを引く対応表を取得する。
	* @return 元識別子からノードを引く対応表。コレクションの場合はパッケージ内で変更可能な参照
	*/
	Map<String, Vb6FormNode> getById() {
		return byId;
	}

	/**
	* SSTabごとの所属情報を取得する。
	* @return SSTabごとの所属情報。コレクションの場合はパッケージ内で変更可能な参照
	*/
	Map<Vb6FormNode, Vb6TabInfo> getTabs() {
		return tabs;
	}

	/**
	* 既に使用したフィールド名の集合を取得する。
	* @return 既に使用したフィールド名の集合。コレクションの場合はパッケージ内で変更可能な参照
	*/
	Set<String> getUsedFields() {
		return usedFields;
	}

	/**
	* 座標換算に使用するDPIを取得する。
	* @return 座標換算に使用するDPI。コレクションの場合はパッケージ内で変更可能な参照
	*/
	double getDpi() {
		return dpi;
	}

	/**
	* 座標換算に使用するDPIを設定する。
	* @param value 新しい座標換算に使用するDPI
	*/
	void setDpi(double value) {
		this.dpi = value;
	}

	/**
	* 所属タブを推定した件数を取得する。
	* @return 所属タブを推定した件数。コレクションの場合はパッケージ内で変更可能な参照
	*/
	int getUnknownTabCount() {
		return unknownTabCount;
	}

	/**
	* 所属タブを推定した件数を設定する。
	* @param value 新しい所属タブを推定した件数
	*/
	void setUnknownTabCount(int value) {
		this.unknownTabCount = value;
	}

	/** 所属タブを推定した件数を1増加させる。 */
	void incrementUnknownTabCount() {
		unknownTabCount++;
	}

	/**
	* 非選択タブの座標を補正した件数を取得する。
	* @return 非選択タブの座標を補正した件数。コレクションの場合はパッケージ内で変更可能な参照
	*/
	int getRemappedTabCount() {
		return remappedTabCount;
	}

	/**
	* 非選択タブの座標を補正した件数を設定する。
	* @param value 新しい非選択タブの座標を補正した件数
	*/
	void setRemappedTabCount(int value) {
		this.remappedTabCount = value;
	}

	/** 非選択タブの座標を補正した件数を1増加させる。 */
	void incrementRemappedTabCount() {
		remappedTabCount++;
	}

	/**
	* 出力した表示コントロール数を取得する。
	* @return 出力した表示コントロール数。コレクションの場合はパッケージ内で変更可能な参照
	*/
	int getVisualCount() {
		return visualCount;
	}

	/**
	* 出力した表示コントロール数を設定する。
	* @param value 新しい出力した表示コントロール数
	*/
	void setVisualCount(int value) {
		this.visualCount = value;
	}

	/** 出力した表示コントロール数を1増加させる。 */
	void incrementVisualCount() {
		visualCount++;
	}

	/**
	* 非対応として省略した件数を取得する。
	* @return 非対応として省略した件数。コレクションの場合はパッケージ内で変更可能な参照
	*/
	int getIgnoredCount() {
		return ignoredCount;
	}

	/**
	* 非対応として省略した件数を設定する。
	* @param value 新しい非対応として省略した件数
	*/
	void setIgnoredCount(int value) {
		this.ignoredCount = value;
	}

	/** 非対応として省略した件数を1増加させる。 */
	void incrementIgnoredCount() {
		ignoredCount++;
	}

	/**
	* 出力するVB.NETフォームのクラス名を取得する。
	* @return 出力するVB.NETフォームのクラス名。コレクションの場合はパッケージ内で変更可能な参照
	*/
	String getClassName() {
		return className;
	}

	/**
	* 出力するVB.NETフォームのクラス名を設定する。
	* @param value 新しい出力するVB.NETフォームのクラス名
	*/
	void setClassName(String value) {
		this.className = value;
	}

	/**
	* 元の.frmに含まれるVB6コードを取得する。
	* @return 元の.frmに含まれるVB6コード。コレクションの場合はパッケージ内で変更可能な参照
	*/
	String getVb6Code() {
		return vb6Code;
	}

	/**
	* 元の.frmに含まれるVB6コードを設定する。
	* @param value 新しい元の.frmに含まれるVB6コード
	*/
	void setVb6Code(String value) {
		this.vb6Code = value;
	}
}
