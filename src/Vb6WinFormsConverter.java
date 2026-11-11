import static constants.Vb6ConversionConstants.DEFAULT_DPI;
import static constants.Vb6ConversionConstants.MAX_DPI;
import static constants.Vb6ConversionConstants.MIN_DPI;
import static constants.Vb6ConversionConstants.REPORTS_DIRECTORY;

import entity.Vb6ConversionStateEntity;
import entity.Vb6FormNodeEntity;
import entity.Vb6TabInfoEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;

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
	/**
	 * インスタンス化を禁止するユーティリティクラスのコンストラクタ。
	 */
	private Vb6WinFormsConverter() {
	}

	/**
	 * VB6フォームを読み込んでVB.NET WinFormsのデザイナーと検証用資料を生成する。
	 * 使用方法: java -cp bin Vb6WinFormsConverter input.frm output_dir [dpi]。
	 * 既存のコードビハインドは上書きしない。
	 * 引数を省略すると、実行フォルダのsample/input内のすべての.frmをsample/outputへ変換する。
	 * @param args 入力.frm、出力ディレクトリ、省略可能なDPI（既定値96）。全省略も可能
	 * @throws IllegalArgumentException DPIが数値でないか、
	 * 48～768の有限値でない場合
	 * @throws IOException .frmの読み込み、解析、
	 * または出力ファイル作成に失敗した場合
	 */
	public static void main(String[] args) throws IOException {

		// 引数がない場合は、sample/inputにあるフォームをまとめて変換する。
		if (args.length == 0) {
			// 引数省略時に一括変換する入力フォームのパス一覧。
			List<Path> sources;
			try {
				sources = findSampleForms();

				// 入力一覧を取得できなかった理由を、使い方とともに表示する。
			} catch (IOException ex) {
				printHowTo(ex.getMessage());
				System.exit(2); // 2は入力指定の不備を通知する終了コード。

				// 入力を確定できなかったため、変換を開始せず終了する。
				return;
			}

			// 引数省略時の出力先の基準となる絶対パス。
			Path output = Path.of("sample", "output").toAbsolutePath().normalize();

			// 複数フォームは共通プロジェクトへまとめ、既存の単一フォーム出力と分離する。
			Path destination = sources.size() == 1 ? output : output.resolve("combined");
			convert(sources, destination, DEFAULT_DPI);

			// 一括変換が終わったため、明示引数の処理には進まない。
			return;
		}

		// 明示引数は入力と出力の2個、またはDPIを含む3個を受け付ける。
		if (args.length < 2 || args.length > 3) {
			printHowTo("引数は省略するか、入力.frmと出力先（任意でDPI）を指定してください。");
			System.exit(2); // 2は入力指定の不備を通知する終了コード。

			// 引数が不足または過剰なため終了する。
			return;
		}

		// 明示引数から受け取ったVB6フォームの絶対パス。
		Path source = Path.of(args[0]).toAbsolutePath().normalize();

		// 明示引数から受け取った生成物の出力先。
		Path out = Path.of(args[1]).toAbsolutePath().normalize();

		// 引数は入力・出力の2個が必須で、3個目だけが省略可能なDPI。
		// DPIの指定があればその値を読み取り、省略時は既定DPIを使う。
		double dpi = args.length == 3 ? Double.parseDouble(args[2]) : DEFAULT_DPI;

		// 座標換算に使えないDPIを拒否し、極端な拡大縮小や非有限値を防ぐ。
		if (!(dpi >= MIN_DPI && dpi <= MAX_DPI) || !Double.isFinite(dpi)) {
			throw new IllegalArgumentException("DPIは48～768を指定してください");
		}
		convert(List.of(source), out, dpi);
	}

	/**
	 * フォームを解析し、一組のVB.NETプロジェクトとソリューションへまとめる。
	 * @param sources 入力.frmのパス一覧
	 * @param out 出力フォルダ
	 * @param dpi 座標換算に使用するDPI
	 * @throws IOException 入力の読み込み、解析、または出力に失敗した場合
	 */
	private static void convert(List<Path> sources, Path out, double dpi) throws IOException {
		// 入力順に蓄積するフォームごとの変換状態。先頭が起動画面になる。
		List<Vb6ConversionStateEntity> forms = new ArrayList<>();

		// 大文字小文字を無視して重複を検出するための確定クラス名の集合。
		Set<String> classNames = new HashSet<>();

		// 全フォームを先に解析し、出力ファイル名とVB.NETのクラス名の衝突を解消する。
		for (Path source : sources) {

			// 通常ファイルを読み込めない場合は、生成物を書き出す前に中止する。
			if (!Files.isRegularFile(source)) {
				throw new IOException("入力ファイルがありません: " + source);
			}

			// この入力フォームの解析結果と、後で解決する配置情報。
			Vb6ConversionStateEntity form = Vb6FormParser.parseForm(source);
			form.setDpi(dpi);
			Vb6LayoutResolver.resolveLayout(form);

			// クラス名の重複時に連番を付ける前の基準名。
			String baseName = form.getClassName();

			// 重複検査にかけるクラス名の候補。
			String className = baseName;

			// 同名フォームを区別するため、2から始める連番。
			int suffix = 2;

			// VB.NETと同様に大文字小文字を区別せず、未使用の名前が見つかるまで連番を進める。
			while (!classNames.add(className.toLowerCase(Locale.ROOT))) {
				className = baseName + "_" + suffix++;
			}
			form.setClassName(className);
			forms.add(form);
		}

		// 単一フォームは従来の名前を使い、複数フォームは共通のプロジェクト名を使う。
		String projectName = forms.size() == 1 ? forms.getFirst().getClassName() : "VB6Migrated";
		Files.createDirectories(out.resolve(REPORTS_DIRECTORY));

		// 全フォームをまとめるVB.NETプロジェクトの出力パス。
		Path project = out.resolve(projectName + "_Migrated.vbproj");

		// 共通プロジェクトを参照するVisual Studioソリューションの出力パス。
		Path solution = out.resolve(projectName + "_Migrated.slnx");
		writeProject(forms, project, solution);

		// 各フォームの画面ファイルとレポートを、共通プロジェクトと同じフォルダに保存する。
		for (int i = 0; i < forms.size(); i++) {
			writeForm(sources.get(i), forms.get(i), project, solution);
		}
	}

	/**
	 * 1件の画面とレポートを生成し、共通プロジェクトの場所をログへ記録する。
	 * @param source 元の.frmファイル
	 * @param st 解析済みフォーム
	 * @param project 共通のVB.NETプロジェクト
	 * @param solution 共通のソリューション
	 * @throws IOException 出力に失敗した場合
	 */
	private static void writeForm(Path source, Vb6ConversionStateEntity st, Path project, Path solution) throws IOException {
		// 共通プロジェクトとフォームの生成物を置くフォルダ。
		Path out = project.getParent();

		// 画面レイアウトと初期化処理を出力するデザイナーのパス。
		Path designer = out.resolve(st.getClassName() + ".Designer.vb");

		// 移植するイベント処理を追記できるコードビハインドのパス。
		Path codeBehind = out.resolve(st.getClassName() + ".vb");

		// VB.NETソースと分けて保存する座標監査CSVのパス。
		Path audit = out.resolve(REPORTS_DIRECTORY).resolve(st.getClassName() + "_coordinate_audit.csv");

		// 変換時の警告と元のVB6コードを保存するレポートのパス。
		Path report = out.resolve(st.getClassName() + "_migration_report.txt");
		Files.writeString(designer, VbNetSourceGenerator.toDesignerSource(st), StandardCharsets.UTF_8);

		// 既存の手書きコードを保護し、新規出力時だけコードビハインドを作成する。
		if (!Files.exists(codeBehind)) {
			Files.writeString(codeBehind,
			"' VB6から移植したイベント処理・ビジネスロジックは未移行です。\r\n" +
			"' 元.frmのコード末尾を確認して必要な処理を移植してください。\r\n" +
			"Partial Class " + st.getClassName() + "\r\n" +
			"    Public Sub New()\r\n" +
			"        InitializeComponent()\r\n" +
			"    End Sub\r\n" +
			VbNetSourceGenerator.toPublicFieldDeclarations(st.getVb6Code()) +
			"End Class\r\n", StandardCharsets.UTF_8);
		} else {
			st.addWarning("既存のコードビハインドは保護しました: " + codeBehind.getFileName());
		}

		// 移行結果の検証用データと警告レポートは毎回更新する。
		Files.writeString(audit, Vb6ReportGenerator.toCoordinateAuditCsv(st), StandardCharsets.UTF_8);
		Files.writeString(report, Vb6ReportGenerator.toMigrationReport(st, source), StandardCharsets.UTF_8);

		// 実行ごとのロガーを使い、親ハンドラから標準エラーへ重複出力されるのを防ぐ。
		Logger logger = Logger.getAnonymousLogger();
		logger.setUseParentHandlers(false);
		logger.setLevel(Level.INFO);

		// 出力先に含まれる%を、FileHandlerのパターン指定として解釈させない。
		String logPattern = out.resolve("convertVB6Form.log").toString().replace("%", "%%");

		// 実行結果を既存ログに追記するファイルハンドラ。
		FileHandler fileHandler = new FileHandler(logPattern, true);
		try {
			fileHandler.setEncoding(StandardCharsets.UTF_8.name());
			fileHandler.setFormatter(new SimpleFormatter());
			logger.addHandler(fileHandler);
			printInfo(logger, "出力: " + designer);
			printInfo(logger, "コードビハインド: " + codeBehind);
			printInfo(logger, "Visual Studioプロジェクト: " + project);
			printInfo(logger, "Visual Studioソリューション: " + solution);
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
	 * 全フォームに共通する起動処理、プロジェクト、ソリューションを作成する。
	 * @param forms 変換するフォームの一覧。先頭が起動画面
	 * @param project 共通のVB.NETプロジェクト
	 * @param solution 共通のソリューション
	 * @throws IOException 出力に失敗した場合
	 */
	private static void writeProject(List<Vb6ConversionStateEntity> forms, Path project, Path solution) throws IOException {
		// 入力順で最初のフォームを起動するためのクラス名。
		String startupForm = forms.getFirst().getClassName();

		// WinFormsアプリのエントリーポイントを保存するパス。
		Path program = project.resolveSibling("Program.vb");

		// 既存の起動処理を保護し、Program.vbがない場合だけ標準の起動処理を生成する。
		if (!Files.exists(program)) {
			Files.writeString(program,
			"Imports System.Windows.Forms\r\n" +
			"Friend Module Program\r\n" +
			"    <System.STAThread()>\r\n" +
			"    Public Sub Main()\r\n" +
			"        Application.EnableVisualStyles()\r\n" +
			"        Application.SetCompatibleTextRenderingDefault(False)\r\n" +
			"        Application.Run(New " + startupForm + "())\r\n" +
			"    End Sub\r\n" +
			"End Module\r\n", StandardCharsets.UTF_8);
		}

		// 移行後のプロジェクト設定を上書きしないよう、新規の場合だけ生成する。
		if (!Files.exists(project)) {
			// 共通設定と各フォームのデザイナー関連付けを記述するXMLを蓄積する。
			StringBuilder xml = new StringBuilder(
			"<Project Sdk=\"Microsoft.NET.Sdk\">\r\n" +
			"  <PropertyGroup>\r\n" +
			"    <OutputType>WinExe</OutputType>\r\n" +
			"    <TargetFramework>net8.0-windows</TargetFramework>\r\n" +
			"    <UseWindowsForms>true</UseWindowsForms>\r\n" +
			"    <EnableWindowsTargeting>true</EnableWindowsTargeting>\r\n" +
			"    <DefaultItemExcludes>$(DefaultItemExcludes);*.frm/**;combined/**</DefaultItemExcludes>\r\n" +
			"    <MyType>Empty</MyType>\r\n" +
			"    <RootNamespace>VB6Migrated</RootNamespace>\r\n" +
			"    <StartupObject>VB6Migrated.Program</StartupObject>\r\n" +
			"  </PropertyGroup>\r\n" +
			"  <ItemGroup>\r\n");

			// フォームごとにコードビハインドとデザイナーの関連を登録する。
			for (Vb6ConversionStateEntity form : forms) {
				// デザイナーの従属先としてプロジェクトに登録するコードビハインド名。
				String file = form.getClassName() + ".vb";
				xml.append("    <Compile Update=\"").append(file).append("\">\r\n")
				.append("      <SubType>Form</SubType>\r\n    </Compile>\r\n")
				.append("    <Compile Update=\"").append(form.getClassName()).append(".Designer.vb\">\r\n")
				.append("      <DependentUpon>").append(file).append("</DependentUpon>\r\n    </Compile>\r\n");
			}
			xml.append("  </ItemGroup>\r\n</Project>\r\n");
			Files.writeString(project, xml.toString(), StandardCharsets.UTF_8);
		}

		// 既存のソリューションに追加されたプロジェクトや設定を保護し、新規の場合だけ生成する。
		if (!Files.exists(solution)) {
			Files.writeString(solution, toSolutionSource(project), StandardCharsets.UTF_8);
		}
	}

	/**
	 * sample/input直下の.frm通常ファイルをファイル名順に取得する。
	 * 拡張子の大文字小文字は区別せず、他のファイルとサブフォルダは読み飛ばす。
	 * @return 変換対象フォームの一覧
	 * @throws IOException 入力フォルダを読み取れないか、対象の.frmがない場合
	 */
	private static List<Path> findSampleForms() throws IOException {
		// 引数省略時に.frmを探索する、実行フォルダからの相対パス。
		Path sample = Path.of("sample", "input");

		// 相対パスsample/inputがディレクトリでない場合は、入力を探索できない。
		if (!Files.isDirectory(sample)) {
			throw new IOException("実行フォルダにsample/inputディレクトリがありません: " + sample.toAbsolutePath().normalize());
		}

		// 拡張子を検査し、ファイル名順に並べた変換対象のパス一覧。
		List<Path> candidates;

		// 入力フォルダ直下を列挙するストリーム。列挙後は自動で閉じる。
		try (Stream<Path> entries = Files.list(sample)) {
			// .frmと.FRMの両方を対象にし、他の拡張子やディレクトリは除外する。
			candidates = entries.filter(Files::isRegularFile)
			.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".frm"))
			.sorted().toList();
		}

		// 入力候補がない場合は、元のVB6フォームを配置する方法を案内する。
		if (candidates.isEmpty()) {
			throw new IOException("sample/inputディレクトリに対象の.frmファイルがありません。");
		}

		// 条件を満たすすべてのフォームを、変換順に返す。
		return candidates;
	}

	/**
	 * 入力指定を解決できない理由と、引数省略・明示指定の使い方を標準エラーへ出力する。
	 * @param reason 変換を開始できない理由
	 */
	private static void printHowTo(String reason) {
		System.err.println("HowTo ERROR: " + reason);
		System.err.println("引数なし: 実行フォルダのsample/input直下に.frmを配置してください（出力先: sample/output）。");
		System.err.println("明示指定: java -cp bin Vb6WinFormsConverter <input.frm> <output-dir> [dpi: default 96]");
		System.err.println("例: java -cp bin Vb6WinFormsConverter sample/input/ReportWindow.frm sample/output");
	}

	/**
	 * 同じ出力フォルダ内のVB.NETプロジェクトを参照するXMLソリューションを生成する。
	 * プロジェクトを相対パスで記録し、フォルダを移動しても参照できるようにする。
	 * @param project ソリューションに登録するVB.NETプロジェクトのパス
	 * @return SLNX形式のソリューション全文
	 */
	private static String toSolutionSource(Path project) {
		// XML属性に埋め込むため、ファイル名の特殊文字をエスケープする。
		String projectFile = project.getFileName().toString()
		.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");

		// 生成したVB.NETプロジェクトを1件含む、最小構成のXMLソリューションを返す。
		return "<Solution>\r\n"
		+ "  <Project Path=\"" + projectFile + "\" />\r\n"
		+ "</Solution>\r\n";
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
}
