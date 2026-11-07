import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VB6 .frm designer -> VB.NET WinForms .Designer.vb conversion.
 * Java 17. No external dependencies. This tool migrates visual layout,
 * not VB6 event handlers or COM component behavior.
 */
public final class Vb6WinFormsConverter {
    // VB6の日本語.frmをWindows-31Jで読み取る。生成ファイルはUTF-8で出力する。
    private static final Charset VB6_CHARSET = Charset.forName("windows-31j");
    // .frmのBegin/Endでコントロール階層を、BeginProperty/EndPropertyでFont等の属性を認識する。
    private static final Pattern BEGIN = Pattern.compile("^\\s*Begin\\s+([\\w.]+)\\s+([^\\s]+)\\s*$");
    private static final Pattern BEGIN_PROPERTY = Pattern.compile("^\\s*BeginProperty\\s+(\\S+)(?:\\s+.*)?$");
    private static final Pattern PROPERTY = Pattern.compile("^\\s*([^=]+?)\\s*=\\s*(.*?)\\s*$");
    // SSTab固有の「Tab(n).Control(m)」形式から子要素の所属タブを抽出する。
    private static final Pattern TAB_CONTROL = Pattern.compile("Tab\\((\\d+)\\)\\.Control\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAB_CAPTION = Pattern.compile("TabCaption\\((\\d+)\\)", Pattern.CASE_INSENSITIVE);
    // 非選択タブのコントロールが、左方向へ75000Twips移動して保存されるケースを補正する。
    private static final long SSTAB_HIDE_TWIPS = 75000L;

    /**
     * .frm内のコントロールまたはBeginPropertyを表す中間ノード。
     * 元のTwips座標と変換後のPixel座標を両方持ち、監査CSVに記録できるようにする。
     */
    static class Node {
        String kind; // control, property
        String vbType, name;
        Node parent;
        final List<Node> children = new ArrayList<>();
        final Map<String, String> props = new LinkedHashMap<>();
        final Map<String, Node> nested = new HashMap<>();
        String id, field, winType, resolvedParent = "";
        int tabIndex = -1;
        int leftTwips, topTwips, widthTwips, heightTwips;
        int pixelLeft, pixelTop, pixelWidth, pixelHeight;
        boolean supported = true;
        Node(String kind, String type, String name, Node parent) {
            this.kind = kind; this.vbType = type; this.name = name; this.parent = parent;
        }
        String prop(String key) { return props.get(key); }
        boolean has(String key) { return props.containsKey(key); }
    }
    /** SSTabの枚数・見出し高さ・タブ所属情報・生成予定のTabPageを保持する。 */
    static class TabInfo {
        Node tab;
        int count;
        int tabHeightTwips;
        final Map<String, Integer> assignments = new HashMap<>();
        final List<Node> pages = new ArrayList<>();
    }
    /**
     * 1フォームの解析結果、コントロール名管理、変換時の警告をまとめた状態。
     * 配置を推定した要素はwarningsに残して手動確認できるようにする。
     */
    static class State {
        Node form;
        final List<Node> all = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Map<String, Node> byId = new LinkedHashMap<>();
        final Map<Node, TabInfo> tabs = new IdentityHashMap<>();
        final Set<String> usedFields = new HashSet<>();
        double dpi = 96.0;
        int unknownTabCount;
        int remappedTabCount;
        int visualCount;
        int ignoredCount;
        String className;
        String vb6Code = "";
        void warn(String s) { warnings.add("WARN: " + s); }
    }

    private Vb6WinFormsConverter() { }
    /**
     * コマンドラインの実行入口。入力.frmからVB.NETのWinFormsプロジェクト一式を生成する。
     *
     * @param args 入力.frm、出力ディレクトリ、任意のDPI（省略時96）
     * @throws Exception 入出力エラーまたは不正な.frm定義を検出した場合
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            System.err.println("使い方: java -jar Vb6WinFormsConverter.jar <input.frm> <output-dir> [dpi: default 96]");
            System.exit(2);
        }
        Path source = Path.of(args[0]).toAbsolutePath().normalize();
        Path out = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) throw new IOException("入力ファイルがありません: " + source);
        double dpi = args.length == 3 ? Double.parseDouble(args[2]) : 96.0;
        if (!(dpi >= 48.0 && dpi <= 768.0) || !Double.isFinite(dpi))
            throw new IllegalArgumentException("DPIは48～768を指定してください");
        // ステップ1: .frm解析 → ステップ2: コントロールの種類と配置を確定する。
        State st = parse(source);
        st.dpi = dpi;
        prepare(st);
        // 入力ファイルは変更せず、出力先ディレクトリへ生成する。
        Files.createDirectories(out);
        Path designer = out.resolve(st.className + ".Designer.vb");
        Path codeBehind = out.resolve(st.className + ".vb");
        Path project = out.resolve(st.className + "_Migrated.vbproj");
        Path program = out.resolve("Program.vb");
        Path audit = out.resolve(st.className + "_coordinate_audit.csv");
        Path report = out.resolve(st.className + "_migration_report.txt");
        Files.writeString(designer, generateDesigner(st), StandardCharsets.UTF_8);
        // 手書きの処理を誤って消さないよう、コードビハインドは既存なら上書きしない。
        if (!Files.exists(codeBehind)) {
            Files.writeString(codeBehind,
                "' VB6から移植したイベント処理・ビジネスロジックは未移行です。\r\n" +
                "' 元.frmのコード末尾を確認して必要な処理を移植してください。\r\n" +
                "Partial Class " + st.className + "\r\n" +
                "    Public Sub New()\r\n" +
                "        InitializeComponent()\r\n" +
                "    End Sub\r\n" +
                extractSimplePublicFields(st.vb6Code) +
                "End Class\r\n", StandardCharsets.UTF_8);
        } else st.warn("既存のコードビハインドは保護しました: " + codeBehind.getFileName());
        // Program.vbと.vbprojも既存ファイルを保護する。
        if (!Files.exists(program)) Files.writeString(program,
            "Imports System.Windows.Forms\r\n" +
            "Friend Module Program\r\n" +
            "    <System.STAThread()>\r\n" +
            "    Public Sub Main()\r\n" +
            "        Application.EnableVisualStyles()\r\n" +
            "        Application.SetCompatibleTextRenderingDefault(False)\r\n" +
            "        Application.Run(New " + st.className + "())\r\n" +
            "    End Sub\r\n" +
            "End Module\r\n", StandardCharsets.UTF_8);
        if (!Files.exists(project)) Files.writeString(project,
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
            "    <Compile Update=\"" + st.className + ".vb\">\r\n" +
            "      <SubType>Form</SubType>\r\n" +
            "    </Compile>\r\n" +
            "    <Compile Update=\"" + st.className + ".Designer.vb\">\r\n" +
            "      <DependentUpon>" + st.className + ".vb</DependentUpon>\r\n" +
            "    </Compile>\r\n" +
            "  </ItemGroup>\r\n" +
            "</Project>\r\n", StandardCharsets.UTF_8);
        // 移行結果の検証用データと警告レポートは毎回更新する。
        Files.writeString(audit, generateAudit(st), StandardCharsets.UTF_8);
        Files.writeString(report, generateReport(st, source), StandardCharsets.UTF_8);
        System.out.println("出力: " + designer);
        System.out.println("コードビハインド: " + codeBehind);
        System.out.println("Visual Studioプロジェクト: " + project);
        System.out.println("座標CSV: " + audit);
        System.out.println("移行レポート: " + report);
        System.out.println("コントロール: " + st.all.size() + " (出力 " + st.visualCount
                + " / 省略 " + st.ignoredCount + "), タブ座標補正: " + st.remappedTabCount
                + ", 所属タブ推定: " + st.unknownTabCount + ", 警告: " + st.warnings.size());
    }

    /**
     * VB6フォーム定義部を読み込み、スタックでBegin/Endを対応付けて階層化する。
     * Attribute VB_Name以降のVB6コードは別途保存し、ここでは解釈しない。
     *
     * @param source .frmファイルのパス
     * @return 構築したフォーム構造と未変換コード
     */
    private static State parse(Path source) throws IOException {
        State s = new State();
        String raw = Files.readString(source, VB6_CHARSET);
        String[] lines = raw.split("\\r?\\n", -1);
        // Beginでpush、EndでpopすることでVB6のネスト構造を復元する。
        Deque<Node> stack = new ArrayDeque<>();
        int codeStart = lines.length;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            // ここから先はVB6イベント処理などのコード部なので、レイアウト解析を終了する。
            if (line.startsWith("Attribute VB_Name")) { codeStart = i; break; }
            Matcher begin = BEGIN.matcher(line);
            Matcher nested = BEGIN_PROPERTY.matcher(line);
            if (begin.matches()) {
                Node parent = stack.peek();
                Node node = new Node("control", begin.group(1), begin.group(2), parent);
                if (parent != null) parent.children.add(node);
                else if (s.form == null) s.form = node;
                else s.warn("複数のトップレベルフォームがあります: " + node.name);
                stack.push(node);
                s.all.add(node);
            // BeginProperty Fontなどは別コントロールではなく親コントロールの属性。
            } else if (nested.matches()) {
                Node parent = stack.peek();
                if (parent == null) throw new IOException("BeginPropertyの親がありません (行 " + (i+1) + ")");
                Node node = new Node("property", "", nested.group(1), parent);
                parent.nested.put(node.name, node);
                stack.push(node);
            } else if (line.equals("End") || line.equals("EndProperty")) {
                if (stack.isEmpty()) throw new IOException("Endの数が不正です (行 " + (i+1) + ")");
                Node popped = stack.pop();
                if (line.equals("End") && !popped.kind.equals("control") ||
                    line.equals("EndProperty") && !popped.kind.equals("property"))
                    throw new IOException("Begin/Endの対応が不正です (行 " + (i+1) + ")");
            } else if (!stack.isEmpty()) {
                Matcher prop = PROPERTY.matcher(line);
                if (prop.matches()) stack.peek().props.put(prop.group(1).trim(), prop.group(2).trim());
            }
        }
        if (!stack.isEmpty()) throw new IOException("BeginとEndが対応していません: " + stack.peek().name);
        if (s.form == null || !"VB.Form".equals(s.form.vbType))
            throw new IOException("VB.Formが見つかりません");
        if (codeStart < lines.length) s.vb6Code = String.join("\n", Arrays.copyOfRange(lines, codeStart, lines.length));
        return s;
    }

    /**
     * 解析済みのフォームをWinFormsのコントロール名・種類・親・座標に変換する。
     * SSTabについては明示的な所属情報と位置補正を複数段階で解決する。
     */
    private static void prepare(State s) {
        // VB6のVB_Name（見つからなければフォーム名）を出力先のクラス名に採用。
        Matcher classMatch = Pattern.compile("(?m)^Attribute VB_Name\\s*=\\s*\"([^\"]+)\"")
                .matcher(s.vb6Code);
        s.className = safeIdentifier(classMatch.find() ? classMatch.group(1) : s.form.name);
        if (s.form.has("ScaleMode") && integer(s.form.prop("ScaleMode"), 1) != 1)
            s.warn("Form.ScaleMode がTwips以外です。座標は96DPIのTwipsとして暫定変換します");
        for (Node n : s.all) {
            n.id = n.name + (n.has("Index") ? "(" + integer(n.prop("Index"), 0) + ")" : "");
            n.field = uniqueField(s, safeIdentifier(n.name + (n.has("Index")
                    ? "_" + integer(n.prop("Index"), 0) : "")));
            n.winType = controlType(n.vbType);
            if (n.winType == null) {
                n.supported = false;
                s.ignoredCount++;
                s.warn(n.id + " (" + n.vbType + ") はWinForms相当コントロールがなく省略しました");
            } else if (n != s.form) s.visualCount++;
            if (n.has("Index"))
                s.warn(n.id + " はVB6のコントロール配列です。WinFormsでは " + n.field + " へ個別変換しました。配列イベント処理の移植は別途必要です");
            n.leftTwips = integer(n.prop("Left"), 0);
            n.topTwips = integer(n.prop("Top"), 0);
            n.widthTwips = integer(n.prop("Width"), integer(n.prop("ClientWidth"), 0));
            n.heightTwips = integer(n.prop("Height"), integer(n.prop("ClientHeight"), 0));
            n.pixelLeft = px(n.leftTwips, s.dpi);
            n.pixelTop = px(n.topTwips, s.dpi);
            n.pixelWidth = px(n.widthTwips, s.dpi);
            n.pixelHeight = px(n.heightTwips, s.dpi);
            if (n.id != null && s.byId.put(n.id, n) != null)
                s.warn("VB6コントロール識別子が重複しています: " + n.id);
            // SSTabは後で子要素を適切なTabPageへ移すため、タブ数と見出し高を保持する。
            if ("TabDlg.SSTab".equals(n.vbType)) {
                TabInfo t = new TabInfo(); t.tab = n;
                t.count = Math.max(1, integer(n.prop("Tabs"), 1));
                t.tabHeightTwips = integer(n.prop("TabHeight"), 520);
                s.tabs.put(n, t);
            }
            if (n.has("Picture")) s.warn(n.id + " のPictureは .frx 参照の可能性があります。画像リソースは未移行です");
            if (n.has("ItemData") || n.has("List"))
                s.warn(n.id + " のリスト初期値はFRXを参照する場合があります。実データは未移行です");
            if (n.has("ScaleMode") && integer(n.prop("ScaleMode"), 1) != 1)
                s.warn(n.id + " のScaleModeはTwipsではありません。子コントロール座標の手動検証が必要です");
        }
        for (TabInfo ti : s.tabs.values()) {
            for (Map.Entry<String, String> e : ti.tab.props.entrySet()) {
                Matcher match = TAB_CONTROL.matcher(e.getKey());
                if (match.matches()) {
                    int tabIdx = Integer.parseInt(match.group(1));
                    String original = strValue(e.getValue());
                    ti.assignments.put(original, tabIdx);
                }
            }
            for (int i = 0; i < ti.count; i++) {
                Node page = new Node("generated", "WinForms.TabPage", ti.tab.name + "_TabPage" + i, ti.tab);
                page.field = uniqueField(s, safeIdentifier(page.name));
                page.winType = "System.Windows.Forms.TabPage";
                page.tabIndex = i;
                page.id = page.name;
                ti.pages.add(page);
            }
            if (ti.tab.has("TabPicture(0)"))
                s.warn(ti.tab.id + " のTabPictureは移行しません（.frx画像未添付）");
        }
        // SSTab直下のコントロールは親をTabPageへ変更してから座標を補正する。
        // Frame等の内側では元の親子関係と相対座標を維持する。
        for (Node n : s.all) {
            if (n.parent != null && s.tabs.containsKey(n.parent)) {
                TabInfo ti = s.tabs.get(n.parent);
                Integer tab = ti.assignments.get(n.id);
                boolean inferred = false;
                // 明示されたタブ所属がない場合、同名コントロール配列から推定する。
                // それでも不明なら符号から暫定配置して警告に記録する。
                if (tab == null) {
                    // Array siblings can establish otherwise missing tab membership.
                    Set<Integer> siblings = new LinkedHashSet<>();
                    for (Node other : n.parent.children) {
                        if (other == n || !other.name.equals(n.name)) continue;
                        Integer assigned = ti.assignments.get(other.id);
                        if (assigned != null) siblings.add(assigned);
                    }
                    if (siblings.size() == 1) tab = siblings.iterator().next();
                    else tab = n.leftTwips >= 0 ? 0 : Math.min(1, ti.count - 1);
                    inferred = true;
                }
                if (tab < 0 || tab >= ti.count) {
                    s.warn(n.id + " のタブ番号が不正です: " + tab + "。Tab0に置き換えます");
                    tab = 0;
                }
                n.tabIndex = tab;
                if (inferred) {
                    s.unknownTabCount++;
                    s.warn(n.id + " はTab(n).Control一覧にありません。タブ" + tab + " に推定配置しました（要確認）");
                }
                // SSTabで非表示のタブを左へ追い出した大きな負座標だけを補正する。
                // 通常の小さな負座標は意図的な位置として扱い、補正しない。
                long correctedLeft = n.leftTwips < -10000
                    ? (long)n.leftTwips + SSTAB_HIDE_TWIPS : n.leftTwips;
                if (correctedLeft != n.leftTwips) s.remappedTabCount++;
                n.pixelLeft = px(correctedLeft, s.dpi);
                // VB6ではタブ見出し基準、WinForms TabPageでは内容領域基準なので、
                // TabHeightを引いてY座標を揃える。
                long correctedTop = (long)n.topTwips - ti.tabHeightTwips;
                // Some VB6 controls begin slightly above the tab header baseline.
                if (correctedTop < 0 && correctedTop > -200) correctedTop = 0;
                n.pixelTop = px(correctedTop, s.dpi);
                n.resolvedParent = ti.pages.get(tab).field;
            } else if (n.parent != null) {
                n.resolvedParent = n.parent.field;
            }
        }
    }

    /** VB.NETで大文字・小文字を区別しない名前の重複を連番で回避する。 */
    private static String uniqueField(State s, String requested) {
        String name = requested;
        int i = 2;
        while (!s.usedFields.add(name.toLowerCase(Locale.ROOT))) name = requested + "_" + i++;
        return name;
    }
    /** VB6の名称をVB.NETの識別子で使える文字構成へ正規化する。 */
    private static String safeIdentifier(String name) {
        String result = name.replaceAll("[^\\p{L}\\p{N}_]", "_");
        if (result.isEmpty()) result = "Unnamed";
        if (Character.isDigit(result.charAt(0))) result = "ctl_" + result;
        // Avoid collision with VB.NET keywords while preserving original-ish designer names.
        Set<String> keywords = Set.of("Class", "End", "New", "If", "Then", "Else", "Module", "Sub", "Me", "Public", "Private", "Friend", "Partial", "Namespace", "Property", "Integer", "String");
        if (keywords.contains(result)) result = "ctl_" + result;
        return result;
    }
    /**
     * VB6/ActiveXコントロールの型をWindows Formsの対応クラスに置き換える。
     * 未対応型はnullとし警告を出す。COMの動作互換までは実装しない。
     */
    private static String controlType(String type) {
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
    /** 数値属性を解析し、欠落・不正値の場合は指定された既定値を用いる。 */
    private static int integer(String value, int fallback) {
        if (value == null) return fallback;
        try { return Integer.parseInt(value.split("\\s+'", 2)[0].trim()); }
        catch (NumberFormatException ex) { return fallback; }
    }
    /**
     * TwipsからPixelへの換算。1inch=1440Twipsなので pixel=twips*dpi/1440。
     * 96DPIの場合は15Twips=1Pixelとなる。
     */
    private static int px(long twips, double dpi) {
        return (int)Math.round(twips * dpi / 1440.0);
    }
    /** VB6文字列リテラルを読み、二重の引用符を1文字の引用符へ戻す。 */
    private static String strValue(String vbValue) {
        if (vbValue == null) return "";
        String t = vbValue.trim();
        if (!t.startsWith("\"")) return t.split("\\s+'", 2)[0];
        StringBuilder b = new StringBuilder();
        for (int i = 1; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '"') {
                if (i+1 < t.length() && t.charAt(i+1) == '"') { b.append('"'); i++; }
                else break;
            } else b.append(c);
        }
        return b.toString();
    }
    /** Java文字列をVB.NETの文字列リテラル形式へエスケープする。 */
    private static String vbString(String javaString) {
        return "\"" + javaString.replace("\"", "\"\"").replace("\r", "")
                .replace("\n", "\" & vbCrLf & \"") + "\"";
    }
    private static boolean vbTrue(String value) { return integer(value, 0) != 0; }
    private static String bool(boolean value) { return value ? "True" : "False"; }
    private static String q(String n) { return "Me." + n; }
    private static String target(Node n) { return "VB.Form".equals(n.vbType) ? "Me" : q(n.field); }
    private static void line(StringBuilder b, String s) { b.append(s).append("\r\n"); }
    private static void set(StringBuilder b, Node n, String property, String expression) {
        line(b, "        " + target(n) + "." + property + " = " + expression);
    }
    /** ImageList等の、フォーム上の表示位置を持たないコンポーネントを判別する。 */
    private static boolean nonvisual(Node n) {
        return n.winType != null && (n.winType.endsWith(".ImageList") || n.winType.endsWith(".OpenFileDialog"));
    }
    /**
     * コントロール単位のName/Location/Size/Text/Fontと種類固有の属性を書き出す。
     * VB6とWinFormsで列挙値の意味が異なる属性はswitch内で個別対応する。
     */
    private static void configureControl(StringBuilder out, Node n, State s) {
        if (!n.supported || n == s.form) return;
        if (nonvisual(n)) return;
        set(out, n, "Name", vbString(n.field));
        set(out, n, "Location", "New System.Drawing.Point(" + n.pixelLeft + ", " + n.pixelTop + ")");
        if (n.has("Width") || n.has("Height"))
            set(out, n, "Size", "New System.Drawing.Size(" + Math.max(1, n.pixelWidth) + ", " + Math.max(1, n.pixelHeight) + ")");
        if (n.has("Caption")) set(out, n, "Text", vbString(strValue(n.prop("Caption"))));
        if (n.has("Text") && !n.vbType.equals("VB.TextBox")) set(out, n, "Text", vbString(strValue(n.prop("Text"))));
        if (n.has("Visible")) set(out, n, "Visible", bool(vbTrue(n.prop("Visible"))));
        if (n.has("Enabled")) set(out, n, "Enabled", bool(vbTrue(n.prop("Enabled"))));
        if (n.has("TabIndex")) set(out, n, "TabIndex", Integer.toString(integer(n.prop("TabIndex"), 0)));
        // フォント名・ポイント数・太字などはBeginProperty Fontから復元する。
        Node font = n.nested.get("Font");
        if (font != null) {
            String fontName = strValue(font.prop("Name"));
            if (fontName.isEmpty()) fontName = "MS UI Gothic";
            String fontSize = font.prop("Size") == null ? "9" : font.prop("Size").trim();
            try { new BigDecimal(fontSize); }
            catch (NumberFormatException ex) { fontSize = "9"; }
            List<String> styles = new ArrayList<>();
            if (integer(font.prop("Weight"), 400) >= 700) styles.add("System.Drawing.FontStyle.Bold");
            if (vbTrue(font.prop("Italic"))) styles.add("System.Drawing.FontStyle.Italic");
            if (vbTrue(font.prop("Underline"))) styles.add("System.Drawing.FontStyle.Underline");
            if (vbTrue(font.prop("Strikethrough"))) styles.add("System.Drawing.FontStyle.Strikeout");
            String style = styles.isEmpty() ? "System.Drawing.FontStyle.Regular" : String.join(" Or ", styles);
            int charset = integer(font.prop("Charset"), 0);
            set(out, n, "Font", "New System.Drawing.Font(" + vbString(fontName) + ", CSng(" + fontSize
                    + "), " + style + ", System.Drawing.GraphicsUnit.Point, CByte(" + Math.max(0, Math.min(255, charset)) + "))");
        }
        if (n.has("BackColor")) {
            String color = toColor(n.prop("BackColor"));
            if (color != null) set(out, n, "BackColor", color);
        }
        if (n.has("ForeColor")) {
            String color = toColor(n.prop("ForeColor"));
            if (color != null) set(out, n, "ForeColor", color);
        }
        // コントロール固有のVB6プロパティをWinFormsの対応プロパティに変換。
        switch (n.vbType) {
            case "VB.Label" -> {
                set(out, n, "AutoSize", "False");
                if (n.has("Alignment")) set(out, n, "TextAlign", switch (integer(n.prop("Alignment"), 0)) {
                    case 1 -> "System.Drawing.ContentAlignment.MiddleRight";
                    case 2 -> "System.Drawing.ContentAlignment.MiddleCenter";
                    default -> "System.Drawing.ContentAlignment.MiddleLeft";
                });
            }
            case "VB.TextBox" -> {
                if (n.has("Text")) set(out, n, "Text", vbString(strValue(n.prop("Text"))));
                if (n.has("Locked")) set(out, n, "ReadOnly", bool(vbTrue(n.prop("Locked"))));
                if (n.has("MaxLength")) set(out, n, "MaxLength", Integer.toString(integer(n.prop("MaxLength"), 32767)));
                if (n.has("MultiLine")) set(out, n, "Multiline", bool(vbTrue(n.prop("MultiLine"))));
                if (n.has("ScrollBars")) {
                    String bars = switch (integer(n.prop("ScrollBars"), 0)) {
                        case 1 -> "Vertical"; case 2 -> "Horizontal"; case 3 -> "Both"; default -> "None";
                    };
                    set(out, n, "ScrollBars", "System.Windows.Forms.ScrollBars." + bars);
                }
            }
            case "VB.CheckBox", "VB.OptionButton" -> {
                if (n.has("Value")) set(out, n, "Checked", bool(vbTrue(n.prop("Value"))));
            }
            case "VB.ComboBox" -> {
                if (n.has("Style")) {
                    String style = switch (integer(n.prop("Style"), 0)) {
                        case 1 -> "Simple"; case 2 -> "DropDownList"; default -> "DropDown";
                    };
                    set(out, n, "DropDownStyle", "System.Windows.Forms.ComboBoxStyle." + style);
                }
            }
            case "VB.Image" -> {
                String mode = vbTrue(n.prop("Stretch")) ? "StretchImage" : "Normal";
                set(out, n, "SizeMode", "System.Windows.Forms.PictureBoxSizeMode." + mode);
            }
            case "VB.ListBox" -> {
                if (n.has("MultiSelect")) {
                    String mode = switch (integer(n.prop("MultiSelect"), 0)) {
                        case 1 -> "MultiSimple"; case 2 -> "MultiExtended"; default -> "One";
                    };
                    set(out, n, "SelectionMode", "System.Windows.Forms.SelectionMode." + mode);
                }
            }
            case "VB.Frame" -> { /* GroupBox. Children are parent-relative. */ }
            case "VB.PictureBox" -> {
                if (n.has("BorderStyle")) set(out, n, "BorderStyle",
                    "System.Windows.Forms.BorderStyle." + (integer(n.prop("BorderStyle"), 0) == 0 ? "None" : "FixedSingle"));
            }
            case "MSComctlLib.ListView" -> {
                int view = integer(n.prop("View"), 3);
                String style = switch (view) {
                    case 0 -> "LargeIcon"; case 1 -> "Details"; case 2 -> "SmallIcon"; default -> "List";
                };
                set(out, n, "View", "System.Windows.Forms.View." + style);
            }
            case "SHDocVwCtl.WebBrowser" -> {
                set(out, n, "ScriptErrorsSuppressed", "True");
            }
        }
    }
    /**
     * OLE_COLOR形式の値をSystem.Drawing.Color式にする。
     * VB6通常色はBGR順で、&H800000xxはWindowsのシステム色を表す。
     */
    private static String toColor(String raw) {
        if (raw == null) return null;
        Matcher m = Pattern.compile("&H([0-9A-Fa-f]{1,8})&?").matcher(raw.trim());
        if (!m.find()) return null;
        long value = Long.parseUnsignedLong(m.group(1), 16);
        if ((value & 0xFF000000L) == 0x80000000L) {
            return switch ((int)(value & 0xFF)) {
                case 5 -> "System.Drawing.SystemColors.Window";
                case 8 -> "System.Drawing.SystemColors.WindowText";
                case 15 -> "System.Drawing.SystemColors.Control";
                case 18 -> "System.Drawing.SystemColors.ControlText";
                default -> null;
            };
        }
        int b = (int)((value >> 16) & 255), g = (int)((value >> 8) & 255), r = (int)(value & 255);
        return "System.Drawing.Color.FromArgb(" + r + ", " + g + ", " + b + ")";
    }
    /**
     * Designer.vbのInitializeComponent()を生成する。
     * コントロールのインスタンス生成→属性設定→親への追加→フィールド宣言の順に出力。
     */
    private static String generateDesigner(State s) {
        StringBuilder out = new StringBuilder();
        line(out, "' 自動生成: Vb6WinFormsConverter (Java 17) / レイアウト変換のみ");
        line(out, "' 自動生成されたため手作業変更は避けてください。制限は migration_report.txt を参照。");
        line(out, "<Global.Microsoft.VisualBasic.CompilerServices.DesignerGenerated()> _");
        line(out, "Partial Class " + s.className);
        line(out, "    Inherits System.Windows.Forms.Form");
        line(out, "");
        line(out, "    <Global.System.Diagnostics.DebuggerNonUserCode()> _");
        line(out, "    Protected Overrides Sub Dispose(disposing As Boolean)");
        line(out, "        Try");
        line(out, "            If disposing AndAlso components IsNot Nothing Then components.Dispose()");
        line(out, "        Finally");
        line(out, "            MyBase.Dispose(disposing)");
        line(out, "        End Try");
        line(out, "    End Sub");
        line(out, "");
        line(out, "    Private components As System.ComponentModel.IContainer");
        line(out, "");
        line(out, "    <Global.System.Diagnostics.DebuggerStepThrough()> _");
        line(out, "    Private Sub InitializeComponent()");
        line(out, "        Me.components = New System.ComponentModel.Container()");
        // 先にコントロールの実体をすべて作り、Controls.Addが参照できるようにする。
        for (Node n : s.all) {
            if (n != s.form && n.supported) {
                if (n.winType.endsWith(".ImageList"))
                    line(out, "        Me." + n.field + " = New " + n.winType + "(Me.components)");
                else line(out, "        Me." + n.field + " = New " + n.winType + "()");
            }
        }
        for (TabInfo t : s.tabs.values()) for (Node p : t.pages)
            line(out, "        Me." + p.field + " = New System.Windows.Forms.TabPage()");
        line(out, "        Me.SuspendLayout()");
        // 次に位置・サイズ・表示文字などの基本属性を設定する。
        for (Node n : s.all) {
            if (n.supported && n != s.form) configureControl(out, n, s);
        }
        for (TabInfo ti : s.tabs.values()) {
            for (Node page : ti.pages) {
                int i = page.tabIndex;
                set(out, page, "Name", vbString(page.field));
                set(out, page, "Text", vbString(strValue(ti.tab.prop("TabCaption(" + i + ")"))));
                set(out, page, "UseVisualStyleBackColor", "True");
            }
            set(out, ti.tab, "Multiline", "True");
            set(out, ti.tab, "SizeMode", "System.Windows.Forms.TabSizeMode.Fixed");
            set(out, ti.tab, "ItemSize", "New System.Drawing.Size(" + px(1800, s.dpi) + ", " + Math.max(1, px(ti.tabHeightTwips, s.dpi)) + ")");
        }
        // ネストしたFrame等への追加と、SSTab直下のTabPageへの追加を行う。
        for (Node n : s.all) {
            if (n == s.form || n.parent == s.form || !n.supported || nonvisual(n)) continue;
            if (s.tabs.containsKey(n.parent)) {
                // Tab pages may be omitted if a tab control is skipped in the future.
                line(out, "        Me." + n.resolvedParent + ".Controls.Add(Me." + n.field + ")");
            } else if (n.parent != null && n.parent.supported) {
                line(out, "        Me." + n.parent.field + ".Controls.Add(Me." + n.field + ")");
            }
        }
        for (TabInfo ti : s.tabs.values()) for (Node page : ti.pages)
            line(out, "        Me." + ti.tab.field + ".TabPages.Add(Me." + page.field + ")");
        // フォーム内部のサイズを設定。AutoScaleMode=NoneでDPI自動拡大を防ぐ。
        set(out, s.form, "Name", vbString(s.className));
        set(out, s.form, "AutoScaleMode", "System.Windows.Forms.AutoScaleMode.None");
        set(out, s.form, "ClientSize", "New System.Drawing.Size(" + px(integer(s.form.prop("ClientWidth"), 0), s.dpi) + ", "
                + px(integer(s.form.prop("ClientHeight"), 0), s.dpi) + ")");
        set(out, s.form, "Text", vbString(strValue(s.form.prop("Caption"))));
        if (s.form.has("BorderStyle")) {
            String border = switch (integer(s.form.prop("BorderStyle"), 2)) {
                case 0 -> "None"; case 1 -> "FixedSingle"; case 3 -> "FixedDialog";
                case 4 -> "FixedToolWindow"; case 5 -> "SizableToolWindow"; default -> "Sizable";
            };
            set(out, s.form, "FormBorderStyle", "System.Windows.Forms.FormBorderStyle." + border);
        }
        if (s.form.has("MaxButton")) set(out, s.form, "MaximizeBox", bool(vbTrue(s.form.prop("MaxButton"))));
        if (s.form.has("MinButton")) set(out, s.form, "MinimizeBox", bool(vbTrue(s.form.prop("MinButton"))));
        if (s.form.has("StartUpPosition")) {
            String start = switch (integer(s.form.prop("StartUpPosition"), 0)) {
                case 1 -> "CenterParent"; case 2 -> "CenterScreen"; case 3 -> "WindowsDefaultLocation";
                case 0 -> "Manual"; default -> "WindowsDefaultBounds";
            };
            set(out, s.form, "StartPosition", "System.Windows.Forms.FormStartPosition." + start);
        }
        for (Node child : s.form.children) if (child.supported && !nonvisual(child))
            line(out, "        Me.Controls.Add(Me." + child.field + ")");
        line(out, "        Me.ResumeLayout(False)");
        line(out, "    End Sub");
        line(out, "");
        for (Node n : s.all) if (n != s.form && n.supported)
            line(out, "    Friend WithEvents " + n.field + " As " + n.winType);
        for (TabInfo t : s.tabs.values()) for (Node p : t.pages)
            line(out, "    Friend WithEvents " + p.field + " As " + p.winType);
        line(out, "End Class");
        return out.toString();
    }
    /**
     * VB6コードから単純なPublicフィールド宣言だけを抽出してコードビハインドへ追加する。
     * イベント・業務ロジック・コントロール配列の動作は自動移植しない。
     */
    private static String extractSimplePublicFields(String code) {
        StringBuilder output = new StringBuilder();
        Matcher matcher = Pattern.compile("(?m)^Public\\s+([A-Za-z_][A-Za-z0-9_]*)\\s+As\\s+(Boolean|Integer|Long|String|Double|Single)\\s*$", Pattern.CASE_INSENSITIVE).matcher(code);
        while (matcher.find()) {
            output.append("    Public ").append(matcher.group(1)).append(" As ").append(matcher.group(2)).append("\r\n");
        }
        return output.toString();
    }
    /** CSVフィールドを引用符で囲み、フィールド内の引用符を二重化する。 */
    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
    /** 元のTwips、換算後のPixel、親子関係、未対応の有無を一覧CSVへ出力する。 */
    private static String generateAudit(State s) {
        StringBuilder b = new StringBuilder();
        line(b, "VB6Type,VB6Name,WinFormsType,WinFormsField,VB6Parent,WinFormsParent,TabIndex,LeftTwips,TopTwips,WidthTwips,HeightTwips,PixelX,PixelY,PixelWidth,PixelHeight,Output");
        for (Node n : s.all) {
            String parent = n.parent == null ? "" : n.parent.id;
            String resolved = n.resolvedParent.isEmpty() ? (n.parent == null ? "" : n.parent.field) : n.resolvedParent;
            String[] v = {n.vbType,n.id,n.winType==null ? "" : n.winType,n.field,parent,resolved,
                n.tabIndex < 0 ? "" : Integer.toString(n.tabIndex),
                Integer.toString(n.leftTwips), Integer.toString(n.topTwips),Integer.toString(n.widthTwips),Integer.toString(n.heightTwips),
                Integer.toString(n.pixelLeft),Integer.toString(n.pixelTop),Integer.toString(n.pixelWidth),Integer.toString(n.pixelHeight),
                n.supported ? "YES" : "NO"};
            line(b, String.join(",", Arrays.stream(v).map(Vb6WinFormsConverter::csv).toList()));
        }
        return b.toString();
    }
    /**
     * 前提条件・未移植要素・座標補正数・推定したタブ・警告をテキストへ出力する。
     * 元VB6コードは参考資料として掲載し、自動移行済みとは扱わない。
     */
    private static String generateReport(State s, Path source) {
        StringBuilder b = new StringBuilder();
        line(b, "VB6 -> VB.NET WinForms 座標変換レポート");
        line(b, "入力: " + source.getFileName());
        line(b, "フォーム: " + s.className);
        line(b, "DPI: " + s.dpi + " (1px = " + 1440.0 / s.dpi + " Twips)");
        line(b, "フォームを含む定義数: " + s.all.size());
        line(b, "SSTab非表示座標補正数: " + s.remappedTabCount);
        line(b, "タブ割り当て推定数: " + s.unknownTabCount);
        line(b, "警告数: " + s.warnings.size());
        line(b, "");
        line(b, "移行範囲: フォーム配置、標準コントロール、Frame親子構造、SSTab->TabControl、基本的な属性とフォント。");
        line(b, "制限: VB6実行コード・イベント・コントロール配列イベント・COMオブジェクトの動作は自動変換しません。");
        line(b, "制限: .frx は添付されていないため Picture/TabPicture/ItemData/ImageList画像を復元しません。");
        line(b, "制限: 元フォーム内の ScaleMode はTwips（標準）を前提としています。");
        line(b, "制限: SSTabのY座標はTabHeightを引き、負の小さい値は0に丸めます。手動で見た目を確認してください。");
        line(b, "制限: Form.AutoScaleMode=NoneのためDPI倍率・フォント環境で見た目がずれることがあります。");
        line(b, "注意: VB.NETの.Windows.Forms.Formの自動生成レイアウトとしてVisual Studioで開けますが、未サポート要素は警告を確認してください。");
        line(b, "");
        line(b, "=== 警告 ===");
        if (s.warnings.isEmpty()) line(b, "なし");
        for (String warn : s.warnings) line(b, warn);
        line(b, "");
        line(b, "=== 元.frm内のコード（参考・未移植） ===");
        line(b, s.vb6Code);
        return b.toString();
    }
}
