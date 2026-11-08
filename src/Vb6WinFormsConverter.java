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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VB6フォームの.frmからVB.NET Windows Formsの画面定義を生成するJava 17ツール。
 *
 * <p>Windows-31Jで読み込み、Twipsを指定DPIのPixelへ換算する。
 * SSTabはTabControl/TabPageへ変換し、Frameの親子関係を引き継ぐ。
 * 生成したDesigner.vbとともに監査CSVと未移行項目のレポートを出力する。
 *
 * <p>イベントハンドラー、業務ロジック、COMの動作、.frx画像の移行には対応しない。
 * 推定した配置や対応しない要素は、必ず出力結果と警告を確認すること。
 *
 * @see Vb6FormNode
 * @see Vb6TabInfo
 * @see Vb6ConversionState
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
     * インスタンス化を禁止するユーティリティクラスのコンストラクタ。
     */
    private Vb6WinFormsConverter() {
    }
    /**
     * VB6フォームを読み込んでVB.NET WinFormsのデザイナーと検証用資料を生成する。
     * 使用方法: java -cp bin Vb6WinFormsConverter input.frm output_dir [dpi]。既存のコードビハインドは上書きしない。
     * @param args 入力.frm、出力ディレクトリ、省略可能なDPI（既定値96）
     * @throws IllegalArgumentException DPIが数値でないか、48～768の有限値でない場合
     * @throws IOException .frmの読み込み、解析、または出力ファイル作成に失敗した場合
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 2 || args.length > 3) {
            System.err.println("使い方: java -jar Vb6WinFormsConverter.jar <input.frm> <output-dir> [dpi: default 96]");
            System.exit(2);
        }
        Path source = Path.of(args[0]).toAbsolutePath().normalize();
        Path out = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IOException("入力ファイルがありません: " + source);
        }
        double dpi = args.length == 3 ? Double.parseDouble(args[2]) : 96.0;
        if (!(dpi >= 48.0 && dpi <= 768.0) || !Double.isFinite(dpi)) {
            throw new IllegalArgumentException("DPIは48～768を指定してください");
        }
        // ステップ1: .frm解析 → ステップ2: コントロールの種類と配置を確定する。
        Vb6ConversionState st = parseForm(source);
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
        System.out.println("出力: " + designer);
        System.out.println("コードビハインド: " + codeBehind);
        System.out.println("Visual Studioプロジェクト: " + project);
        System.out.println("座標CSV: " + audit);
        System.out.println("移行レポート: " + report);
        System.out.println("コントロール: " + st.getAll().size() + " (出力 " + st.getVisualCount()
        + " / 省略 " + st.getIgnoredCount() + "), タブ座標補正: " + st.getRemappedTabCount()
        + ", 所属タブ推定: " + st.getUnknownTabCount() + ", 警告: " + st.getWarnings().size());
    }

    /**
     * .frmをWindows-31Jで読み、Begin/Endをスタックで解釈してノードツリーを構築する。
     * BeginPropertyは親コントロールの属性とし、Attribute VB_Name以降はVB6コード部として保存する。
     * @param source 入力.frmのパス
     * @return コントロール階層と元コードを含む解析状態
     * @throws IOException 読み込みに失敗、またはBegin/Endが不整合の場合
     */
    private static Vb6ConversionState parseForm(Path source) throws IOException {
        Vb6ConversionState s = new Vb6ConversionState();
        String raw = Files.readString(source, VB6_CHARSET);
        String[] lines = raw.split("\\r?\\n", -1);
        // Beginでpush、EndでpopすることでVB6のネスト構造を復元する。
        Deque<Vb6FormNode> stack = new ArrayDeque<>();
        int codeStart = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            // ここから先はVB6イベント処理などのコード部なので、レイアウト解析を終了する。
            if (line.startsWith("Attribute VB_Name")) {
                codeStart = i;
                break;
            }
            Matcher begin = BEGIN.matcher(line);
            Matcher nested = BEGIN_PROPERTY.matcher(line);
            if (begin.matches()) {
                Vb6FormNode parent = stack.peek();
                Vb6FormNode node = new Vb6FormNode("control", begin.group(1), begin.group(2), parent);
                if (parent != null) {
                    parent.getChildren().add(node);
                } else if (s.getForm() == null) {
                    s.setForm(node);
                } else {
                    s.addWarning("複数のトップレベルフォームがあります: " + node.getName());
                }
                stack.push(node);
                s.getAll().add(node);
                // BeginProperty Fontなどは別コントロールではなく親コントロールの属性。
            } else if (nested.matches()) {
                Vb6FormNode parent = stack.peek();
                if (parent == null) {
                    throw new IOException("BeginPropertyの親がありません (行 " + (i + 1) + ")");
                }
                Vb6FormNode node = new Vb6FormNode("property", "", nested.group(1), parent);
                parent.getNested().put(node.getName(), node);
                stack.push(node);
            } else if (line.equals("End") || line.equals("EndProperty")) {
                if (stack.isEmpty()) {
                    throw new IOException("Endの数が不正です (行 " + (i + 1) + ")");
                }
                Vb6FormNode popped = stack.pop();
                if (line.equals("End") && !popped.getKind().equals("control") ||
                line.equals("EndProperty") && !popped.getKind().equals("property")) {
                    throw new IOException("Begin/Endの対応が不正です (行 " + (i + 1) + ")");
                }
            } else if (!stack.isEmpty()) {
                Matcher prop = PROPERTY.matcher(line);
                if (prop.matches()) {
                    stack.peek().getProps().put(prop.group(1).trim(), prop.group(2).trim());
                }
            }
        }
        if (!stack.isEmpty()) {
            throw new IOException("BeginとEndが対応していません: " + stack.peek().getName());
        }
        if (s.getForm() == null || !"VB.Form".equals(s.getForm().getVbType())) {
            throw new IOException("VB.Formが見つかりません");
        }
        if (codeStart < lines.length) {
            s.setVb6Code(String.join("\n", Arrays.copyOfRange(lines, codeStart, lines.length)));
        }
        return s;
    }

    /**
     * コントロール名・WinForms型・座標・親子関係を解決する。
     * SSTabでは明示的なTab(n).Controlと兄弟要素から所属を決め、非表示タブ用の負座標とTabHeightを補正する。
     * @param s 更新する解析・変換状態
     */
    private static void resolveLayout(Vb6ConversionState s) {
        // VB6のVB_Name（見つからなければフォーム名）を出力先のクラス名に採用。
        Matcher classMatch = Pattern.compile("(?m)^Attribute VB_Name\\s*=\\s*\"([^\"]+)\"")
        .matcher(s.getVb6Code());
        s.setClassName(toSafeIdentifier(classMatch.find() ? classMatch.group(1) : s.getForm().getName()));
        if (s.getForm().hasProperty("ScaleMode") && parseInteger(s.getForm().getProperty("ScaleMode"), 1) != 1) {
            s.addWarning("Form.ScaleMode がTwips以外です。座標は96DPIのTwipsとして暫定変換します");
        }
        for (Vb6FormNode n : s.getAll()) {
            n.setId(n.getName() + (n.hasProperty("Index") ? "(" + parseInteger(n.getProperty("Index"), 0) + ")" : ""));
            n.setField(toUniqueFieldName(s, toSafeIdentifier(n.getName() + (n.hasProperty("Index")
            ? "_" + parseInteger(n.getProperty("Index"), 0) : ""))));
            n.setWinType(toWinFormsControlType(n.getVbType()));
            if (n.getWinType() == null) {
                n.setSupported(false);
                s.incrementIgnoredCount();
                s.addWarning(n.getId() + " (" + n.getVbType() + ") はWinForms相当コントロールがなく省略しました");
            } else if (n != s.getForm()) {
                s.incrementVisualCount();
            }
            if (n.hasProperty("Index")) {
                s.addWarning(n.getId() + " はVB6のコントロール配列です。WinFormsでは " + n.getField() + " へ個別変換しました。配列イベント処理の移植は別途必要です");
            }
            n.setLeftTwips(parseInteger(n.getProperty("Left"), 0));
            n.setTopTwips(parseInteger(n.getProperty("Top"), 0));
            n.setWidthTwips(parseInteger(n.getProperty("Width"), parseInteger(n.getProperty("ClientWidth"), 0)));
            n.setHeightTwips(parseInteger(n.getProperty("Height"), parseInteger(n.getProperty("ClientHeight"), 0)));
            n.setPixelLeft(toPixels(n.getLeftTwips(), s.getDpi()));
            n.setPixelTop(toPixels(n.getTopTwips(), s.getDpi()));
            n.setPixelWidth(toPixels(n.getWidthTwips(), s.getDpi()));
            n.setPixelHeight(toPixels(n.getHeightTwips(), s.getDpi()));
            if (n.getId() != null && s.getById().put(n.getId(), n) != null) {
                s.addWarning("VB6コントロール識別子が重複しています: " + n.getId());
            }
            // SSTabは後で子要素を適切なTabPageへ移すため、タブ数と見出し高を保持する。
            if ("TabDlg.SSTab".equals(n.getVbType())) {
                Vb6TabInfo t = new Vb6TabInfo();
                t.setTab(n);
                t.setCount(Math.max(1, parseInteger(n.getProperty("Tabs"), 1)));
                t.setTabHeightTwips(parseInteger(n.getProperty("TabHeight"), 520));
                s.getTabs().put(n, t);
            }
            if (n.hasProperty("Picture")) {
                s.addWarning(n.getId() + " のPictureは .frx 参照の可能性があります。画像リソースは未移行です");
            }
            if (n.hasProperty("ItemData") || n.hasProperty("List")) {
                s.addWarning(n.getId() + " のリスト初期値はFRXを参照する場合があります。実データは未移行です");
            }
            if (n.hasProperty("ScaleMode") && parseInteger(n.getProperty("ScaleMode"), 1) != 1) {
                s.addWarning(n.getId() + " のScaleModeはTwipsではありません。子コントロール座標の手動検証が必要です");
            }
        }
        for (Vb6TabInfo ti : s.getTabs().values()) {
            for (Map.Entry<String, String> e : ti.getTab().getProps().entrySet()) {
                Matcher match = TAB_CONTROL.matcher(e.getKey());
                if (match.matches()) {
                    int tabIdx = Integer.parseInt(match.group(1));
                    String original = parseVbString(e.getValue());
                    ti.getAssignments().put(original, tabIdx);
                }
            }
            for (int i = 0; i < ti.getCount(); i++) {
                Vb6FormNode page = new Vb6FormNode("generated", "WinForms.TabPage", ti.getTab().getName() + "_TabPage" + i, ti.getTab());
                page.setField(toUniqueFieldName(s, toSafeIdentifier(page.getName())));
                page.setWinType("System.Windows.Forms.TabPage");
                page.setTabIndex(i);
                page.setId(page.getName());
                ti.getPages().add(page);
            }
            if (ti.getTab().hasProperty("TabPicture(0)")) {
                s.addWarning(ti.getTab().getId() + " のTabPictureは移行しません（.frx画像未添付）");
            }
        }
        // SSTab直下のコントロールは親をTabPageへ変更してから座標を補正する。
        // Frame等の内側では元の親子関係と相対座標を維持する。
        for (Vb6FormNode n : s.getAll()) {
            if (n.getParent() != null && s.getTabs().containsKey(n.getParent())) {
                Vb6TabInfo ti = s.getTabs().get(n.getParent());
                Integer tab = ti.getAssignments().get(n.getId());
                boolean inferred = false;
                // 明示されたタブ所属がない場合、同名コントロール配列から推定する。
                // それでも不明なら符号から暫定配置して警告に記録する。
                if (tab == null) {
                    // Array siblings can establish otherwise missing tab membership.
                    Set<Integer> siblings = new LinkedHashSet<>();
                    for (Vb6FormNode other : n.getParent().getChildren()) {
                        if (other == n || !other.getName().equals(n.getName())) {
                            continue;
                        }
                        Integer assigned = ti.getAssignments().get(other.getId());
                        if (assigned != null) {
                            siblings.add(assigned);
                        }
                    }
                    if (siblings.size() == 1) {
                        tab = siblings.iterator().next();
                    } else {
                        tab = n.getLeftTwips() >= 0 ? 0 : Math.min(1, ti.getCount() - 1);
                    }
                    inferred = true;
                }
                if (tab < 0 || tab >= ti.getCount()) {
                    s.addWarning(n.getId() + " のタブ番号が不正です: " + tab + "。Tab0に置き換えます");
                    tab = 0;
                }
                n.setTabIndex(tab);
                if (inferred) {
                    s.incrementUnknownTabCount();
                    s.addWarning(n.getId() + " はTab(n).Control一覧にありません。タブ" + tab + " に推定配置しました（要確認）");
                }
                // SSTabで非表示のタブを左へ追い出した大きな負座標だけを補正する。
                // 通常の小さな負座標は意図的な位置として扱い、補正しない。
                long correctedLeft = n.getLeftTwips() < -10000
                ? (long) n.getLeftTwips() + SSTAB_HIDE_TWIPS : n.getLeftTwips();
                if (correctedLeft != n.getLeftTwips()) {
                    s.incrementRemappedTabCount();
                }
                n.setPixelLeft(toPixels(correctedLeft, s.getDpi()));
                // VB6ではタブ見出し基準、WinForms TabPageでは内容領域基準なので、
                // TabHeightを引いてY座標を揃える。
                long correctedTop = (long) n.getTopTwips() - ti.getTabHeightTwips();
                // Some VB6 controls begin slightly above the tab header baseline.
                if (correctedTop < 0 && correctedTop > -200) {
                    correctedTop = 0;
                }
                n.setPixelTop(toPixels(correctedTop, s.getDpi()));
                n.setResolvedParent(ti.getPages().get(tab).getField());
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
    private static String toUniqueFieldName(Vb6ConversionState s, String requested) {
        String name = requested;
        int i = 2;
        while (!s.getUsedFields().add(name.toLowerCase(Locale.ROOT))) {
            name = requested + "_" + i++;
        }
        return name;
    }
    /**
     * VB6名の使用できない文字と予約語を処理してVB.NET識別子へ正規化する。
     * @param name 元の名前
     * @return VB.NET識別子の候補
     */
    private static String toSafeIdentifier(String name) {
        String result = name.replaceAll("[^\\p{L}\\p{N}_]", "_");
        if (result.isEmpty()) {
            result = "Unnamed";
        }
        if (Character.isDigit(result.charAt(0))) {
            result = "ctl_" + result;
        }
        // Avoid collision with VB.NET keywords while preserving original-ish designer names.
        Set<String> keywords = Set.of("Class", "End", "New", "If", "Then", "Else", "Module", "Sub", "Me", "Public", "Private", "Friend", "Partial", "Namespace", "Property", "Integer", "String");
        if (keywords.contains(result)) {
            result = "ctl_" + result;
        }
        return result;
    }
    /**
     * VB6のコントロール型をWinFormsの完全修飾クラス名へ割り当てる。
     * COM固有の処理は移行しない。
     * @param type VB6型名
     * @return WinForms型名。非対応ならnull
     */
    private static String toWinFormsControlType(String type) {
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
     * @param value 属性値。未設定ならnull
     * @param fallback 解析失敗時の既定値
     * @return 数値化した属性値
     */
    private static int parseInteger(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.split("\\s+'", 2)[0].trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
    /**
     * Twipsを指定DPIに対応するピクセルへ換算する。1inch=1440Twips。
     * @param twips 元座標またはサイズ
     * @param dpi 変換先のDPI
     * @return 四捨五入したピクセル値
     */
    private static int toPixels(long twips, double dpi) {
        return (int) Math.round(twips * dpi / 1440.0);
    }
    /**
     * VB6文字列リテラルを取り出し、二重の引用符を元に戻す。
     * @param vbValue .frmの元の値
     * @return デコードした文字列。nullの場合は空文字列
     */
    private static String parseVbString(String vbValue) {
        if (vbValue == null) {
            return "";
        }
        String t = vbValue.trim();
        if (!t.startsWith("\"")) {
            return t.split("\\s+'", 2)[0];
        }
        StringBuilder b = new StringBuilder();
        for (int i = 1; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '"') {
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
        return b.toString();
    }
    /**
     * 文字列をVB.NETの引用符・改行を適切に含む式へ変換する。
     * @param javaString 変換元文字列
     * @return VB.NET文字列式
     */
    private static String toVbStringLiteral(String javaString) {
        return "\"" + javaString.replace("\"", "\"\"").replace("\r", "")
        .replace("\n", "\" & vbCrLf & \"") + "\"";
    }
    /**
     * 数値で記述されたVB6真偽値を判定する。
     * @param value VB6属性値
     * @return 非ゼロならtrue
     */
    private static boolean isVbTrue(String value) {
        return parseInteger(value, 0) != 0;
    }
    /**
     * JavaのbooleanをVB.NETリテラルへ変換する。
     * @param value 真偽値
     * @return TrueまたはFalse
     */
    private static String toVbBooleanLiteral(boolean value) {
        return value ? "True" : "False";
    }
    /**
     * フィールド名をMe.で修飾する。
     * @param n フィールド名
     * @return Me.フィールド名
     */
    private static String toControlReference(String n) {
        return "Me." + n;
    }
    /**
     * VB.NETプロパティ代入の対象を選択する。
     * @param n コントロールノード
     * @return フォームならMe、子ならMe.フィールド名
     */
    private static String toPropertyTarget(Vb6FormNode n) {
        return "VB.Form".equals(n.getVbType()) ? "Me" : toControlReference(n.getField());
    }
    /**
     * 文字列バッファに1行をCRLF付きで追記する。
     * @param b 生成先
     * @param s 追記する内容
     */
    private static void appendLine(StringBuilder b, String s) {
        b.append(s).append("\r\n");
    }
    /**
     * VB.NETのコントロールプロパティ代入文を生成する。
     * @param b 生成先
     * @param n コントロールノード
     * @param property プロパティ名
     * @param expression 右辺のVB.NET式
     */
    private static void appendPropertyAssignment(StringBuilder b, Vb6FormNode n, String property, String expression) {
        appendLine(b, "        " + toPropertyTarget(n) + "." + property + " = " + expression);
    }
    /**
     * ImageListなど、Controls.Addをしない非表示コンポーネントを判定する。
     * @param n 対象ノード
     * @return 非表示コンポーネントならtrue
     */
    private static boolean isNonVisual(Vb6FormNode n) {
        return n.getWinType() != null && (n.getWinType().endsWith(".ImageList") || n.getWinType().endsWith(".OpenFileDialog"));
    }
    /**
     * 単一コントロールの位置、サイズ、文字、フォント、種別固有の属性を生成する。
     * LabelはCaption・WordWrap・AutoSizeから文字切れを避ける設定を決定する。
     * @param out 出力バッファ
     * @param n 対象ノード
     * @param s 変換状態
     */
    private static void appendControlProperties(StringBuilder out, Vb6FormNode n, Vb6ConversionState s) {
        if (!n.isSupported() || n == s.getForm()) {
            return;
        }
        if (isNonVisual(n)) {
            return;
        }
        appendPropertyAssignment(out, n, "Name", toVbStringLiteral(n.getField()));
        appendPropertyAssignment(out, n, "Location", "New System.Drawing.Point(" + n.getPixelLeft() + ", " + n.getPixelTop() + ")");
        if (n.hasProperty("Width") || n.hasProperty("Height")) {
            appendPropertyAssignment(out, n, "Size", "New System.Drawing.Size(" + Math.max(1, n.getPixelWidth()) + ", " + Math.max(1, n.getPixelHeight()) + ")");
        }
        if (n.hasProperty("Caption")) {
            appendPropertyAssignment(out, n, "Text", toVbStringLiteral(parseVbString(n.getProperty("Caption"))));
        }
        if (n.hasProperty("Text") && !n.getVbType().equals("VB.TextBox")) {
            appendPropertyAssignment(out, n, "Text", toVbStringLiteral(parseVbString(n.getProperty("Text"))));
        }
        if (n.hasProperty("Visible")) {
            appendPropertyAssignment(out, n, "Visible", toVbBooleanLiteral(isVbTrue(n.getProperty("Visible"))));
        }
        if (n.hasProperty("Enabled")) {
            appendPropertyAssignment(out, n, "Enabled", toVbBooleanLiteral(isVbTrue(n.getProperty("Enabled"))));
        }
        if (n.hasProperty("TabIndex")) {
            appendPropertyAssignment(out, n, "TabIndex", Integer.toString(parseInteger(n.getProperty("TabIndex"), 0)));
        }
        // フォント名・ポイント数・太字などはBeginProperty Fontから復元する。
        Vb6FormNode font = n.getNested().get("Font");
        if (font != null) {
            String fontName = parseVbString(font.getProperty("Name"));
            if (fontName.isEmpty()) {
                fontName = "MS UI Gothic";
            }
            String fontSize = font.getProperty("Size") == null ? "9" : font.getProperty("Size").trim();
            try {
                new BigDecimal(fontSize);
            } catch (NumberFormatException ex) {
                fontSize = "9";
            }
            List<String> styles = new ArrayList<>();
            if (parseInteger(font.getProperty("Weight"), 400) >= 700) {
                styles.add("System.Drawing.FontStyle.Bold");
            }
            if (isVbTrue(font.getProperty("Italic"))) {
                styles.add("System.Drawing.FontStyle.Italic");
            }
            if (isVbTrue(font.getProperty("Underline"))) {
                styles.add("System.Drawing.FontStyle.Underline");
            }
            if (isVbTrue(font.getProperty("Strikethrough"))) {
                styles.add("System.Drawing.FontStyle.Strikeout");
            }
            String style = styles.isEmpty() ? "System.Drawing.FontStyle.Regular" : String.join(" Or ", styles);
            int charset = parseInteger(font.getProperty("Charset"), 0);
            appendPropertyAssignment(out, n, "Font", "New System.Drawing.Font(" + toVbStringLiteral(fontName) + ", CSng(" + fontSize
            + "), " + style + ", System.Drawing.GraphicsUnit.Point, CByte(" + Math.max(0, Math.min(255, charset)) + "))");
        }
        if (n.hasProperty("BackColor")) {
            String color = toColor(n.getProperty("BackColor"));
            if (color != null) {
                appendPropertyAssignment(out, n, "BackColor", color);
            }
        }
        if (n.hasProperty("ForeColor")) {
            String color = toColor(n.getProperty("ForeColor"));
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
                if (n.hasProperty("Alignment")) {
                    appendPropertyAssignment(out, n, "TextAlign", switch (parseInteger(n.getProperty("Alignment"), 0)) {
                        case 1 -> "System.Drawing.ContentAlignment.MiddleRight";
                        case 2 -> "System.Drawing.ContentAlignment.MiddleCenter";
                        default -> "System.Drawing.ContentAlignment.MiddleLeft";
                    });
                }
            }
            case "VB.TextBox" -> {
                if (n.hasProperty("Text")) {
                    appendPropertyAssignment(out, n, "Text", toVbStringLiteral(parseVbString(n.getProperty("Text"))));
                }
                if (n.hasProperty("Locked")) {
                    appendPropertyAssignment(out, n, "ReadOnly", toVbBooleanLiteral(isVbTrue(n.getProperty("Locked"))));
                }
                if (n.hasProperty("MaxLength")) {
                    appendPropertyAssignment(out, n, "MaxLength", Integer.toString(parseInteger(n.getProperty("MaxLength"), 32767)));
                }
                if (n.hasProperty("MultiLine")) {
                    appendPropertyAssignment(out, n, "Multiline", toVbBooleanLiteral(isVbTrue(n.getProperty("MultiLine"))));
                }
                if (n.hasProperty("ScrollBars")) {
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
                if (n.hasProperty("Value")) {
                    appendPropertyAssignment(out, n, "Checked", toVbBooleanLiteral(isVbTrue(n.getProperty("Value"))));
                }
            }
            case "VB.ComboBox" -> {
                if (n.hasProperty("Style")) {
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
                if (n.hasProperty("MultiSelect")) {
                    String mode = switch (parseInteger(n.getProperty("MultiSelect"), 0)) {
                        case 1 -> "MultiSimple";
                        case 2 -> "MultiExtended";
                        default -> "One";
                    };
                    appendPropertyAssignment(out, n, "SelectionMode", "System.Windows.Forms.SelectionMode." + mode);
                }
            }
            case "VB.PictureBox" -> {
                if (n.hasProperty("BorderStyle")) {
                    appendPropertyAssignment(out, n, "BorderStyle",
                    "System.Windows.Forms.BorderStyle." + (parseInteger(n.getProperty("BorderStyle"), 0) == 0 ? "None" : "FixedSingle"));
                }
            }
            case "MSComctlLib.ListView" -> {
                int view = parseInteger(n.getProperty("View"), 3);
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
     * VB6のOLE_COLORをSystem.Drawing.Colorの式へ変換する。通常色はBGR、システム色は一部対応。
     * @param raw VB6の色コード
     * @return WinForms色の式。変換できなければnull
     */
    private static String toColor(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher m = Pattern.compile("&H([0-9A-Fa-f]{1,8})&?").matcher(raw.trim());
        if (!m.find()) {
            return null;
        }
        long value = Long.parseUnsignedLong(m.group(1), 16);
        if ((value & 0xFF000000L) == 0x80000000L) {
            return switch ((int) (value & 0xFF)) {
                case 5 -> "System.Drawing.SystemColors.Window";
                case 8 -> "System.Drawing.SystemColors.WindowText";
                case 15 -> "System.Drawing.SystemColors.Control";
                case 18 -> "System.Drawing.SystemColors.ControlText";
                default -> null;
            };
        }
        int b = (int) ((value >> 16) & 255), g = (int) ((value >> 8) & 255), r = (int) (value & 255);
        return "System.Drawing.Color.FromArgb(" + r + ", " + g + ", " + b + ")";
    }
    /**
     * WinFormsのDesigner.vbを生成する。
     * コントロール生成、プロパティ設定、TabPageと親子関係の割当、フィールド宣言を順に行う。
     * @param s 解決済み変換状態
     * @return Designer.vb全文
     */
    private static String toDesignerSource(Vb6ConversionState s) {
        StringBuilder out = new StringBuilder();
        appendLine(out, "' 自動生成: Vb6WinFormsConverter (Java 17) / レイアウト変換のみ");
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
        for (Vb6FormNode n : s.getAll()) {
            if (n != s.getForm() && n.isSupported()) {
                if (n.getWinType().endsWith(".ImageList")) {
                    appendLine(out, "        Me." + n.getField() + " = New " + n.getWinType() + "(Me.components)");
                } else {
                    appendLine(out, "        Me." + n.getField() + " = New " + n.getWinType() + "()");
                }
            }
        }
        for (Vb6TabInfo t : s.getTabs().values()) {
            for (Vb6FormNode p : t.getPages()) {
                appendLine(out, "        Me." + p.getField() + " = New System.Windows.Forms.TabPage()");
            }
        }
        appendLine(out, "        Me.SuspendLayout()");
        // 次に位置・サイズ・表示文字などの基本属性を設定する。
        for (Vb6FormNode n : s.getAll()) {
            if (n.isSupported() && n != s.getForm()) {
                appendControlProperties(out, n, s);
            }
        }
        for (Vb6TabInfo ti : s.getTabs().values()) {
            for (Vb6FormNode page : ti.getPages()) {
                int i = page.getTabIndex();
                appendPropertyAssignment(out, page, "Name", toVbStringLiteral(page.getField()));
                appendPropertyAssignment(out, page, "Text", toVbStringLiteral(parseVbString(ti.getTab().getProperty("TabCaption(" + i + ")"))));
                appendPropertyAssignment(out, page, "UseVisualStyleBackColor", "True");
            }
            appendPropertyAssignment(out, ti.getTab(), "Multiline", "True");
            appendPropertyAssignment(out, ti.getTab(), "SizeMode", "System.Windows.Forms.TabSizeMode.Fixed");
            appendPropertyAssignment(out, ti.getTab(), "ItemSize", "New System.Drawing.Size(" + toPixels(1800, s.getDpi()) + ", " + Math.max(1, toPixels(ti.getTabHeightTwips(), s.getDpi())) + ")");
        }
        // ネストしたFrame等への追加と、SSTab直下のTabPageへの追加を行う。
        for (Vb6FormNode n : s.getAll()) {
            if (n == s.getForm() || n.getParent() == s.getForm() || !n.isSupported() || isNonVisual(n)) {
                continue;
            }
            if (s.getTabs().containsKey(n.getParent())) {
                // Tab pages may be omitted if a tab control is skipped in the future.
                appendLine(out, "        Me." + n.getResolvedParent() + ".Controls.Add(Me." + n.getField() + ")");
            } else if (n.getParent() != null && n.getParent().isSupported()) {
                appendLine(out, "        Me." + n.getParent().getField() + ".Controls.Add(Me." + n.getField() + ")");
            }
        }
        for (Vb6TabInfo ti : s.getTabs().values()) {
            for (Vb6FormNode page : ti.getPages()) {
                appendLine(out, "        Me." + ti.getTab().getField() + ".TabPages.Add(Me." + page.getField() + ")");
            }
        }
        // フォーム内部のサイズを設定。AutoScaleMode=NoneでDPI自動拡大を防ぐ。
        appendPropertyAssignment(out, s.getForm(), "Name", toVbStringLiteral(s.getClassName()));
        appendPropertyAssignment(out, s.getForm(), "AutoScaleMode", "System.Windows.Forms.AutoScaleMode.None");
        appendPropertyAssignment(out, s.getForm(), "ClientSize", "New System.Drawing.Size(" + toPixels(parseInteger(s.getForm().getProperty("ClientWidth"), 0), s.getDpi()) + ", "
        + toPixels(parseInteger(s.getForm().getProperty("ClientHeight"), 0), s.getDpi()) + ")");
        appendPropertyAssignment(out, s.getForm(), "Text", toVbStringLiteral(parseVbString(s.getForm().getProperty("Caption"))));
        if (s.getForm().hasProperty("BorderStyle")) {
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
        if (s.getForm().hasProperty("MaxButton")) {
            appendPropertyAssignment(out, s.getForm(), "MaximizeBox", toVbBooleanLiteral(isVbTrue(s.getForm().getProperty("MaxButton"))));
        }
        if (s.getForm().hasProperty("MinButton")) {
            appendPropertyAssignment(out, s.getForm(), "MinimizeBox", toVbBooleanLiteral(isVbTrue(s.getForm().getProperty("MinButton"))));
        }
        if (s.getForm().hasProperty("StartUpPosition")) {
            String start = switch (parseInteger(s.getForm().getProperty("StartUpPosition"), 0)) {
                case 1 -> "CenterParent";
                case 2 -> "CenterScreen";
                case 3 -> "WindowsDefaultLocation";
                case 0 -> "Manual";
                default -> "WindowsDefaultBounds";
            };
            appendPropertyAssignment(out, s.getForm(), "StartPosition", "System.Windows.Forms.FormStartPosition." + start);
        }
        for (Vb6FormNode child : s.getForm().getChildren()) {
            if (child.isSupported() && !isNonVisual(child)) {
                appendLine(out, "        Me.Controls.Add(Me." + child.getField() + ")");
            }
        }
        appendLine(out, "        Me.ResumeLayout(False)");
        appendLine(out, "    End Sub");
        appendLine(out, "");
        for (Vb6FormNode n : s.getAll()) {
            if (n != s.getForm() && n.isSupported()) {
                appendLine(out, "    Friend WithEvents " + n.getField() + " As " + n.getWinType());
            }
        }
        for (Vb6TabInfo t : s.getTabs().values()) {
            for (Vb6FormNode p : t.getPages()) {
                appendLine(out, "    Friend WithEvents " + p.getField() + " As " + p.getWinType());
            }
        }
        appendLine(out, "End Class");
        return out.toString();
    }
    /**
     * VB6コードから単純なPublicフィールドのみを抽出する。イベントや業務ロジックは移植しない。
     * @param code 元VB6コード
     * @return VB.NETのPublicフィールド宣言
     */
    private static String toPublicFieldDeclarations(String code) {
        StringBuilder output = new StringBuilder();
        Matcher matcher = Pattern.compile("(?m)^Public\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+As\\s+(Boolean|Integer|Long|String|Double|Single)\\s*$", Pattern.CASE_INSENSITIVE).matcher(code);
        while (matcher.find()) {
            output.append("    Public ").append(matcher.group(1)).append(" As ").append(matcher.group(2)).append("\r\n");
        }
        return output.toString();
    }
    /**
     * CSVフィールドを引用符で囲み、内部の引用符をエスケープする。
     * @param value 出力する値
     * @return CSV用にエスケープした値
     */
    private static String toCsvField(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    /**
     * 元のTwips座標、換算後のPixel座標、親子関係などをCSVに出力する。
     * @param s 変換状態
     * @return 座標監査CSV全文
     */
    private static String toCoordinateAuditCsv(Vb6ConversionState s) {
        StringBuilder b = new StringBuilder();
        appendLine(b, "VB6Type,VB6Name,WinFormsType,WinFormsField,VB6Parent,WinFormsParent,TabIndex,LeftTwips,TopTwips,WidthTwips,HeightTwips,PixelX,PixelY,PixelWidth,PixelHeight,Output");
        for (Vb6FormNode n : s.getAll()) {
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
        return b.toString();
    }
    /**
     * 変換条件、補正・推定件数、警告、未移植コードをレポートに出力する。
     * @param s 変換状態
     * @param source 入力.frmパス
     * @return 移行レポート全文
     */
    private static String toMigrationReport(Vb6ConversionState s, Path source) {
        StringBuilder b = new StringBuilder();
        appendLine(b, "VB6 -> VB.NET WinForms 座標変換レポート");
        appendLine(b, "入力: " + source.getFileName());
        appendLine(b, "フォーム: " + s.getClassName());
        appendLine(b, "DPI: " + s.getDpi() + " (1px = " + 1440.0 / s.getDpi() + " Twips)");
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
        if (s.getWarnings().isEmpty()) {
            appendLine(b, "なし");
        }
        for (String warn : s.getWarnings()) {
            appendLine(b, warn);
        }
        appendLine(b, "");
        appendLine(b, "=== 元.frm内のコード（参考・未移植） ===");
        appendLine(b, s.getVb6Code());
        return b.toString();
    }
}
