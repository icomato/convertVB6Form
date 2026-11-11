package constants;

import java.nio.charset.Charset;
import java.util.regex.Pattern;

/**
* VB6フォーム変換で使用する文字コード、解析パターン、換算値と出力先の定数。
*
* @author convertVB6Form contributors
* @version 1.0
* @since 1.0
*/
public final class Vb6ConversionConstants {
	// VB6の日本語.frmをWindows-31Jで読み取る。生成ファイルはUTF-8で出力する。
	/** VB6フォームの読み込みに使用するWindows-31J文字コード。 */
	public static final Charset VB6_CHARSET = Charset.forName("windows-31j");

	// .frmのBegin/Endでコントロール階層を、BeginProperty/EndPropertyでFont等の属性を認識する。
	/**
	* コントロールの開始宣言「Begin 型名 要素名」の行全体に一致する。
	* 前後の空白を許可し、型名には英数字・アンダースコア・ピリオドを許可する。
	* 要素名は空白を含まない1語で、キーワードの大文字小文字は区別する。
	* 例: 「Begin VB.CommandButton Command1」→ グループ1はVB.CommandButton、
	* グループ2はCommand1。「Begin VB.Label」は要素名がないため一致しない。
	*/
	public static final Pattern BEGIN = Pattern.compile("^\\s*Begin\\s+([\\w.]+)\\s+([^\\s]+)\\s*$");

	/**
	* 複合属性の開始宣言「BeginProperty 属性名」の行全体に一致する。
	* 属性名は空白を含まない1語で、その後の空白と追加情報は省略できる。
	* 例: 「BeginProperty Font」と「BeginProperty Font {GUID}」はどちらも一致し、
	* グループ1からFontを取得する。「EndProperty」は一致しない。
	*/
	public static final Pattern BEGIN_PROPERTY = Pattern.compile("^\\s*BeginProperty\\s+(\\S+)(?:\\s+.*)?$");

	/**
	* 「属性名 = 値」の行全体に一致し、前後と等号の両側の空白を許可する。
	* グループ1は等号を含まない属性名、グループ2は空文字列も許容する属性値。
	* 例: 「Caption = "住所"」→ Captionと"住所"、
	* 「ClientWidth = 3000」→ ClientWidthと3000を取り出す。
	* 「Text =」も一致するが、等号がない「End」は一致しない。
	*/
	public static final Pattern PROPERTY = Pattern.compile("^\\s*([^=]+?)\\s*=\\s*(.*?)\\s*$");

	// SSTab固有の「Tab(n).Control(m)」形式から子要素の所属タブを抽出する。
	/**
	* SSTabの子コントロール所属を示す「Tab(数字).Control(数字)」に一致する。
	* 括弧とピリオドは文字そのものを表し、数字は半角数字1桁以上。
	* 呼び出し側のmatchesで属性名全体を照合し、大文字小文字は区別しない。
	* 例: 「Tab(1).Control(12)」→ グループ1は1、グループ2は12。
	* 「tab(0).control(2)」も一致し、「Tab(A).Control(0)」は一致しない。
	*/
	public static final Pattern TAB_CONTROL = Pattern.compile("Tab\\((\\d+)\\)\\.Control\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);

	// 非選択タブのコントロールが、左方向へ75000Twips移動して保存されるケースを補正する。
	/** SSTabが非表示タブを退避させる際に用いるTwipsオフセット。 */
	public static final long SSTAB_HIDE_TWIPS = 75000L;

	/**
	* DPIを省略した場合の換算値。
	* Windowsの標準倍率に相当する。
	*/
	public static final double DEFAULT_DPI = 96.0;

	/**
	* 変換ツールが受け付けるDPIの下限。
	* 標準倍率の半分。
	*/
	public static final double MIN_DPI = 48.0;

	/**
	* 変換ツールが受け付けるDPIの上限。
	* 標準倍率の8倍。
	*/
	public static final double MAX_DPI = 768.0;

	/**
	* 1インチに含まれるTwips数。
	* DPIからピクセルへ換算する際の基準。
	*/
	public static final double TWIPS_PER_INCH = 1440.0;

	/** VB6のScaleModeでTwipsを表す値。 */
	public static final int VB_SCALE_MODE_TWIPS = 1;

	/** SSTabのTabHeightが未指定の場合に使う見出し高さ（Twips）。 */
	public static final int DEFAULT_TAB_HEIGHT_TWIPS = 520;

	/** 非表示タブの退避座標とみなすLeft値の境界（Twips、既存の補正条件）。 */
	public static final int HIDDEN_TAB_LEFT_THRESHOLD_TWIPS = -10000;

	/** TabPage原点に丸める小さな負のTop値の下限（Twips、境界値は除外）。 */
	public static final int TAB_TOP_CLAMP_LIMIT_TWIPS = -200;

	/** 生成するタブ見出しの固定幅（Twips、既存の表示設定）。 */
	public static final int TAB_HEADER_WIDTH_TWIPS = 1800;

	/** フォントサイズ未指定・不正時の既定値（ポイント）。 */
	public static final String DEFAULT_FONT_SIZE_POINTS = "9";

	/** フォントのWeight未指定時に使う標準の太さ。 */
	public static final int DEFAULT_FONT_WEIGHT = 400;

	/** フォントを太字にするWeightの下限。 */
	public static final int BOLD_FONT_WEIGHT = 700;

	/** TextBoxのMaxLength未指定時に使用する既存の入力上限。 */
	public static final int DEFAULT_TEXT_MAX_LENGTH = 32767;

	/** CByteやRGBの各成分で表現できる符号なし8ビット値の上限。 */
	public static final int UNSIGNED_BYTE_MAX = 255;

	/** OLE_COLORの上位バイトを取り出すマスク。 */
	public static final long OLE_COLOR_TYPE_MASK = 0xFF000000L;

	/** OLE_COLORでシステム色を示す上位バイトの値。 */
	public static final long OLE_SYSTEM_COLOR_FLAG = 0x80000000L;

	/** VB.NETソースの出力先の下に座標監査CSVを保存するフォルダ名。 */
	public static final String REPORTS_DIRECTORY = "reports";

	/** インスタンス化を防ぐ。 */
	private Vb6ConversionConstants() {
	}
}
