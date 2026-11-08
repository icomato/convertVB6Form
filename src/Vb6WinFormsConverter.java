import entity.Vb6ConversionStateEntity;
import entity.Vb6FormNodeEntity;
import entity.Vb6TabInfoEntity;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
* VB6フォームの.frmからVB.NET Windows Formsの画面定義を生成するJava 21ツール。
*
* <p>Windows-31Jで読み込み、Twipsを指定DPIのPixelへ換算する。
* SSTabはTabControl/TabPageへ変換し、Frameの親子関係を引き継ぐ。
* 生成したDesigner.vbとともに監査CSVと未移行項目のレポートを出力する。
*
* <p>イベントハンドラー、業務ロジック、COMの動作、.frx画像の移行には対応しない。
* 推定した配置や対応しない要素は、必ず出力結果と警告を確認すること。
*
* @see Vb6FormNodeEntity
* @see Vb6TabInfoEntity
* @see Vb6ConversionStateEntity
*
* @author convertVB6Form contributors
* @version 1.0 (Javaコーディング規約対応)
* @since 1.0
*/
public final class Vb6WinFormsConverter {
	// VB6の日本語.frmをWindows-31Jで読み取る。生成ファイルはUTF-8で出力する。
	/** VB6フォームの読み込みに使用するWindows-31J文字コード。 */
	private static final Charset VB6_CHARSET = Charset.forName("windows-31j");
	// .frmのBegin/Endでコントロール階層を、BeginProperty/EndPropertyでFont等の属性を認識する。
	/** コントロールBegin行の解析用正規表現。 */
	private static final Pattern BEGIN = Pattern.compile("^\\s*Begin\\s+([\\w.]+)\\s+([^\\s]+)\\s*$");
	/** BeginProperty行の解析用正規表現。 */
	private static final Pattern BEGIN_PROPERTY = Pattern.compile("^\\s*BeginProperty\\s+(\\S+)(?:\\s+.*)?$");
	/** プロパティ代入行の解析用正規表現。 */
	private static final Pattern PROPERTY = Pattern.compile("^\\s*([^=]+?)\\s*=\\s*(.*?)\\s*$");
	// SSTab固有の「Tab(n).Control(m)」形式から子要素の所属タブを抽出する。
	/** SSTabのTab(n).Control(m)を認識する正規表現。 */
	private static final Pattern TAB_CONTROL = Pattern.compile("Tab\\((\\d+)\\)\\.Control\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);
	// 非選択タブのコントロールが、左方向へ75000Twips移動して保存されるケースを補正する。
	/** SSTabが非表示タブを退避させる際に用いるTwipsオフセット。 */
	private static final long SSTAB_HIDE_TWIPS = 75000L;
	/**
	* DPIを省略した場合の換算値。
	* Windowsの標準倍率に相当する。
	*/
	private static final double DEFAULT_DPI = 96.0;
	/**
	* 変換ツールが受け付けるDPIの下限。
	* 標準倍率の半分。
	*/
	private static final double MIN_DPI = 48.0;
	/**
	* 変換ツールが受け付けるDPIの上限。
	* 標準倍率の8倍。
	*/
	private static final double MAX_DPI = 768.0;
	/**
	* 1インチに含まれるTwips数。
	* DPIからピクセルへ換算する際の基準。
	*/
	private static final double TWIPS_PER_INCH = 1440.0;
	/** VB6のScaleModeでTwipsを表す値。 */
	private static final int VB_SCALE_MODE_TWIPS = 1;
	/** SSTabのTabHeightが未指定の場合に使う見出し高さ（Twips）。 */
	private static final int DEFAULT_TAB_HEIGHT_TWIPS = 520;
	/** 非表示タブの退避座標とみなすLeft値の境界（Twips、既存の補正条件）。 */
	private static final int HIDDEN_TAB_LEFT_THRESHOLD_TWIPS = -10000;
	/** TabPage原点に丸める小さな負のTop値の下限（Twips、境界値は除外）。 */
	private static final int TAB_TOP_CLAMP_LIMIT_TWIPS = -200;
	/** 生成するタブ見出しの固定幅（Twips、既存の表示設定）。 */
	private static final int TAB_HEADER_WIDTH_TWIPS = 1800;
	/** フォントサイズ未指定・不正時の既定値（ポイント）。 */
	private static final String DEFAULT_FONT_SIZE_POINTS = "9";
	/** フォントのWeight未指定時に使う標準の太さ。 */
	private static final int DEFAULT_FONT_WEIGHT = 400;
	/** フォントを太字にするWeightの下限。 */
	private static final int BOLD_FONT_WEIGHT = 700;
	/** TextBoxのMaxLength未指定時に使用する既存の入力上限。 */
	private static final int DEFAULT_TEXT_MAX_LENGTH = 32767;
	/** CByteやRGBの各成分で表現できる符号なし8ビット値の上限。 */
	private static final int UNSIGNED_BYTE_MAX = 255;
	/** OLE_COLORの上位バイトを取り出すマスク。 */
	private static final long OLE_COLOR_TYPE_MASK = 0xFF000000L;
	/** OLE_COLORでシステム色を示す上位バイトの値。 */
	private static final long OLE_SYSTEM_COLOR_FLAG = 0x80000000L;

	/**
	* インスタンス化を禁止するユーティリティクラスのコンストラクタ。
	*/
	private Vb6WinFormsConverter() {
	}

	/**
	* VB6フォームを読み込んでVB.NET WinFormsのデザイナーと検証用資料を生成する。
	* 使用方法: java -cp bin Vb6WinFormsConverter input.frm output_dir [dpi]。
	* 既存のコードビハインドは上書きしない。
	* @param args 入力.frm、出力ディレクトリ、省略可能なDPI（既定値96）
	* @throws IllegalArgumentException DPIが数値でないか、
	* 48～768の有限値でない場合
	* @throws IOException .frmの読み込み、解析、
	* または出力ファイル作成に失敗した場合
	*/
	public static void main(String[] args) throws IOException {

		// 引数の過不足を検出したら、使用方法を表示して変換を開始せず終了する。
		if (args.length < 2 || args.length > 3) {
			System.err.println("使い方: java -jar Vb6WinFormsConverter.jar <input.frm> <output-dir> [dpi: default 96]");
			System.exit(2); // 2は引数不正を呼び出し元へ通知する終了コード。
		}
		Path source = Path.of(args[0]).toAbsolutePath().normalize();
		Path out = Path.of(args[1]).toAbsolutePath().normalize();

		// 通常ファイルとして読み込めない入力を拒否し、後続の解析失敗を防ぐ。
		if (!Files.isRegularFile(source)) {
			throw new IOException("入力ファイルがありません: " + source);
		}
		// 引数は入力・出力の2個が必須で、3個目だけが省略可能なDPI。
		double dpi = args.length == 3 ? Double.parseDouble(args[2]) : DEFAULT_DPI;

		// 座標換算に使えないDPIを拒否し、極端な拡大縮小や非有限値を防ぐ。
		if (!(dpi >= MIN_DPI && dpi <= MAX_DPI) || !Double.isFinite(dpi)) {
			throw new IllegalArgumentException("DPIは48～768を指定してください");
		}
		// ステップ1: .frm解析 → ステップ2: コントロールの種類と配置を確定する。
		Vb6ConversionStateEntity st = parseForm(source);
		st.setDpi(dpi);
		resolveLayout(st);
		// 入力ファイルは変更せず、出力先ディレクトリへ生成する。
		Files.createDirectories(out);
		Path designer = out.resolve(st.getClassName() + ".Designer.vb");
		Path codeBehind = out.resolve(st.getClassName() + ".vb");
		Path project = out.resolve(st.getClassName() + "_Migrated.vbproj");
		Path program = out.resolve("Program.vb");
		Path audit = out.resolve(st.getClassName() + "_coordinate_audit.csv");
		Path report = out.resolve(st.getClassName() + "_migration_report.txt");
		Files.writeString(designer, toDesignerSource(st), StandardCharsets.UTF_8);
		// 手書きの処理を誤って消さないよう、コードビハインドは既存なら上書きしない。

		// 既存の手書きコードを保護し、新規出力時だけコードビハインドを作成する。
		if (!Files.exists(codeBehind)) {
			Files.writeString(codeBehind,
			"' VB6から移植したイベント処理・ビジネスロジックは未移行です。\r\n" +
			"' 元.frmのコード末尾を確認して必要な処理を移植してください。\r\n" +
			"Partial Class " + st.getClassName() + "\r\n" +
			"    Public Sub New()\r\n" +
			"        InitializeComponent()\r\n" +
			"    End Sub\r\n" +
			toPublicFieldDeclarations(st.getVb6Code()) +
			"End Class\r\n", StandardCharsets.UTF_8);
		} else {
			st.addWarning("既存のコードビハインドは保護しました: " + codeBehind.getFileName());
		}
		// Program.vbと.vbprojも既存ファイルを保護する。

		// 既存の起動処理を保護し、Program.vbがない場合だけ標準の起動処理を生成する。
		if (!Files.exists(program)) {
			Files.writeString(program,
			"Imports System.Windows.Forms\r\n" +
			"Friend Module Program\r\n" +
			"    <System.STAThread()>\r\n" +
			"    Public Sub Main()\r\n" +
			"        Application.EnableVisualStyles()\r\n" +
			"        Application.SetCompatibleTextRenderingDefault(False)\r\n" +
			"        Application.Run(New " + st.getClassName() + "())\r\n" +
			"    End Sub\r\n" +
			"End Module\r\n", StandardCharsets.UTF_8);
		}

		// 移行後のプロジェクト設定を上書きしないよう、新規の場合だけ生成する。
		if (!Files.exists(project)) {
			Files.writeString(project,
			"<Project Sdk=\"Microsoft.NET.Sdk\">\r\n" +
			"  <PropertyGroup>\r\n" +
			"    <OutputType>WinExe</OutputType>\r\n" +
			"    <TargetFramework>net8.0-windows</TargetFramework>\r\n" +
			"    <UseWindowsForms>true</UseWindowsForms>\r\n" +
			"    <EnableWindowsTargeting>true</EnableWindowsTargeting>\r\n" +
			"    <MyType>Empty</MyType>\r\n" +
			"    <RootNamespace>VB6Migrated</RootNamespace>\r\n" +
			"    <StartupObject>VB6Migrated.Program</StartupObject>\r\n" +
			"  </PropertyGroup>\r\n" +
			"  <ItemGroup>\r\n" +
			"    <Compile Update=\"" + st.getClassName() + ".vb\">\r\n" +
			"      <SubType>Form</SubType>\r\n" +
			"    </Compile>\r\n" +
			"    <Compile Update=\"" + st.getClassName() + ".Designer.vb\">\r\n" +
			"      <DependentUpon>" + st.getClassName() + ".vb</DependentUpon>\r\n" +
			"    </Compile>\r\n" +
			"  </ItemGroup>\r\n" +
			"</Project>\r\n", StandardCharsets.UTF_8);
		}
		// 移行結果の検証用データと警告レポートは毎回更新する。
		Files.writeString(audit, toCoordinateAuditCsv(st), StandardCharsets.UTF_8);
		Files.writeString(report, toMigrationReport(st, source), StandardCharsets.UTF_8);
		// 実行ごとのロガーを使い、親ハンドラから標準エラーへ重複出力されるのを防ぐ。
		Logger logger = Logger.getAnonymousLogger();
		logger.setUseParentHandlers(false);
		logger.setLevel(Level.INFO);
		// 出力先に含まれる%を、FileHandlerのパターン指定として解釈させない。
		String logPattern = out.resolve("convertVB6Form.log").toString().replace("%", "%%");
		FileHandler fileHandler = new FileHandler(logPattern, true);
		try {
			fileHandler.setEncoding(StandardCharsets.UTF_8.name());
			fileHandler.setFormatter(new SimpleFormatter());
			logger.addHandler(fileHandler);
			printInfo(logger, "出力: " + designer);
			printInfo(logger, "コードビハインド: " + codeBehind);
			printInfo(logger, "Visual Studioプロジェクト: " + project);
			printInfo(logger, "座標CSV: " + audit);
			printInfo(logger, "移行レポート: " + report);
			printInfo(logger, "コントロール: " + st.getAll().size() + " (出力 " + st.getVisualCount()
			+ " / 省略 " + st.getIgnoredCount() + "), タブ座標補正: " + st.getRemappedTabCount()
			+ ", 所属タブ推定: " + st.getUnknownTabCount() + ", 警告: " + st.getWarnings().size());
		} finally {
			logger.removeHandler(fileHandler);
			fileHandler.close();
		}
	}

	/**
	* 同じメッセージを標準出力とINFOレベルのファイルログへ出力する。
	* @param logger ファイルハンドラを登録済みの実行単位のロガー
	* @param message 両方の出力先に記録するメッセージ
	*/
	private static void printInfo(Logger logger, String message) {
		System.out.println(message);
		logger.info(message);
	}

	/**
	* .frmをWindows-31Jで読み、
	* Begin/Endをスタックで解釈してノードツリーを構築する。
	* BeginPropertyは親コントロールの属性とし、
	* Attribute VB_Name以降はVB6コード部として保存する。
	* @param source 入力.frmのパス
	* @return コントロール階層と元コードを含む解析状態
	* @throws IOException 読み込みに失敗、
	* またはBegin/Endが不整合の場合
	*/
	private static Vb6ConversionStateEntity parseForm(Path source) throws IOException {
		Vb6ConversionStateEntity s = new Vb6ConversionStateEntity();
		String raw = Files.readString(source, VB6_CHARSET);
		// splitの-1は末尾の空行も保持する指定。元コードをレポートに残すため省略しない。
		String[] lines = raw.split("\\r?\\n", -1);
		// Beginでpush、EndでpopすることでVB6のネスト構造を復元する。
		Deque<Vb6FormNodeEntity> stack = new ArrayDeque<>();
		int codeStart = lines.length;

		// フォーム定義を先頭から読み、スタックでコントロール階層を復元する。
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i].trim();
			// ここから先はVB6イベント処理などのコード部なので、レイアウト解析を終了する。

			// VB6コード部の開始位置を保存し、画面定義の解析を終了する。
			if (line.startsWith("Attribute VB_Name")) {
				codeStart = i;
				break;
			}
			Matcher begin = BEGIN.matcher(line);
			Matcher nested = BEGIN_PROPERTY.matcher(line);

			// コントロールの開始宣言からノードを作成し、階層と一覧に登録する。
			if (begin.matches()) {
				Vb6FormNodeEntity parent = stack.peek();
				// BEGINのグループ1はVB6型名、グループ2はコントロール名。
				Vb6FormNodeEntity node = new Vb6FormNodeEntity("control", begin.group(1), begin.group(2), parent);

				// 現在の親コントロールに子を登録し、元の包含関係を保持する。
				if (parent != null) {
					parent.getChildren().add(node);

				// 最初のトップレベルノードを変換対象フォームとして採用する。
				} else if (s.getForm() == null) {
					s.setForm(node);
				} else {
					s.addWarning("複数のトップレベルフォームがあります: " + node.getName());
				}
				stack.push(node);
				s.getAll().add(node);
				// BeginProperty Fontなどは別コントロールではなく親コントロールの属性。

			// Fontなどの複合属性を親コントロールに属する属性ノードとして読み込む。
			} else if (nested.matches()) {
				Vb6FormNodeEntity parent = stack.peek();

				// 対応する親がない複合属性は不正なフォーム定義として扱う。
				if (parent == null) {
					throw new IOException("BeginPropertyの親がありません (行 " + (i + 1) + ")");
				}
				// BEGIN_PROPERTYのグループ1はFontなどの複合属性名。
				Vb6FormNodeEntity node = new Vb6FormNodeEntity("property", "", nested.group(1), parent);
				parent.getNested().put(node.getName(), node);
				stack.push(node);

			// 終了宣言に対応する開始ノードを取り出し、入れ子の整合性を確認する。
			} else if (line.equals("End") || line.equals("EndProperty")) {

				// 開始宣言より終了宣言が多い場合は、行番号を添えて解析を中止する。
				if (stack.isEmpty()) {
					throw new IOException("Endの数が不正です (行 " + (i + 1) + ")");
				}
				Vb6FormNodeEntity popped = stack.pop();

				// コントロールと複合属性で終了宣言の種類が違う場合は、対応不整合を通知する。
				if (line.equals("End") && !popped.getKind().equals("control") ||
				line.equals("EndProperty") && !popped.getKind().equals("property")) {
					throw new IOException("Begin/Endの対応が不正です (行 " + (i + 1) + ")");
				}

			// 開いている定義の内側だけをプロパティの読み込み対象とする。
			} else if (!stack.isEmpty()) {
				Matcher prop = PROPERTY.matcher(line);

				// 代入形式の行から属性名と値を抽出し、現在のノードに保存する。
				if (prop.matches()) {
					// PROPERTYのグループ1は属性名、グループ2は値。
					stack.peek().getProps().put(prop.group(1).trim(), prop.group(2).trim());
				}
			}
		}

		// 閉じられていない開始宣言が残っていれば、不完全な定義として解析を中止する。
		if (!stack.isEmpty()) {
			throw new IOException("BeginとEndが対応していません: " + stack.peek().getName());
		}

		// ルートがVB.Formであることを確認し、フォーム以外の入力を拒否する。
		if (s.getForm() == null || !"VB.Form".equals(s.getForm().getVbType())) {
			throw new IOException("VB.Formが見つかりません");
		}

		// レイアウト以降のVB6コードを、移行レポートに参考掲載するため保存する。
		if (codeStart < lines.length) {
			s.setVb6Code(String.join("\n", Arrays.copyOfRange(lines, codeStart, lines.length)));
		}
		// コントロール階層と元のVB6コードを含む解析結果を、後続の変換処理へ渡す。
		return s;
	}

	/**
	* コントロール名・WinForms型・座標・親子関係を解決する。
	* SSTabでは明示的なTab(n).Controlと兄弟要素から所属を決め、
	* 非表示タブ用の負座標とTabHeightを補正する。
	* @param s 更新する解析・変換状態
	*/
	private static void resolveLayout(Vb6ConversionStateEntity s) {
		// VB6のVB_Name（見つからなければフォーム名）を出力先のクラス名に採用。
		Matcher classMatch = Pattern.compile("(?m)^Attribute VB_Name\\s*=\\s*\"([^\"]+)\"")
		.matcher(s.getVb6Code());
		s.setClassName(toSafeIdentifier(classMatch.find() ? classMatch.group(1) : s.getForm().getName()));

		// フォームの座標単位が前提と異なる場合は、暫定換算であることを警告する。
		if (s.getForm().hasProperty("ScaleMode") && parseInteger(s.getForm().getProperty("ScaleMode"), VB_SCALE_MODE_TWIPS) != VB_SCALE_MODE_TWIPS) {
			s.addWarning("Form.ScaleMode がTwips以外です。座標は96DPIのTwipsとして暫定変換します");
		}

		// 各ノードの識別子・出力型・座標を決定し、変換可否と追加確認事項を記録する。
		for (Vb6FormNodeEntity n : s.getAll()) {
			n.setId(n.getName() + (n.hasProperty("Index") ? "(" + parseInteger(n.getProperty("Index"), 0) + ")" : ""));
			n.setField(toUniqueFieldName(s, toSafeIdentifier(n.getName() + (n.hasProperty("Index")
			? "_" + parseInteger(n.getProperty("Index"), 0) : ""))));
			n.setWinType(toWinFormsControlType(n.getVbType()));

			// 対応するWinForms型がないノードを出力対象から外し、件数と警告を記録する。
			if (n.getWinType() == null) {
				n.setSupported(false);
				s.incrementIgnoredCount();
				s.addWarning(n.getId() + " (" + n.getVbType() + ") はWinForms相当コントロールがなく省略しました");

			// フォーム本体を除き、対応型のあるコントロールを出力件数に数える。
			} else if (n != s.getForm()) {
				s.incrementVisualCount();
			}

			// コントロール配列の個別変換と、配列イベントが別途移植対象であることを知らせる。
			if (n.hasProperty("Index")) {
				s.addWarning(n.getId() + " はVB6のコントロール配列です。WinFormsでは " + n.getField() + " へ個別変換しました。配列イベント処理の移植は別途必要です");
			}
			// 位置・サイズの未指定値は0 Twips。フォームの幅・高さだけはClientWidth・ClientHeightも参照する。
			n.setLeftTwips(parseInteger(n.getProperty("Left"), 0));
			n.setTopTwips(parseInteger(n.getProperty("Top"), 0));
			n.setWidthTwips(parseInteger(n.getProperty("Width"), parseInteger(n.getProperty("ClientWidth"), 0)));
			n.setHeightTwips(parseInteger(n.getProperty("Height"), parseInteger(n.getProperty("ClientHeight"), 0)));
			n.setPixelLeft(toPixels(n.getLeftTwips(), s.getDpi()));
			n.setPixelTop(toPixels(n.getTopTwips(), s.getDpi()));
			n.setPixelWidth(toPixels(n.getWidthTwips(), s.getDpi()));
			n.setPixelHeight(toPixels(n.getHeightTwips(), s.getDpi()));

			// 参照表を更新した結果、同じVB6識別子が存在した場合は重複を警告する。
			if (n.getId() != null && s.getById().put(n.getId(), n) != null) {
				s.addWarning("VB6コントロール識別子が重複しています: " + n.getId());
			}
			// SSTabは後で子要素を適切なTabPageへ移すため、タブ数と見出し高を保持する。

			// SSTabのタブ数と見出し高さを保存し、TabPage生成と座標補正に備える。
			if ("TabDlg.SSTab".equals(n.getVbType())) {
				Vb6TabInfoEntity t = new Vb6TabInfoEntity();
				t.setTab(n);
				// タブ数は少なくとも1枚とし、未指定の場合も1枚を生成する。
				t.setCount(Math.max(1, parseInteger(n.getProperty("Tabs"), 1)));
				t.setTabHeightTwips(parseInteger(n.getProperty("TabHeight"), DEFAULT_TAB_HEIGHT_TWIPS));
				s.getTabs().put(n, t);
			}

			// 画像属性はFRX参照を含む可能性があるため、画像が未移行であることを知らせる。
			if (n.hasProperty("Picture")) {
				s.addWarning(n.getId() + " のPictureは .frx 参照の可能性があります。画像リソースは未移行です");
			}

			// リスト初期値のFRX依存を警告し、移行後のデータ確認を促す。
			if (n.hasProperty("ItemData") || n.hasProperty("List")) {
				s.addWarning(n.getId() + " のリスト初期値はFRXを参照する場合があります。実データは未移行です");
			}

			// コントロールの座標単位がTwips以外なら、子の配置の手動確認を促す。
			if (n.hasProperty("ScaleMode") && parseInteger(n.getProperty("ScaleMode"), VB_SCALE_MODE_TWIPS) != VB_SCALE_MODE_TWIPS) {
				s.addWarning(n.getId() + " のScaleModeはTwipsではありません。子コントロール座標の手動検証が必要です");
			}
		}

		// 各SSTabの明示的な所属情報を読み込み、必要なTabPageを準備する。
		for (Vb6TabInfoEntity ti : s.getTabs().values()) {

			// SSTabの属性から、子コントロールのタブ所属を表す定義を探す。
			for (Map.Entry<String, String> e : ti.getTab().getProps().entrySet()) {
				Matcher match = TAB_CONTROL.matcher(e.getKey());

				// Tab(n).Control(m)の定義を、子識別子からタブ番号を引く対応表に変換する。
				if (match.matches()) {
					// TAB_CONTROLのグループ1は0始まりのタブ番号。グループ2の並び順は所属判定に使わない。
					int tabIdx = Integer.parseInt(match.group(1));
					String original = parseVbString(e.getValue());
					ti.getAssignments().put(original, tabIdx);
				}
			}

			// 宣言されたタブ枚数に合わせて、出力先のTabPageノードを作成する。
			for (int i = 0; i < ti.getCount(); i++) {
				Vb6FormNodeEntity page = new Vb6FormNodeEntity("generated", "WinForms.TabPage", ti.getTab().getName() + "_TabPage" + i, ti.getTab());
				page.setField(toUniqueFieldName(s, toSafeIdentifier(page.getName())));
				page.setWinType("System.Windows.Forms.TabPage");
				page.setTabIndex(i);
				page.setId(page.getName());
				ti.getPages().add(page);
			}

			// タブ見出し画像は復元できないため、移行対象外であることを記録する。
			if (ti.getTab().hasProperty("TabPicture(0)")) {
				s.addWarning(ti.getTab().getId() + " のTabPictureは移行しません（.frx画像未添付）");
			}
		}
		// SSTab直下のコントロールは親をTabPageへ変更してから座標を補正する。
		// Frame等の内側では元の親子関係と相対座標を維持する。

		// 各ノードの出力先の親を確定し、SSTab直下の座標だけを補正する。
		for (Vb6FormNodeEntity n : s.getAll()) {

			// SSTab直下の子を実際のTabPageに割り当て、タブ内の相対座標に直す。
			if (n.getParent() != null && s.getTabs().containsKey(n.getParent())) {
				Vb6TabInfoEntity ti = s.getTabs().get(n.getParent());
				Integer tab = ti.getAssignments().get(n.getId());
				boolean inferred = false;
				// 明示されたタブ所属がない場合、同名コントロール配列から推定する。
				// それでも不明なら符号から暫定配置して警告に記録する。

				// 所属の明示がない子は、同名の配列要素からタブを推定する。
				if (tab == null) {
					// Array siblings can establish otherwise missing tab membership.
					Set<Integer> siblings = new LinkedHashSet<>();

					// 同じSSTabの子を調べ、同名コントロールの既知の所属を収集する。
					for (Vb6FormNodeEntity other : n.getParent().getChildren()) {

						// 自分自身と別名のコントロールは、所属を推定する根拠から除外する。
						if (other == n || !other.getName().equals(n.getName())) {
							continue;
						}
						Integer assigned = ti.getAssignments().get(other.getId());

						// 明示された所属だけを推定の根拠として集め、未割り当て要素は使わない。
						if (assigned != null) {
							siblings.add(assigned);
						}
					}

					// 同名要素の所属が一つに定まる場合は、そのタブを推定先として採用する。
					if (siblings.size() == 1) {
						tab = siblings.iterator().next();
					} else {
						// 0は先頭、1は2枚目のタブ。負の退避座標は2枚目を候補とし、1枚構成なら先頭に収める。
						tab = n.getLeftTwips() >= 0 ? 0 : Math.min(1, ti.getCount() - 1);
					}
					inferred = true;
				}

				// 範囲外のタブ番号は警告したうえでTab0に補正し、ページ参照エラーを防ぐ。
				if (tab < 0 || tab >= ti.getCount()) {
					s.addWarning(n.getId() + " のタブ番号が不正です: " + tab + "。Tab0に置き換えます");
					tab = 0;
				}
				n.setTabIndex(tab);

				// 推定配置の件数と根拠不足を記録し、移行後に確認できるようにする。
				if (inferred) {
					s.incrementUnknownTabCount();
					s.addWarning(n.getId() + " はTab(n).Control一覧にありません。タブ" + tab + " に推定配置しました（要確認）");
				}
				// SSTabで非表示のタブを左へ追い出した大きな負座標だけを補正する。
				// 通常の小さな負座標は意図的な位置として扱い、補正しない。
				long correctedLeft = n.getLeftTwips() < HIDDEN_TAB_LEFT_THRESHOLD_TWIPS
				? (long) n.getLeftTwips() + SSTAB_HIDE_TWIPS : n.getLeftTwips();

				// 非表示タブの退避座標を補正したノードだけを、補正件数に加算する。
				if (correctedLeft != n.getLeftTwips()) {
					s.incrementRemappedTabCount();
				}
				n.setPixelLeft(toPixels(correctedLeft, s.getDpi()));
				// VB6ではタブ見出し基準、WinForms TabPageでは内容領域基準なので、
				// TabHeightを引いてY座標を揃える。
				long correctedTop = (long) n.getTopTwips() - ti.getTabHeightTwips();
				// Some VB6 controls begin slightly above the tab header baseline.

				// 見出し付近の小さな負の位置を原点へ丸め、TabPage内でのはみ出しを抑える。
				if (correctedTop < 0 && correctedTop > TAB_TOP_CLAMP_LIMIT_TWIPS) {
					correctedTop = 0;
				}
				n.setPixelTop(toPixels(correctedTop, s.getDpi()));
				n.setResolvedParent(ti.getPages().get(tab).getField());

			// SSTab以外の子は、元の親をそのまま出力先の親として保持する。
			} else if (n.getParent() != null) {
				n.setResolvedParent(n.getParent().getField());
			}
		}
	}

	/**
	* フィールド名を大文字小文字を区別せず一意にする。
	* @param s 使用済み名前の管理状態
	* @param requested フィールド名候補
	* @return 必要に応じて連番を付けたフィールド名
	*/
	private static String toUniqueFieldName(Vb6ConversionStateEntity s, String requested) {
		String name = requested;
		// 元の候補名を最初に試すため、重複時の連番は2から開始する。
		int i = 2;
		// 大文字小文字を区別しない名前の登録に成功するまで、連番付きの候補を試す。
		while (!s.getUsedFields().add(name.toLowerCase(Locale.ROOT))) {
			name = requested + "_" + i++;
		}
		// 使用済み名称との重複を解消し、登録できたフィールド名を返す。
		return name;
	}

	/**
	* VB6名の使用できない文字と予約語を処理してVB.NET識別子へ正規化する。
	* @param name 元の名前
	* @return VB.NET識別子の候補
	*/
	private static String toSafeIdentifier(String name) {
		String result = name.replaceAll("[^\\p{L}\\p{N}_]", "_");

		// 名前が空になった場合も、有効な識別子の候補を用意する。
		if (result.isEmpty()) {
			result = "Unnamed";
		}

		// 数字で始まる名前に接頭辞を付け、VB.NETの識別子として使える形にする。
		if (Character.isDigit(result.charAt(0))) {
			result = "ctl_" + result;
		}
		// Avoid collision with VB.NET keywords while preserving original-ish designer names.
		Set<String> keywords = Set.of("Class", "End", "New", "If", "Then", "Else", "Module", "Sub", "Me", "Public", "Private", "Friend", "Partial", "Namespace", "Property", "Integer", "String");

		// 予約語と同じ名前には接頭辞を付け、生成コードでの構文衝突を避ける。
		if (keywords.contains(result)) {
			result = "ctl_" + result;
		}
		// 使用できない文字・先頭の数字・予約語を処理した識別子を返す。
		return result;
	}

	/**
	* VB6のコントロール型をWinFormsの完全修飾クラス名へ割り当てる。
	* COM固有の処理は移行しない。
	* @param type VB6型名
	* @return WinForms型名。
	* 非対応ならnull
	*/
	private static String toWinFormsControlType(String type) {
		// VB6の型に対応するWinForms型を返し、未対応の型にはnullを返す。
		return switch (type) {
			case "VB.Form" -> "System.Windows.Forms.Form";
			case "TabDlg.SSTab" -> "System.Windows.Forms.TabControl";
			case "VB.Frame" -> "System.Windows.Forms.GroupBox";
			case "VB.PictureBox" -> "System.Windows.Forms.Panel";
			case "VB.Image" -> "System.Windows.Forms.PictureBox";
			case "VB.Label" -> "System.Windows.Forms.Label";
			case "VB.TextBox" -> "System.Windows.Forms.TextBox";
			case "VB.CommandButton" -> "System.Windows.Forms.Button";
			case "VB.CheckBox" -> "System.Windows.Forms.CheckBox";
			case "VB.OptionButton" -> "System.Windows.Forms.RadioButton";
			case "VB.ComboBox" -> "System.Windows.Forms.ComboBox";
			case "VB.ListBox" -> "System.Windows.Forms.ListBox";
			case "MSComctlLib.ProgressBar" -> "System.Windows.Forms.ProgressBar";
			case "MSComctlLib.ListView" -> "System.Windows.Forms.ListView";
			case "MSComctlLib.ImageList" -> "System.Windows.Forms.ImageList";
			case "MSComDlg.CommonDialog" -> "System.Windows.Forms.OpenFileDialog";
			case "SHDocVwCtl.WebBrowser" -> "System.Windows.Forms.WebBrowser";
			default -> null;
		};
	}

	/**
	* 数値プロパティを読み取り、末尾のVB6コメントを取り除く。
	* @param value 属性値。
	* 未設定ならnull
	* @param fallback 解析失敗時の既定値
	* @return 数値化した属性値
	*/
	private static int parseInteger(String value, int fallback) {

		// 未設定の数値属性には、呼び出し元が指定した既定値を使う。
		if (value == null) {
			// 属性が未設定の場合は、呼び出し元が指定した既定値を返す。
			return fallback;
		}
		try {
			// 最大2分割の先頭（値部分）から空白を除き、整数として読み取った値を返す。
			return Integer.parseInt(value.split("\\s+'", 2)[0].trim());
		} catch (NumberFormatException ex) {
			// 整数として解釈できない値は、呼び出し元が指定した既定値に置き換える。
			return fallback;
		}
	}

	/**
	* Twipsを指定DPIに対応するピクセルへ換算する。
	* 1inch=1440Twips。
	* @param twips 元座標またはサイズ
	* @param dpi 変換先のDPI
	* @return 四捨五入したピクセル値
	*/
	private static int toPixels(long twips, double dpi) {
		// Twipsを指定DPIで換算し、最も近い整数ピクセル値を返す。
		return (int) Math.round(twips * dpi / TWIPS_PER_INCH);
	}

	/**
	* VB6文字列リテラルを取り出し、二重の引用符を元に戻す。
	* @param vbValue .frmの元の値
	* @return デコードした文字列。
	* nullの場合は空文字列
	*/
	private static String parseVbString(String vbValue) {

		// 未設定の文字列属性を空文字列として扱い、後続の文字列処理を簡潔にする。
		if (vbValue == null) {
			// 未設定の文字列属性は、後続処理で扱える空文字列として返す。
			return "";
		}
		String t = vbValue.trim();

		// 最大2分割の先頭を取り出し、末尾のVB6コメントを除いた値を返す。
		if (!t.startsWith("\"")) {
			// 最大2分割の先頭を取り出し、末尾のVB6コメントを除いた値を返す。
			return t.split("\\s+'", 2)[0];
		}
		// 引用符のエスケープを復元した、VB6文字列属性の本文を蓄積する。
		StringBuilder b = new StringBuilder();

		// 開始引用符の次から読み、引用符のエスケープと終端を区別する。
		for (int i = 1; i < t.length(); i++) {
			char c = t.charAt(i);

			// 引用符を見つけたら、文字として残すか文字列の終端とするかを判定する。
			if (c == '"') {

				// 連続する二つの引用符を一文字に復元し、二文字目を読み飛ばす。
				if (i + 1 < t.length() && t.charAt(i + 1) == '"') {
					b.append('"');
					i++;
				} else {
					break;
				}
			} else {
				b.append(c);
			}
		}
		// 終端まで読み取り、二重引用符のエスケープを復元した文字列を返す。
		return b.toString();
	}

	/**
	* 文字列をVB.NETの引用符・改行を適切に含む式へ変換する。
	* @param javaString 変換元文字列
	* @return VB.NET文字列式
	*/
	private static String toVbStringLiteral(String javaString) {
		// 引用符と改行をVB.NETで表現できる形に置き換えた文字列式を返す。
		return "\"" + javaString.replace("\"", "\"\"").replace("\r", "")
		.replace("\n", "\" & vbCrLf & \"") + "\"";
	}

	/**
	* 数値で記述されたVB6真偽値を判定する。
	* @param value VB6属性値
	* @return 非ゼロならtrue
	*/
	private static boolean isVbTrue(String value) {
		// VB6の数値が0以外なら真とし、未設定や不正値は偽として返す。
		return parseInteger(value, 0) != 0;
	}

	/**
	* JavaのbooleanをVB.NETリテラルへ変換する。
	* @param value 真偽値
	* @return TrueまたはFalse
	*/
	private static String toVbBooleanLiteral(boolean value) {
		// Javaの真偽値に対応する、VB.NETのTrueまたはFalseを返す。
		return value ? "True" : "False";
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
	* 文字列バッファに1行をCRLF付きで追記する。
	* @param b 生成するVB.NETコードや監査資料の行を蓄積する文字列バッファ
	* @param s 追記する内容
	*/
	private static void appendLine(StringBuilder b, String s) {
		b.append(s).append("\r\n");
	}

	/**
	* VB.NETのコントロールプロパティ代入文を生成する。
	* @param b 生成するVB.NETコードや監査資料の行を蓄積する文字列バッファ
	* @param n コントロールノード
	* @param property プロパティ名
	* @param expression 右辺のVB.NET式
	*/
	private static void appendPropertyAssignment(StringBuilder b, Vb6FormNodeEntity n, String property, String expression) {
		appendLine(b, "        " + toPropertyTarget(n) + "." + property + " = " + expression);
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
	* @param s 変換状態
	*/
	private static void appendControlProperties(StringBuilder out, Vb6FormNodeEntity n, Vb6ConversionStateEntity s) {

		// 未対応ノードとフォーム本体は、子コントロール用の属性出力から除外する。
		if (!n.isSupported() || n == s.getForm()) {
			// 未対応ノードとフォーム本体は、子コントロール用の属性を出力せず終了する。
			return;
		}

		// ImageListなどの非表示部品には、位置やサイズなどの画面属性を出力しない。
		if (isNonVisual(n)) {
			// 非表示部品には画面上の位置やサイズを設定せず、属性出力を終了する。
			return;
		}
		appendPropertyAssignment(out, n, "Name", toVbStringLiteral(n.getField()));
		appendPropertyAssignment(out, n, "Location", "New System.Drawing.Point(" + n.getPixelLeft() + ", " + n.getPixelTop() + ")");

		// 元定義にサイズ指定がある場合だけ、最小1ピクセルの表示サイズを設定する。
		if (n.hasProperty("Width") || n.hasProperty("Height")) {
			appendPropertyAssignment(out, n, "Size", "New System.Drawing.Size(" + Math.max(1, n.getPixelWidth()) + ", " + Math.max(1, n.getPixelHeight()) + ")");
		}

		// VB6のCaptionを、WinFormsで表示するTextに引き継ぐ。
		if (n.hasProperty("Caption")) {
			appendPropertyAssignment(out, n, "Text", toVbStringLiteral(parseVbString(n.getProperty("Caption"))));
		}

		// TextBox以外のText属性を引き継ぎ、TextBox固有の出力処理との重複を避ける。
		if (n.hasProperty("Text") && !n.getVbType().equals("VB.TextBox")) {
			appendPropertyAssignment(out, n, "Text", toVbStringLiteral(parseVbString(n.getProperty("Text"))));
		}

		// 表示・非表示の明示指定がある場合だけ、元の状態を引き継ぐ。
		if (n.hasProperty("Visible")) {
			appendPropertyAssignment(out, n, "Visible", toVbBooleanLiteral(isVbTrue(n.getProperty("Visible"))));
		}

		// 操作可否の明示指定がある場合だけ、元のEnabled状態を引き継ぐ。
		if (n.hasProperty("Enabled")) {
			appendPropertyAssignment(out, n, "Enabled", toVbBooleanLiteral(isVbTrue(n.getProperty("Enabled"))));
		}

		// 明示されたタブ移動順を数値として出力する。
		if (n.hasProperty("TabIndex")) {
			// TabIndexは0始まり。未指定の場合は先頭の0を使う。
			appendPropertyAssignment(out, n, "TabIndex", Integer.toString(parseInteger(n.getProperty("TabIndex"), 0)));
		}
		// フォント名・ポイント数・太字などはBeginProperty Fontから復元する。
		Vb6FormNodeEntity font = n.getNested().get("Font");

		// 複合属性Fontがある場合だけ、名前・サイズ・装飾・文字セットを復元する。
		if (font != null) {
			String fontName = parseVbString(font.getProperty("Name"));

			// フォント名が空の場合は、生成コードで使用する既定フォントを補う。
			if (fontName.isEmpty()) {
				fontName = "MS UI Gothic";
			}
			String fontSize = font.getProperty("Size") == null ? DEFAULT_FONT_SIZE_POINTS : font.getProperty("Size").trim();
			try {
				new BigDecimal(fontSize);
			} catch (NumberFormatException ex) {
				fontSize = DEFAULT_FONT_SIZE_POINTS;
			}
			List<String> styles = new ArrayList<>();

			// VB6の太字に相当するWeightなら、出力するフォント装飾にBoldを加える。
			if (parseInteger(font.getProperty("Weight"), DEFAULT_FONT_WEIGHT) >= BOLD_FONT_WEIGHT) {
				styles.add("System.Drawing.FontStyle.Bold");
			}

			// 斜体指定を、他の装飾と併用できるフォントスタイルとして追加する。
			if (isVbTrue(font.getProperty("Italic"))) {
				styles.add("System.Drawing.FontStyle.Italic");
			}

			// 下線指定を、出力するフォントスタイルに追加する。
			if (isVbTrue(font.getProperty("Underline"))) {
				styles.add("System.Drawing.FontStyle.Underline");
			}

			// 取り消し線指定を、WinFormsのStrikeoutに引き継ぐ。
			if (isVbTrue(font.getProperty("Strikethrough"))) {
				styles.add("System.Drawing.FontStyle.Strikeout");
			}
			String style = styles.isEmpty() ? "System.Drawing.FontStyle.Regular" : String.join(" Or ", styles);
			// Charset未指定時の0はANSI文字セット。CByteの範囲に収めて生成する。
			int charset = parseInteger(font.getProperty("Charset"), 0);
			appendPropertyAssignment(out, n, "Font", "New System.Drawing.Font(" + toVbStringLiteral(fontName) + ", CSng(" + fontSize
			+ "), " + style + ", System.Drawing.GraphicsUnit.Point, CByte(" + Math.max(0, Math.min(UNSIGNED_BYTE_MAX, charset)) + "))");
		}

		// 背景色が指定されている場合だけ、OLE_COLORからの変換を試みる。
		if (n.hasProperty("BackColor")) {
			String color = toColor(n.getProperty("BackColor"));

			// 変換できた背景色だけを出力し、未対応色による不正な式を生成しない。
			if (color != null) {
				appendPropertyAssignment(out, n, "BackColor", color);
			}
		}

		// 文字色が指定されている場合だけ、OLE_COLORからの変換を試みる。
		if (n.hasProperty("ForeColor")) {
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
				boolean autoSize = n.hasProperty("AutoSize")
				? isVbTrue(n.getProperty("AutoSize"))
				: !parseVbString(n.getProperty("Caption")).isBlank() && !isVbTrue(n.getProperty("WordWrap"));
				appendPropertyAssignment(out, n, "AutoSize", toVbBooleanLiteral(autoSize));

				// ラベルの配置指定を、WinFormsの文字揃えに変換する。
				if (n.hasProperty("Alignment")) {
					// Alignmentの対応値は0が左寄せ、1が右寄せ、2が中央寄せ。未指定は0。
					appendPropertyAssignment(out, n, "TextAlign", switch (parseInteger(n.getProperty("Alignment"), 0)) {
						case 1 -> "System.Drawing.ContentAlignment.MiddleRight";
						case 2 -> "System.Drawing.ContentAlignment.MiddleCenter";
						default -> "System.Drawing.ContentAlignment.MiddleLeft";
					});
				}
			}
			case "VB.TextBox" -> {

				// TextBoxの初期入力文字列を、VB.NETの文字列式として出力する。
				if (n.hasProperty("Text")) {
					appendPropertyAssignment(out, n, "Text", toVbStringLiteral(parseVbString(n.getProperty("Text"))));
				}

				// 入力ロック指定をReadOnlyに変換し、元の編集可否を引き継ぐ。
				if (n.hasProperty("Locked")) {
					appendPropertyAssignment(out, n, "ReadOnly", toVbBooleanLiteral(isVbTrue(n.getProperty("Locked"))));
				}

				// 入力文字数の上限が指定されている場合だけ、MaxLengthを設定する。
				if (n.hasProperty("MaxLength")) {
					appendPropertyAssignment(out, n, "MaxLength", Integer.toString(parseInteger(n.getProperty("MaxLength"), DEFAULT_TEXT_MAX_LENGTH)));
				}

				// 複数行入力の指定を、WinFormsのMultilineに引き継ぐ。
				if (n.hasProperty("MultiLine")) {
					appendPropertyAssignment(out, n, "Multiline", toVbBooleanLiteral(isVbTrue(n.getProperty("MultiLine"))));
				}

				// スクロールバーの指定値を、WinFormsの列挙値へ変換する。
				if (n.hasProperty("ScrollBars")) {
					// ScrollBarsの既存対応表は0がなし、1が縦、2が横、3が両方。
					String bars = switch (parseInteger(n.getProperty("ScrollBars"), 0)) {
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
					appendPropertyAssignment(out, n, "Checked", toVbBooleanLiteral(isVbTrue(n.getProperty("Value"))));
				}
			}
			case "VB.ComboBox" -> {

				// コンボボックスの入力方式を、WinFormsのDropDownStyleへ変換する。
				if (n.hasProperty("Style")) {
					// ComboBox.Styleの対応値は0が通常、1が単純、2が選択専用。
					String style = switch (parseInteger(n.getProperty("Style"), 0)) {
						case 1 -> "Simple";
						case 2 -> "DropDownList";
						default -> "DropDown";
					};
					appendPropertyAssignment(out, n, "DropDownStyle", "System.Windows.Forms.ComboBoxStyle." + style);
				}
			}
			case "VB.Image" -> {
				String mode = isVbTrue(n.getProperty("Stretch")) ? "StretchImage" : "Normal";
				appendPropertyAssignment(out, n, "SizeMode", "System.Windows.Forms.PictureBoxSizeMode." + mode);
			}
			case "VB.ListBox" -> {

				// リストの複数選択方式を、WinFormsのSelectionModeへ変換する。
				if (n.hasProperty("MultiSelect")) {
					// MultiSelectの対応値は0が単一選択、1が単純な複数選択、2が拡張複数選択。
					String mode = switch (parseInteger(n.getProperty("MultiSelect"), 0)) {
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
					"System.Windows.Forms.BorderStyle." + (parseInteger(n.getProperty("BorderStyle"), 0) == 0 ? "None" : "FixedSingle"));
				}
			}
			case "MSComctlLib.ListView" -> {
				// View未指定時は既存の既定値3を使う。下の対応表でWinFormsの表示方式を選ぶ。
				int view = parseInteger(n.getProperty("View"), 3);
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
		// OLE_COLORは下位からR・G・B各8ビット。16ビットと8ビットのシフトでBとGを抽出する。
		int b = (int) ((value >> 16) & UNSIGNED_BYTE_MAX), g = (int) ((value >> 8) & UNSIGNED_BYTE_MAX), r = (int) (value & UNSIGNED_BYTE_MAX);
		// BGRの各成分をRGB順に並べ、WinFormsの色生成式として返す。
		return "System.Drawing.Color.FromArgb(" + r + ", " + g + ", " + b + ")";
	}

	/**
	* WinFormsのDesigner.vbを生成する。
	* コントロール生成、プロパティ設定、TabPageと親子関係の割当、
	* フィールド宣言を順に行う。
	* @param s 解決済み変換状態
	* @return Designer.vb全文
	*/
	private static String toDesignerSource(Vb6ConversionStateEntity s) {
		// 生成するDesigner.vbの全文（初期化処理・プロパティ・フィールド宣言）を蓄積する。
		StringBuilder out = new StringBuilder();
		appendLine(out, "' 自動生成: Vb6WinFormsConverter (Java 21) / レイアウト変換のみ");
		appendLine(out, "' 自動生成されたため手作業変更は避けてください。制限は migration_report.txt を参照。");
		appendLine(out, "<Global.Microsoft.VisualBasic.CompilerServices.DesignerGenerated()> _");
		appendLine(out, "Partial Class " + s.getClassName());
		appendLine(out, "    Inherits System.Windows.Forms.Form");
		appendLine(out, "");
		appendLine(out, "    <Global.System.Diagnostics.DebuggerNonUserCode()> _");
		appendLine(out, "    Protected Overrides Sub Dispose(disposing As Boolean)");
		appendLine(out, "        Try");
		appendLine(out, "            If disposing AndAlso components IsNot Nothing Then components.Dispose()");
		appendLine(out, "        Finally");
		appendLine(out, "            MyBase.Dispose(disposing)");
		appendLine(out, "        End Try");
		appendLine(out, "    End Sub");
		appendLine(out, "");
		appendLine(out, "    Private components As System.ComponentModel.IContainer");
		appendLine(out, "");
		appendLine(out, "    <Global.System.Diagnostics.DebuggerStepThrough()> _");
		appendLine(out, "    Private Sub InitializeComponent()");
		appendLine(out, "        Me.components = New System.ComponentModel.Container()");
		// 先にコントロールの実体をすべて作り、Controls.Addが参照できるようにする。

		// 属性や親子関係の設定より先に、出力対象コントロールの実体を生成する。
		for (Vb6FormNodeEntity n : s.getAll()) {

			// フォーム本体と未対応要素を除き、子コントロールの生成式を出力する。
			if (n != s.getForm() && n.isSupported()) {

				// ImageListはフォームのコンポーネント管理に登録し、破棄処理の対象とする。
				if (n.getWinType().endsWith(".ImageList")) {
					appendLine(out, "        Me." + n.getField() + " = New " + n.getWinType() + "(Me.components)");
				} else {
					appendLine(out, "        Me." + n.getField() + " = New " + n.getWinType() + "()");
				}
			}
		}

		// 各SSTabで必要になるTabPageの生成式を出力する。
		for (Vb6TabInfoEntity t : s.getTabs().values()) {

			// タブごとのページ実体を、コントロール追加より先に生成する。
			for (Vb6FormNodeEntity p : t.getPages()) {
				appendLine(out, "        Me." + p.getField() + " = New System.Windows.Forms.TabPage()");
			}
		}
		appendLine(out, "        Me.SuspendLayout()");
		// 次に位置・サイズ・表示文字などの基本属性を設定する。

		// 生成済みコントロールに位置・サイズ・表示属性を順番に設定する。
		for (Vb6FormNodeEntity n : s.getAll()) {

			// フォーム本体以外の対応済みコントロールだけに、個別の属性を出力する。
			if (n.isSupported() && n != s.getForm()) {
				appendControlProperties(out, n, s);
			}
		}

		// SSTabごとにページ見出しと、タブ本体の表示設定を出力する。
		for (Vb6TabInfoEntity ti : s.getTabs().values()) {

			// 各TabPageに識別名・見出し文字・背景表示を設定する。
			for (Vb6FormNodeEntity page : ti.getPages()) {
				int i = page.getTabIndex();
				appendPropertyAssignment(out, page, "Name", toVbStringLiteral(page.getField()));
				appendPropertyAssignment(out, page, "Text", toVbStringLiteral(parseVbString(ti.getTab().getProperty("TabCaption(" + i + ")"))));
				appendPropertyAssignment(out, page, "UseVisualStyleBackColor", "True");
			}
			appendPropertyAssignment(out, ti.getTab(), "Multiline", "True");
			appendPropertyAssignment(out, ti.getTab(), "SizeMode", "System.Windows.Forms.TabSizeMode.Fixed");
			appendPropertyAssignment(out, ti.getTab(), "ItemSize", "New System.Drawing.Size(" + toPixels(TAB_HEADER_WIDTH_TWIPS, s.getDpi()) + ", " + Math.max(1, toPixels(ti.getTabHeightTwips(), s.getDpi())) + ")");
		}
		// ネストしたFrame等への追加と、SSTab直下のTabPageへの追加を行う。

		// フォーム直下以外のコントロールを、対応するコンテナに追加する。
		for (Vb6FormNodeEntity n : s.getAll()) {

			// フォーム直下の要素や画面に配置しない要素を、ネスト追加処理から除外する。
			if (n == s.getForm() || n.getParent() == s.getForm() || !n.isSupported() || isNonVisual(n)) {
				continue;
			}

			// SSTab直下の子は、所属を解決したTabPageのControlsに追加する。
			if (s.getTabs().containsKey(n.getParent())) {
				// Tab pages may be omitted if a tab control is skipped in the future.
				appendLine(out, "        Me." + n.getResolvedParent() + ".Controls.Add(Me." + n.getField() + ")");

			// 対応する通常コンテナが親なら、そのControlsに追加して階層を保持する。
			} else if (n.getParent() != null && n.getParent().isSupported()) {
				appendLine(out, "        Me." + n.getParent().getField() + ".Controls.Add(Me." + n.getField() + ")");
			}
		}

		// 生成済みのTabPageを、それぞれのTabControlへ登録する。
		for (Vb6TabInfoEntity ti : s.getTabs().values()) {

			// ページの並び順を保って、TabPagesへの追加式を出力する。
			for (Vb6FormNodeEntity page : ti.getPages()) {
				appendLine(out, "        Me." + ti.getTab().getField() + ".TabPages.Add(Me." + page.getField() + ")");
			}
		}
		// フォーム内部のサイズを設定。AutoScaleMode=NoneでDPI自動拡大を防ぐ。
		appendPropertyAssignment(out, s.getForm(), "Name", toVbStringLiteral(s.getClassName()));
		appendPropertyAssignment(out, s.getForm(), "AutoScaleMode", "System.Windows.Forms.AutoScaleMode.None");
		// ClientWidth・ClientHeightが未指定の場合は0 Twipsとして扱う。
		appendPropertyAssignment(out, s.getForm(), "ClientSize", "New System.Drawing.Size(" + toPixels(parseInteger(s.getForm().getProperty("ClientWidth"), 0), s.getDpi()) + ", "
		+ toPixels(parseInteger(s.getForm().getProperty("ClientHeight"), 0), s.getDpi()) + ")");
		appendPropertyAssignment(out, s.getForm(), "Text", toVbStringLiteral(parseVbString(s.getForm().getProperty("Caption"))));

		// フォームの枠形式が指定されている場合だけ、FormBorderStyleに変換する。
		if (s.getForm().hasProperty("BorderStyle")) {
			// フォームの枠は0なし・1固定・2可変・3ダイアログ・4固定ツール・5可変ツール。未指定は2。
			String border = switch (parseInteger(s.getForm().getProperty("BorderStyle"), 2)) {
				case 0 -> "None";
				case 1 -> "FixedSingle";
				case 3 -> "FixedDialog";
				case 4 -> "FixedToolWindow";
				case 5 -> "SizableToolWindow";
				default -> "Sizable";
			};
			appendPropertyAssignment(out, s.getForm(), "FormBorderStyle", "System.Windows.Forms.FormBorderStyle." + border);
		}

		// 最大化ボタンの有無を、フォームのMaximizeBoxへ引き継ぐ。
		if (s.getForm().hasProperty("MaxButton")) {
			appendPropertyAssignment(out, s.getForm(), "MaximizeBox", toVbBooleanLiteral(isVbTrue(s.getForm().getProperty("MaxButton"))));
		}

		// 最小化ボタンの有無を、フォームのMinimizeBoxへ引き継ぐ。
		if (s.getForm().hasProperty("MinButton")) {
			appendPropertyAssignment(out, s.getForm(), "MinimizeBox", toVbBooleanLiteral(isVbTrue(s.getForm().getProperty("MinButton"))));
		}

		// 起動時の配置方式を、WinFormsのStartPositionへ変換する。
		if (s.getForm().hasProperty("StartUpPosition")) {
			// 起動位置は0手動・1親の中央・2画面中央・3既定位置。それ以外は既定の位置とサイズ。
			String start = switch (parseInteger(s.getForm().getProperty("StartUpPosition"), 0)) {
				case 1 -> "CenterParent";
				case 2 -> "CenterScreen";
				case 3 -> "WindowsDefaultLocation";
				case 0 -> "Manual";
				default -> "WindowsDefaultBounds";
			};
			appendPropertyAssignment(out, s.getForm(), "StartPosition", "System.Windows.Forms.FormStartPosition." + start);
		}

		// フォーム直下の子を調べ、ルートのControlsへの追加式を生成する。
		for (Vb6FormNodeEntity child : s.getForm().getChildren()) {

			// 対応済みの表示コントロールだけを、フォーム直下のControlsに追加する。
			if (child.isSupported() && !isNonVisual(child)) {
				appendLine(out, "        Me.Controls.Add(Me." + child.getField() + ")");
			}
		}
		appendLine(out, "        Me.ResumeLayout(False)");
		appendLine(out, "    End Sub");
		appendLine(out, "");

		// 生成対象コントロールを、コードビハインドから参照するフィールドとして宣言する。
		for (Vb6FormNodeEntity n : s.getAll()) {

			// フォーム本体と未対応要素を除き、WithEvents付きのフィールドを出力する。
			if (n != s.getForm() && n.isSupported()) {
				appendLine(out, "    Friend WithEvents " + n.getField() + " As " + n.getWinType());
			}
		}

		// 自動生成したTabPageについても、参照用フィールドを宣言する。
		for (Vb6TabInfoEntity t : s.getTabs().values()) {

			// 各ページのフィールド名と型を、クラス末尾の宣言に出力する。
			for (Vb6FormNodeEntity p : t.getPages()) {
				appendLine(out, "    Friend WithEvents " + p.getField() + " As " + p.getWinType());
			}
		}
		appendLine(out, "End Class");
		// コントロールの生成・属性・親子関係・フィールド宣言を含むDesigner全文を返す。
		return out.toString();
	}

	/**
	* VB6コードから単純なPublicフィールドのみを抽出する。
	* イベントや業務ロジックは移植しない。
	* @param code 元VB6コード
	* @return VB.NETのPublicフィールド宣言
	*/
	private static String toPublicFieldDeclarations(String code) {
		// 元VB6コードから抽出したPublicフィールドのVB.NET宣言文を蓄積する。
		StringBuilder output = new StringBuilder();
		Matcher matcher = Pattern.compile("(?m)^Public\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+As\\s+(Boolean|Integer|Long|String|Double|Single)\\s*$", Pattern.CASE_INSENSITIVE).matcher(code);
		// 単純なPublicフィールド宣言を順に抽出し、VB.NETの宣言文へ変換する。
		while (matcher.find()) {
			// 正規表現のグループ1はフィールド名、グループ2は対応可能な基本型名。
			output.append("    Public ").append(matcher.group(1)).append(" As ").append(matcher.group(2)).append("\r\n");
		}
		// 抽出できたPublicフィールドの宣言を連結して返し、該当がなければ空文字列を返す。
		return output.toString();
	}

	/**
	* CSVフィールドを引用符で囲み、内部の引用符をエスケープする。
	* @param value 出力する値
	* @return CSV用にエスケープした値
	*/
	private static String toCsvField(String value) {
		// 内部の引用符を二重化し、全体を引用符で囲んだCSVフィールドを返す。
		return "\"" + value.replace("\"", "\"\"") + "\"";
	}

	/**
	* 元のTwips座標、換算後のPixel座標、親子関係などをCSVに出力する。
	* @param s 変換状態
	* @return 座標監査CSV全文
	*/
	private static String toCoordinateAuditCsv(Vb6ConversionStateEntity s) {
		// 元座標・変換後座標・親子関係などの監査CSV全文を蓄積する。
		StringBuilder b = new StringBuilder();
		appendLine(b, "VB6Type,VB6Name,WinFormsType,WinFormsField,VB6Parent,WinFormsParent,TabIndex,LeftTwips,TopTwips,WidthTwips,HeightTwips,PixelX,PixelY,PixelWidth,PixelHeight,Output");

		// 各ノードの変換前後の座標と親子関係を、一件ずつ監査CSVに記録する。
		for (Vb6FormNodeEntity n : s.getAll()) {
			String parent = n.getParent() == null ? "" : n.getParent().getId();
			String resolved = n.getResolvedParent().isEmpty() ? (n.getParent() == null ? "" : n.getParent().getField()) : n.getResolvedParent();
			String[] v = {
				n.getVbType(), n.getId(), n.getWinType() == null ? "" : n.getWinType(), n.getField(), parent, resolved,
				n.getTabIndex() < 0 ? "" : Integer.toString(n.getTabIndex()),
				Integer.toString(n.getLeftTwips()), Integer.toString(n.getTopTwips()), Integer.toString(n.getWidthTwips()), Integer.toString(n.getHeightTwips()),
				Integer.toString(n.getPixelLeft()), Integer.toString(n.getPixelTop()), Integer.toString(n.getPixelWidth()), Integer.toString(n.getPixelHeight()),
				n.isSupported() ? "YES" : "NO"
			};
			appendLine(b, String.join(",", Arrays.stream(v).map(Vb6WinFormsConverter::toCsvField).toList()));
		}
		// すべてのノードの変換前後の座標と親子関係を記録した監査CSVを返す。
		return b.toString();
	}

	/**
	* 変換条件、補正・推定件数、警告、未移植コードをレポートに出力する。
	* @param s 変換状態
	* @param source 入力.frmパス
	* @return 移行レポート全文
	*/
	private static String toMigrationReport(Vb6ConversionStateEntity s, Path source) {
		// 変換条件・警告・未移植コードを記載する移行レポート全文を蓄積する。
		StringBuilder b = new StringBuilder();
		appendLine(b, "VB6 -> VB.NET WinForms 座標変換レポート");
		appendLine(b, "入力: " + source.getFileName());
		appendLine(b, "フォーム: " + s.getClassName());
		appendLine(b, "DPI: " + s.getDpi() + " (1px = " + TWIPS_PER_INCH / s.getDpi() + " Twips)");
		appendLine(b, "フォームを含む定義数: " + s.getAll().size());
		appendLine(b, "SSTab非表示座標補正数: " + s.getRemappedTabCount());
		appendLine(b, "タブ割り当て推定数: " + s.getUnknownTabCount());
		appendLine(b, "警告数: " + s.getWarnings().size());
		appendLine(b, "");
		appendLine(b, "移行範囲: フォーム配置、標準コントロール、Frame親子構造、SSTab->TabControl、基本的な属性とフォント。");
		appendLine(b, "制限: VB6実行コード・イベント・コントロール配列イベント・COMオブジェクトの動作は自動変換しません。");
		appendLine(b, "制限: .frx は添付されていないため Picture/TabPicture/ItemData/ImageList画像を復元しません。");
		appendLine(b, "制限: 元フォーム内の ScaleMode はTwips（標準）を前提としています。");
		appendLine(b, "制限: SSTabのY座標はTabHeightを引き、負の小さい値は0に丸めます。手動で見た目を確認してください。");
		appendLine(b, "制限: Form.AutoScaleMode=NoneのためDPI倍率・フォント環境で見た目がずれることがあります。");
		appendLine(b, "注意: VB.NETの.Windows.Forms.Formの自動生成レイアウトとしてVisual Studioで開けますが、未サポート要素は警告を確認してください。");
		appendLine(b, "");
		appendLine(b, "=== 警告 ===");

		// 警告が一件もない場合は、空欄ではなく「なし」と明示する。
		if (s.getWarnings().isEmpty()) {
			appendLine(b, "なし");
		}

		// 収集済みの警告を登録順に出力し、未対応事項や推定配置を確認できるようにする。
		for (String warn : s.getWarnings()) {
			appendLine(b, warn);
		}
		appendLine(b, "");
		appendLine(b, "=== 元.frm内のコード（参考・未移植） ===");
		appendLine(b, s.getVb6Code());
		// 変換条件・警告・参考用の元コードをまとめた移行レポートを返す。
		return b.toString();
	}
}
