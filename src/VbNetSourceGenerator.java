import static constants.Vb6ConversionConstants.BOLD_FONT_WEIGHT;
import static constants.Vb6ConversionConstants.DEFAULT_FONT_SIZE_POINTS;
import static constants.Vb6ConversionConstants.DEFAULT_FONT_WEIGHT;
import static constants.Vb6ConversionConstants.DEFAULT_TEXT_MAX_LENGTH;
import static constants.Vb6ConversionConstants.OLE_COLOR_TYPE_MASK;
import static constants.Vb6ConversionConstants.OLE_SYSTEM_COLOR_FLAG;
import static constants.Vb6ConversionConstants.TAB_HEADER_WIDTH_TWIPS;
import static constants.Vb6ConversionConstants.UNSIGNED_BYTE_MAX;

import entity.Vb6ConversionStateEntity;
import entity.Vb6FormNodeEntity;
import entity.Vb6TabInfoEntity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
* 解析・配置解決済みフォームからWinFormsのデザイナーと移行用のフィールド宣言を生成する。
*
* @author convertVB6Form contributors
* @version 1.0
* @since 1.0
*/
final class VbNetSourceGenerator {
	/** インスタンス化を防ぐ。 */
	private VbNetSourceGenerator() {
	}

	/**
	* フィールド名をMe.で修飾する。
	* @param n フィールド名
	* @return Me.フィールド名
	*/
	private static String toControlReference(String n) {
		// フィールド名にMe.を付け、生成コードで使うコントロール参照を返す。
		return "Me." + n;
	}

	/**
	* VB.NETプロパティ代入の対象を選択する。
	* @param n コントロールノード
	* @return フォームならMe、子ならMe.フィールド名
	*/
	private static String toPropertyTarget(Vb6FormNodeEntity n) {
		// フォーム自身への代入はMeを使い、子コントロールにはフィールド参照を返す。
		return "VB.Form".equals(n.getVbType()) ? "Me" : toControlReference(n.getField());
	}

	/**
	* VB.NETのコントロールプロパティ代入文を生成する。
	* @param b 生成するVB.NETコードや監査資料の行を蓄積する文字列バッファ
	* @param n コントロールノード
	* @param property プロパティ名
	* @param expression 右辺のVB.NET式
	*/
	private static void appendPropertyAssignment(StringBuilder b, Vb6FormNodeEntity n, String property, String expression) {
		Vb6Format.appendLine(b, "        " + toPropertyTarget(n) + "." + property + " = " + expression);
	}

	/**
	* ImageListなど、Controls.Addをしない非表示コンポーネントを判定する。
	* @param n 対象ノード
	* @return 非表示コンポーネントならtrue
	*/
	private static boolean isNonVisual(Vb6FormNodeEntity n) {
		// 変換先の型が、画面上に配置しないImageListまたはOpenFileDialogかを返す。
		return n.getWinType() != null && (n.getWinType().endsWith(".ImageList") || n.getWinType().endsWith(".OpenFileDialog"));
	}

	/**
	* 単一コントロールの位置、サイズ、文字、フォント、種別固有の属性を生成する。
	* LabelはCaption・WordWrap・AutoSizeから文字切れを避ける設定を決定する。
	* @param out コントロールのプロパティ代入文を追記するVB.NETコードバッファ
	* @param n 対象ノード
	* @param stateEntity ルートフォームを属性出力の対象から除外するために参照する変換状態
	*/
	private static void appendControlProperties(StringBuilder out, Vb6FormNodeEntity n, Vb6ConversionStateEntity stateEntity) {

		// 未対応ノードとフォーム本体は、子コントロール用の属性出力から除外する。
		if (!n.isSupported() || n == stateEntity.getForm()) {
			// 未対応ノードとフォーム本体は、子コントロール用の属性を出力せず終了する。
			return;
		}

		// ImageListなどの非表示部品には、位置やサイズなどの画面属性を出力しない。
		if (isNonVisual(n)) {
			// 非表示部品には画面上の位置やサイズを設定せず、属性出力を終了する。
			return;
		}
		appendPropertyAssignment(out, n, "Name", Vb6Format.toVbStringLiteral(n.getField()));
		appendPropertyAssignment(out, n, "Location", "New System.Drawing.Point(" + n.getPixelLeft() + ", " + n.getPixelTop() + ")");

		// 元定義にサイズ指定がある場合だけ、最小1ピクセルの表示サイズを設定する。
		if (n.hasProperty("Width") || n.hasProperty("Height")) {
			appendPropertyAssignment(out, n, "Size", "New System.Drawing.Size(" + Math.max(1, n.getPixelWidth()) + ", " + Math.max(1, n.getPixelHeight()) + ")");
		}

		// VB6のCaptionを、WinFormsで表示するTextに引き継ぐ。
		if (n.hasProperty("Caption")) {
			appendPropertyAssignment(out, n, "Text", Vb6Format.toVbStringLiteral(Vb6Format.parseVbString(n.getProperty("Caption"))));
		}

		// TextBox以外のText属性を引き継ぎ、TextBox固有の出力処理との重複を避ける。
		if (n.hasProperty("Text") && !n.getVbType().equals("VB.TextBox")) {
			appendPropertyAssignment(out, n, "Text", Vb6Format.toVbStringLiteral(Vb6Format.parseVbString(n.getProperty("Text"))));
		}

		// 表示・非表示の明示指定がある場合だけ、元の状態を引き継ぐ。
		if (n.hasProperty("Visible")) {
			appendPropertyAssignment(out, n, "Visible", Vb6Format.toVbBooleanLiteral(Vb6Format.isVbTrue(n.getProperty("Visible"))));
		}

		// 操作可否の明示指定がある場合だけ、元のEnabled状態を引き継ぐ。
		if (n.hasProperty("Enabled")) {
			appendPropertyAssignment(out, n, "Enabled", Vb6Format.toVbBooleanLiteral(Vb6Format.isVbTrue(n.getProperty("Enabled"))));
		}

		// 明示されたタブ移動順を数値として出力する。
		if (n.hasProperty("TabIndex")) {
			// TabIndexは0始まり。未指定の場合は先頭の0を使う。
			appendPropertyAssignment(out, n, "TabIndex", Integer.toString(Vb6Format.parseInteger(n.getProperty("TabIndex"), 0)));
		}

		// フォント名・ポイント数・太字などはBeginProperty Fontから復元する。
		Vb6FormNodeEntity font = n.getNested().get("Font");

		// 複合属性Fontがある場合だけ、名前・サイズ・装飾・文字セットを復元する。
		if (font != null) {
			// WinFormsのFontに指定する書体名。空なら後で既定値を補う。
			String fontName = Vb6Format.parseVbString(font.getProperty("Name"));

			// フォント名が空の場合は、生成コードで使用する既定フォントを補う。
			if (fontName.isEmpty()) {
				fontName = "MS UI Gothic";
			}

			// Sizeが未設定なら既定ポイント数を使い、設定済みなら前後の空白を除いた値を使う。
			String fontSize = font.getProperty("Size") == null ? DEFAULT_FONT_SIZE_POINTS : font.getProperty("Size").trim();
			try {
				new BigDecimal(fontSize);
			} catch (NumberFormatException ex) {
				// 数値として無効なSizeを既定ポイント数に置き換え、VB.NETの生成式に残さない。
				fontSize = DEFAULT_FONT_SIZE_POINTS;
			}

			// WinFormsのFontStyleへ組み合わせる装飾の一覧。
			List<String> styles = new ArrayList<>();

			// VB6の太字に相当するWeightなら、出力するフォント装飾にBoldを加える。
			if (Vb6Format.parseInteger(font.getProperty("Weight"), DEFAULT_FONT_WEIGHT) >= BOLD_FONT_WEIGHT) {
				styles.add("System.Drawing.FontStyle.Bold");
			}

			// 斜体指定を、他の装飾と併用できるフォントスタイルとして追加する。
			if (Vb6Format.isVbTrue(font.getProperty("Italic"))) {
				styles.add("System.Drawing.FontStyle.Italic");
			}

			// 下線指定を、出力するフォントスタイルに追加する。
			if (Vb6Format.isVbTrue(font.getProperty("Underline"))) {
				styles.add("System.Drawing.FontStyle.Underline");
			}

			// 取り消し線指定を、WinFormsのStrikeoutに引き継ぐ。
			if (Vb6Format.isVbTrue(font.getProperty("Strikethrough"))) {
				styles.add("System.Drawing.FontStyle.Strikeout");
			}

			// 装飾がなければRegularを使い、あれば指定された装飾をOrで連結する。
			String style = styles.isEmpty() ? "System.Drawing.FontStyle.Regular" : String.join(" Or ", styles);

			// Charset未指定時の0はANSI文字セット。CByteの範囲に収めて生成する。
			int charset = Vb6Format.parseInteger(font.getProperty("Charset"), 0);
			appendPropertyAssignment(out, n, "Font", "New System.Drawing.Font(" + Vb6Format.toVbStringLiteral(fontName) + ", CSng(" + fontSize
			+ "), " + style + ", System.Drawing.GraphicsUnit.Point, CByte(" + Math.max(0, Math.min(UNSIGNED_BYTE_MAX, charset)) + "))");
		}

		// 背景色が指定されている場合だけ、OLE_COLORからの変換を試みる。
		if (n.hasProperty("BackColor")) {
			// BackColorに設定するWinFormsの色式。変換できなければnull。
			String color = toColor(n.getProperty("BackColor"));

			// 変換できた背景色だけを出力し、未対応色による不正な式を生成しない。
			if (color != null) {
				appendPropertyAssignment(out, n, "BackColor", color);
			}
		}

		// 文字色が指定されている場合だけ、OLE_COLORからの変換を試みる。
		if (n.hasProperty("ForeColor")) {
			// ForeColorに設定するWinFormsの色式。変換できなければnull。
			String color = toColor(n.getProperty("ForeColor"));

			// 変換できた文字色だけを出力し、WinFormsの既定色を不用意に上書きしない。
			if (color != null) {
				appendPropertyAssignment(out, n, "ForeColor", color);
			}
		}

		// コントロール固有のVB6プロパティをWinFormsの対応プロパティに変換。
		switch (n.getVbType()) {
			case "VB.Label" -> {
				// 固定文字のラベルはWinForms側で文字幅・高さに合わせる。
				// VB6側にAutoSize指定があればその指定を優先する。
				// 実行時に値を書き換える空Captionのラベルは固定サイズを維持する。
				// AutoSizeが明示されていればその値を優先し、未指定なら固定文字かつ折り返しなしの場合だけ有効にする。
				boolean autoSize = n.hasProperty("AutoSize")
				? Vb6Format.isVbTrue(n.getProperty("AutoSize"))
				: !Vb6Format.parseVbString(n.getProperty("Caption")).isBlank() && !Vb6Format.isVbTrue(n.getProperty("WordWrap"));
				appendPropertyAssignment(out, n, "AutoSize", Vb6Format.toVbBooleanLiteral(autoSize));

				// ラベルの配置指定を、WinFormsの文字揃えに変換する。
				if (n.hasProperty("Alignment")) {
					// Alignmentの対応値は0が左寄せ、1が右寄せ、2が中央寄せ。未指定は0。
					appendPropertyAssignment(out, n, "TextAlign", switch (Vb6Format.parseInteger(n.getProperty("Alignment"), 0)) {
						case 1 -> "System.Drawing.ContentAlignment.MiddleRight";
						case 2 -> "System.Drawing.ContentAlignment.MiddleCenter";
						default -> "System.Drawing.ContentAlignment.MiddleLeft";
					});
				}
			}
			case "VB.TextBox" -> {

				// TextBoxの初期入力文字列を、VB.NETの文字列式として出力する。
				if (n.hasProperty("Text")) {
					appendPropertyAssignment(out, n, "Text", Vb6Format.toVbStringLiteral(Vb6Format.parseVbString(n.getProperty("Text"))));
				}

				// 入力ロック指定をReadOnlyに変換し、元の編集可否を引き継ぐ。
				if (n.hasProperty("Locked")) {
					appendPropertyAssignment(out, n, "ReadOnly", Vb6Format.toVbBooleanLiteral(Vb6Format.isVbTrue(n.getProperty("Locked"))));
				}

				// 入力文字数の上限が指定されている場合だけ、MaxLengthを設定する。
				if (n.hasProperty("MaxLength")) {
					appendPropertyAssignment(out, n, "MaxLength", Integer.toString(Vb6Format.parseInteger(n.getProperty("MaxLength"), DEFAULT_TEXT_MAX_LENGTH)));
				}

				// 複数行入力の指定を、WinFormsのMultilineに引き継ぐ。
				if (n.hasProperty("MultiLine")) {
					appendPropertyAssignment(out, n, "Multiline", Vb6Format.toVbBooleanLiteral(Vb6Format.isVbTrue(n.getProperty("MultiLine"))));
				}

				// スクロールバーの指定値を、WinFormsの列挙値へ変換する。
				if (n.hasProperty("ScrollBars")) {
					// ScrollBarsの既存対応表は0がなし、1が縦、2が横、3が両方。
					String bars = switch (Vb6Format.parseInteger(n.getProperty("ScrollBars"), 0)) {
						case 1 -> "Vertical";
						case 2 -> "Horizontal";
						case 3 -> "Both";
						default -> "None";
					};
					appendPropertyAssignment(out, n, "ScrollBars", "System.Windows.Forms.ScrollBars." + bars);
				}
			}
			case "VB.CheckBox", "VB.OptionButton" -> {

				// チェック状態の初期値を、CheckBoxまたはOptionButtonのCheckedに引き継ぐ。
				if (n.hasProperty("Value")) {
					appendPropertyAssignment(out, n, "Checked", Vb6Format.toVbBooleanLiteral(Vb6Format.isVbTrue(n.getProperty("Value"))));
				}
			}
			case "VB.ComboBox" -> {

				// コンボボックスの入力方式を、WinFormsのDropDownStyleへ変換する。
				if (n.hasProperty("Style")) {
					// ComboBox.Styleの対応値は0が通常、1が単純、2が選択専用。
					String style = switch (Vb6Format.parseInteger(n.getProperty("Style"), 0)) {
						case 1 -> "Simple";
						case 2 -> "DropDownList";
						default -> "DropDown";
					};
					appendPropertyAssignment(out, n, "DropDownStyle", "System.Windows.Forms.ComboBoxStyle." + style);
				}
			}
			case "VB.Image" -> {
				// Stretchが真なら画像を枠に合わせて伸縮し、偽なら元のサイズで表示する。
				String mode = Vb6Format.isVbTrue(n.getProperty("Stretch")) ? "StretchImage" : "Normal";
				appendPropertyAssignment(out, n, "SizeMode", "System.Windows.Forms.PictureBoxSizeMode." + mode);
			}
			case "VB.ListBox" -> {

				// リストの複数選択方式を、WinFormsのSelectionModeへ変換する。
				if (n.hasProperty("MultiSelect")) {
					// MultiSelectの対応値は0が単一選択、1が単純な複数選択、2が拡張複数選択。
					String mode = switch (Vb6Format.parseInteger(n.getProperty("MultiSelect"), 0)) {
						case 1 -> "MultiSimple";
						case 2 -> "MultiExtended";
						default -> "One";
					};
					appendPropertyAssignment(out, n, "SelectionMode", "System.Windows.Forms.SelectionMode." + mode);
				}
			}
			case "VB.PictureBox" -> {

				// PictureBoxの枠指定を、WinFormsの枠の有無に変換する。
				if (n.hasProperty("BorderStyle")) {
					// PictureBox.BorderStyleは0が枠なし。それ以外は既存どおり単線の枠を生成する。
					appendPropertyAssignment(out, n, "BorderStyle",
					"System.Windows.Forms.BorderStyle." + (Vb6Format.parseInteger(n.getProperty("BorderStyle"), 0) == 0 ? "None" : "FixedSingle"));
				}
			}
			case "MSComctlLib.ListView" -> {
				// View未指定時は既存の既定値3を使う。下の対応表でWinFormsの表示方式を選ぶ。
				int view = Vb6Format.parseInteger(n.getProperty("View"), 3);

				// ListView.Viewの既存対応表は0が大アイコン、1が詳細、2が小アイコン、それ以外が一覧。
				String style = switch (view) {
					case 0 -> "LargeIcon";
					case 1 -> "Details";
					case 2 -> "SmallIcon";
					default -> "List";
				};
				appendPropertyAssignment(out, n, "View", "System.Windows.Forms.View." + style);
			}
			case "SHDocVwCtl.WebBrowser" -> {
				appendPropertyAssignment(out, n, "ScriptErrorsSuppressed", "True");
			}
		}
	}

	/**
	* VB6のOLE_COLORをSystem.Drawing.Colorの式へ変換する。
	* 通常色はBGR、システム色は一部対応。
	* @param raw VB6の色コード
	* @return WinForms色の式。
	* 変換できなければnull
	*/
	private static String toColor(String raw) {

		// 色の指定がない場合は変換結果を返さず、既定の表示色を維持する。
		if (raw == null) {
			// 色の指定がないことをnullで示し、呼び出し元で既定色を維持できるようにする。
			return null;
		}

		// OLE_COLORは最大32ビットなので、&Hに続く16進数を1～8桁として受け付ける。
		// 「&H」+ 半角16進数字1～8桁 + 省略可能な末尾の「&」に一致する。
		// 例: &H00FF00& → グループ1は00FF00、&H80000005 → 80000005。
		// 16進数字は大小両方を許可するが、接頭辞は大文字Hのみ。「&HZZ&」は一致しない。
		// 行頭・行末は固定せずfindで部分検索するため、色コード以外の前後文字は検査しない。
		Matcher m = Pattern.compile("&H([0-9A-Fa-f]{1,8})&?").matcher(raw.trim());

		// 対応する16進OLE_COLOR形式でない値は、生成コードに出力しない。
		if (!m.find()) {
			// 対応する16進形式でない色は、変換不可を示すnullとして返す。
			return null;
		}

		// グループ1の色コードを基数16で読み、符号なし32ビット相当の値として扱う。
		long value = Long.parseUnsignedLong(m.group(1), 16);

		// システム色を示す識別値なら、対応するSystemColorsの式を返す。
		if ((value & OLE_COLOR_TYPE_MASK) == OLE_SYSTEM_COLOR_FLAG) {
			// 下位8ビットがシステム色番号。対応する5・8・15・18だけを下の表で変換する。
			// 対応済みのシステム色の式を返し、未対応のシステム色にはnullを返す。
			return switch ((int) (value & UNSIGNED_BYTE_MAX)) {
				case 5 -> "System.Drawing.SystemColors.Window";
				case 8 -> "System.Drawing.SystemColors.WindowText";
				case 15 -> "System.Drawing.SystemColors.Control";
				case 18 -> "System.Drawing.SystemColors.ControlText";
				default -> null;
			};
		}

		// OLE_COLORの下位8ビットから、FromArgbに渡す赤成分を取り出す。
		int r = (int) (value & UNSIGNED_BYTE_MAX);

		// 次の8ビットから、FromArgbに渡す緑成分を取り出す。
		int g = (int) ((value >> 8) & UNSIGNED_BYTE_MAX);

		// さらに上位の8ビットから、FromArgbに渡す青成分を取り出す。
		int b = (int) ((value >> 16) & UNSIGNED_BYTE_MAX);

		// BGRの各成分をRGB順に並べ、WinFormsの色生成式として返す。
		return "System.Drawing.Color.FromArgb(" + r + ", " + g + ", " + b + ")";
	}

	/**
	* WinFormsのDesigner.vbを生成する。
	* コントロール生成、プロパティ設定、TabPageと親子関係の割当、
	* フィールド宣言を順に行う。
	* @param stateEntity 解決済み変換状態
	* @return Designer.vb全文
	*/
	static String toDesignerSource(Vb6ConversionStateEntity stateEntity) {
		// 生成するDesigner.vbの全文（初期化処理・プロパティ・フィールド宣言）を蓄積する。
		StringBuilder out = new StringBuilder();
		Vb6Format.appendLine(out, "' 自動生成: Vb6WinFormsConverter (Java 21) / レイアウト変換のみ");
		Vb6Format.appendLine(out, "' 自動生成されたため手作業変更は避けてください。制限は migration_report.txt を参照。");
		Vb6Format.appendLine(out, "<Global.Microsoft.VisualBasic.CompilerServices.DesignerGenerated()> _");
		Vb6Format.appendLine(out, "Partial Class " + stateEntity.getClassName());
		Vb6Format.appendLine(out, "    Inherits System.Windows.Forms.Form");
		Vb6Format.appendLine(out, "");
		Vb6Format.appendLine(out, "    <Global.System.Diagnostics.DebuggerNonUserCode()> _");
		Vb6Format.appendLine(out, "    Protected Overrides Sub Dispose(disposing As Boolean)");
		Vb6Format.appendLine(out, "        Try");
		Vb6Format.appendLine(out, "            If disposing AndAlso components IsNot Nothing Then components.Dispose()");
		Vb6Format.appendLine(out, "        Finally");
		Vb6Format.appendLine(out, "            MyBase.Dispose(disposing)");
		Vb6Format.appendLine(out, "        End Try");
		Vb6Format.appendLine(out, "    End Sub");
		Vb6Format.appendLine(out, "");
		Vb6Format.appendLine(out, "    Private components As System.ComponentModel.IContainer");
		Vb6Format.appendLine(out, "");
		Vb6Format.appendLine(out, "    <Global.System.Diagnostics.DebuggerStepThrough()> _");
		Vb6Format.appendLine(out, "    Private Sub InitializeComponent()");
		Vb6Format.appendLine(out, "        Me.components = New System.ComponentModel.Container()");

		// 先にコントロールの実体をすべて作り、Controls.Addが参照できるようにする。

		// 属性や親子関係の設定より先に、出力対象コントロールの実体を生成する。
		for (Vb6FormNodeEntity n : stateEntity.getAll()) {

			// フォーム本体と未対応要素を除き、子コントロールの生成式を出力する。
			if (n != stateEntity.getForm() && n.isSupported()) {

				// ImageListはフォームのコンポーネント管理に登録し、破棄処理の対象とする。
				if (n.getWinType().endsWith(".ImageList")) {
					Vb6Format.appendLine(out, "        Me." + n.getField() + " = New " + n.getWinType() + "(Me.components)");
				} else {
					Vb6Format.appendLine(out, "        Me." + n.getField() + " = New " + n.getWinType() + "()");
				}
			}
		}

		// 各SSTabで必要になるTabPageの生成式を出力する。
		for (Vb6TabInfoEntity t : stateEntity.getTabs().values()) {

			// タブごとのページ実体を、コントロール追加より先に生成する。
			for (Vb6FormNodeEntity p : t.getPages()) {
				Vb6Format.appendLine(out, "        Me." + p.getField() + " = New System.Windows.Forms.TabPage()");
			}
		}
		Vb6Format.appendLine(out, "        Me.SuspendLayout()");

		// 次に位置・サイズ・表示文字などの基本属性を設定する。

		// 生成済みコントロールに位置・サイズ・表示属性を順番に設定する。
		for (Vb6FormNodeEntity n : stateEntity.getAll()) {

			// フォーム本体以外の対応済みコントロールだけに、個別の属性を出力する。
			if (n.isSupported() && n != stateEntity.getForm()) {
				appendControlProperties(out, n, stateEntity);
			}
		}

		// SSTabごとにページ見出しと、タブ本体の表示設定を出力する。
		for (Vb6TabInfoEntity ti : stateEntity.getTabs().values()) {

			// 各TabPageに識別名・見出し文字・背景表示を設定する。
			for (Vb6FormNodeEntity page : ti.getPages()) {
				// 元のTab(n).Captionを参照するときに使う0始まりのページ番号。
				int i = page.getTabIndex();
				appendPropertyAssignment(out, page, "Name", Vb6Format.toVbStringLiteral(page.getField()));
				appendPropertyAssignment(out, page, "Text", Vb6Format.toVbStringLiteral(Vb6Format.parseVbString(ti.getTab().getProperty("TabCaption(" + i + ")"))));
				appendPropertyAssignment(out, page, "UseVisualStyleBackColor", "True");
			}
			appendPropertyAssignment(out, ti.getTab(), "Multiline", "True");
			appendPropertyAssignment(out, ti.getTab(), "SizeMode", "System.Windows.Forms.TabSizeMode.Fixed");
			appendPropertyAssignment(out, ti.getTab(), "ItemSize", "New System.Drawing.Size(" + Vb6Format.toPixels(TAB_HEADER_WIDTH_TWIPS, stateEntity.getDpi()) + ", " + Math.max(1, Vb6Format.toPixels(ti.getTabHeightTwips(), stateEntity.getDpi())) + ")");
		}

		// ネストしたFrame等への追加と、SSTab直下のTabPageへの追加を行う。

		// フォーム直下以外のコントロールを、対応するコンテナに追加する。
		for (Vb6FormNodeEntity n : stateEntity.getAll()) {

			// フォーム直下の要素や画面に配置しない要素を、ネスト追加処理から除外する。
			if (n == stateEntity.getForm() || n.getParent() == stateEntity.getForm() || !n.isSupported() || isNonVisual(n)) {
				continue;
			}

			// SSTab直下の子は、所属を解決したTabPageのControlsに追加する。
			if (stateEntity.getTabs().containsKey(n.getParent())) {
				// Tab pages may be omitted if a tab control is skipped in the future.
				Vb6Format.appendLine(out, "        Me." + n.getResolvedParent() + ".Controls.Add(Me." + n.getField() + ")");

			// 対応する通常コンテナが親なら、そのControlsに追加して階層を保持する。
			} else if (n.getParent() != null && n.getParent().isSupported()) {
				Vb6Format.appendLine(out, "        Me." + n.getParent().getField() + ".Controls.Add(Me." + n.getField() + ")");
			}
		}

		// 生成済みのTabPageを、それぞれのTabControlへ登録する。
		for (Vb6TabInfoEntity ti : stateEntity.getTabs().values()) {

			// ページの並び順を保って、TabPagesへの追加式を出力する。
			for (Vb6FormNodeEntity page : ti.getPages()) {
				Vb6Format.appendLine(out, "        Me." + ti.getTab().getField() + ".TabPages.Add(Me." + page.getField() + ")");
			}
		}

		// フォーム内部のサイズを設定。AutoScaleMode=NoneでDPI自動拡大を防ぐ。
		appendPropertyAssignment(out, stateEntity.getForm(), "Name", Vb6Format.toVbStringLiteral(stateEntity.getClassName()));
		appendPropertyAssignment(out, stateEntity.getForm(), "AutoScaleMode", "System.Windows.Forms.AutoScaleMode.None");

		// ClientWidth・ClientHeightが未指定の場合は0 Twipsとして扱う。
		appendPropertyAssignment(out, stateEntity.getForm(), "ClientSize", "New System.Drawing.Size(" + Vb6Format.toPixels(Vb6Format.parseInteger(stateEntity.getForm().getProperty("ClientWidth"), 0), stateEntity.getDpi()) + ", "
		+ Vb6Format.toPixels(Vb6Format.parseInteger(stateEntity.getForm().getProperty("ClientHeight"), 0), stateEntity.getDpi()) + ")");
		appendPropertyAssignment(out, stateEntity.getForm(), "Text", Vb6Format.toVbStringLiteral(Vb6Format.parseVbString(stateEntity.getForm().getProperty("Caption"))));

		// フォームの枠形式が指定されている場合だけ、FormBorderStyleに変換する。
		if (stateEntity.getForm().hasProperty("BorderStyle")) {
			// フォームの枠は0なし・1固定・2可変・3ダイアログ・4固定ツール・5可変ツール。未指定は2。
			String border = switch (Vb6Format.parseInteger(stateEntity.getForm().getProperty("BorderStyle"), 2)) {
				case 0 -> "None";
				case 1 -> "FixedSingle";
				case 3 -> "FixedDialog";
				case 4 -> "FixedToolWindow";
				case 5 -> "SizableToolWindow";
				default -> "Sizable";
			};
			appendPropertyAssignment(out, stateEntity.getForm(), "FormBorderStyle", "System.Windows.Forms.FormBorderStyle." + border);
		}

		// 最大化ボタンの有無を、フォームのMaximizeBoxへ引き継ぐ。
		if (stateEntity.getForm().hasProperty("MaxButton")) {
			appendPropertyAssignment(out, stateEntity.getForm(), "MaximizeBox", Vb6Format.toVbBooleanLiteral(Vb6Format.isVbTrue(stateEntity.getForm().getProperty("MaxButton"))));
		}

		// 最小化ボタンの有無を、フォームのMinimizeBoxへ引き継ぐ。
		if (stateEntity.getForm().hasProperty("MinButton")) {
			appendPropertyAssignment(out, stateEntity.getForm(), "MinimizeBox", Vb6Format.toVbBooleanLiteral(Vb6Format.isVbTrue(stateEntity.getForm().getProperty("MinButton"))));
		}

		// 起動時の配置方式を、WinFormsのStartPositionへ変換する。
		if (stateEntity.getForm().hasProperty("StartUpPosition")) {
			// 起動位置は0手動・1親の中央・2画面中央・3既定位置。それ以外は既定の位置とサイズ。
			String start = switch (Vb6Format.parseInteger(stateEntity.getForm().getProperty("StartUpPosition"), 0)) {
				case 1 -> "CenterParent";
				case 2 -> "CenterScreen";
				case 3 -> "WindowsDefaultLocation";
				case 0 -> "Manual";
				default -> "WindowsDefaultBounds";
			};
			appendPropertyAssignment(out, stateEntity.getForm(), "StartPosition", "System.Windows.Forms.FormStartPosition." + start);
		}

		// フォーム直下の子を調べ、ルートのControlsへの追加式を生成する。
		for (Vb6FormNodeEntity child : stateEntity.getForm().getChildren()) {

			// 対応済みの表示コントロールだけを、フォーム直下のControlsに追加する。
			if (child.isSupported() && !isNonVisual(child)) {
				Vb6Format.appendLine(out, "        Me.Controls.Add(Me." + child.getField() + ")");
			}
		}
		Vb6Format.appendLine(out, "        Me.ResumeLayout(False)");
		Vb6Format.appendLine(out, "    End Sub");
		Vb6Format.appendLine(out, "");

		// 生成対象コントロールを、コードビハインドから参照するフィールドとして宣言する。
		for (Vb6FormNodeEntity n : stateEntity.getAll()) {

			// フォーム本体と未対応要素を除き、WithEvents付きのフィールドを出力する。
			if (n != stateEntity.getForm() && n.isSupported()) {
				Vb6Format.appendLine(out, "    Friend WithEvents " + n.getField() + " As " + n.getWinType());
			}
		}

		// 自動生成したTabPageについても、参照用フィールドを宣言する。
		for (Vb6TabInfoEntity t : stateEntity.getTabs().values()) {

			// 各ページのフィールド名と型を、クラス末尾の宣言に出力する。
			for (Vb6FormNodeEntity p : t.getPages()) {
				Vb6Format.appendLine(out, "    Friend WithEvents " + p.getField() + " As " + p.getWinType());
			}
		}
		Vb6Format.appendLine(out, "End Class");

		// コントロールの生成・属性・親子関係・フィールド宣言を含むDesigner全文を返す。
		return out.toString();
	}

	/**
	* VB6コードから単純なPublicフィールドのみを抽出する。
	* イベントや業務ロジックは移植しない。
	* @param code 元VB6コード
	* @return VB.NETのPublicフィールド宣言
	*/
	static String toPublicFieldDeclarations(String code) {
		// 元VB6コードから抽出したPublicフィールドのVB.NET宣言文を蓄積する。
		StringBuilder output = new StringBuilder();

		// 複数行モードで、行頭Publicに続く「識別子 As 基本型」と行末の空白に一致する。
		// 識別子の先頭は半角英字か_、以降は半角英数字か_。大文字小文字は区別しない。
		// 基本型はBoolean・Integer・Long・String・Double・Singleだけを許可する。
		// 例: 「Public customerName As String」→ グループ1はcustomerName、グループ2はString。
		// 「Public total As Currency」や配列宣言「Public names() As String」は一致しない。
		// 区切りの\sは改行も含むため、空白位置で改行された宣言にも一致し得る。
		Matcher matcher = Pattern.compile("(?m)^Public\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+As\\s+(Boolean|Integer|Long|String|Double|Single)\\s*$", Pattern.CASE_INSENSITIVE).matcher(code);

		// 単純なPublicフィールド宣言を順に抽出し、VB.NETの宣言文へ変換する。
		while (matcher.find()) {
			// 正規表現のグループ1はフィールド名、グループ2は対応可能な基本型名。
			output.append("    Public ").append(matcher.group(1)).append(" As ").append(matcher.group(2)).append("\r\n");
		}

		// 抽出できたPublicフィールドの宣言を連結して返し、該当がなければ空文字列を返す。
		return output.toString();
	}
}
