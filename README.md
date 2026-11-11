# VB6 `.frm` → VB.NET WinForms Designer 変換ツール（Java 21）

添付サンプル `ReportWindowCoorsDummy.frm` を対象に作成した、実行時は外部ライブラリ不要のコンソールツールです。**画面レイアウトの移行支援**用であり、VB6アプリケーションを丸ごとVB.NETへ移行するものではありません。

## 生成物

- `ReportWindow.Designer.vb`：WinForms フォームおよび標準コントロールのレイアウト。
- `ReportWindow.vb`：`InitializeComponent()` を呼ぶフォームのコードビハインド（既存ファイルは上書きしません）。
- `Program.vb`：アプリケーションのエントリポイント（既存ファイルは上書きしません）。
- `ReportWindow_Migrated.vbproj`：.NET 8 Windows Forms のVisual Studio用プロジェクト（既存ファイルは上書きしません）。
- `ReportWindow_Migrated.slnx`：上記のVB.NETプロジェクトを相対パスで参照するXMLソリューション（既存ファイルは上書きしません）。
- `ReportWindow_coordinate_audit.csv`：元のTwips座標、変換後ピクセル座標、タブ所属の確認用CSV。
- `ReportWindow_migration_report.txt`：未移行項目、タブ所属の推定、コントロール配列の変更など。
- `convertVB6Form.log`：変換成功時の出力先・件数など、標準出力と同じ完了メッセージを日時・INFOレベル付きで記録するUTF-8ログ。指定した出力フォルダに作成し、再実行時は追記します。

完了メッセージはコンソール（標準出力）とログファイルの両方に出力します。Java標準の`java.util.logging`を使用するため、ログ用ライブラリの追加は不要です。
入力不正などによる変換失敗は従来どおり標準エラーで通知し、この完了ログの対象には含めません。移行上の警告の詳細は従来どおりmigration_reportを確認してください。

変換処理の変数には用途を日本語で記載します。フィールドや引数はJavadocで説明し、コメント付きの処理が続く箇所は空行で区切ります。

## Eclipseで開く（Java 21）

このリポジトリは **Eclipse標準Javaプロジェクト** として構成しています。Maven・Gradleは不要です。コンパイル時には同梱のLombok 1.18.48を使用します。EclipseへのLombok導入手順は後述します。

1. Java 21 JDKと、Eclipse IDE for Java Developers（Java 21対応版）を用意します。
2. GitHubから `convertVB6Form` をcloneします。既にclone済みなら `git pull` で更新してください。
3. Eclipseで **File → Import → General → Existing Projects into Workspace** を選びます。
4. **Select root directory** にcloneした `convertVB6Form` フォルダを指定し、表示されたプロジェクトを **Finish** で取り込みます。
5. `src/Vb6WinFormsConverter.java` を開きます。Eclipse側は `JavaSE-21`、ソース文字コード `UTF-8` に設定済みです。
6. 実行する場合は **Run → Run Configurations → Java Application** でメインクラス `Vb6WinFormsConverter` を選択し、**Arguments → Program arguments** に入力.frmのパスと出力先フォルダを指定します。例：`C:\\VB6Source\\ReportWindowCoorsDummy.frm C:\\VBNetConverted`

出力する `bin/` と `output/` はGit管理の対象外です。Eclipseの自動ビルドでも `bin/` にクラスファイルが生成されます。EclipseにJRE System Libraryのエラーが出る場合は、**Window → Preferences → Java → Installed JREs** でJava 21 JDKを追加・選択してください。

## Javaコーディング規約への対応

本プロジェクトはフューチャー株式会社が公開する
[Javaコーディング規約](https://future-architect.github.io/coding-standards/documents/forJava/Java%E3%82%B3%E3%83%BC%E3%83%87%E3%82%A3%E3%83%B3%E3%82%B0%E8%A6%8F%E7%B4%84.html)
を参考に、次の方針で整理しています。

- **1ファイル1クラス**を守り、内部・匿名クラスを使いません。
- インデントはタブのみ（表示幅4）、ソースはUTF-8、制御文には省略せず波括弧を付けます。
- **すべてのif文・for文の説明コメントを含むまとまりの前に空行を1行以上**入れます。メソッド・ブロックの先頭、ネストした文、拡張for文も対象です。
  `} else if (...) {`はその行の直前に空行を入れ、構文は同じ行に維持します。説明コメントがある場合は、そのコメントの前に空行を置き、コメントとif/for行は隣接させます。
- **すべてのメソッド間に空行を1行以上**入れます。コンストラクタも同様です。空行は、前のメソッドの終わりと、次のメソッドのJavadoc・アノテーションを含む宣言全体との間に置きます。
  `EmptyLineSeparator`で検査し、Eclipseの整形設定もメソッド間に空行1行を入れるようにしています。
- フィールドはprivateにし、内部データの参照・更新は`@Data`で生成するアクセサを通します。日本語の説明はフィールドのJavadocに記載します。
- メソッド・変数には用途の分かる名前を使用し、変換処理の名前は`to...`に揃えます。
- クラスに`@author`・`@version`・`@since`、メソッドに`@param`・`@return`・必要な`@throws`を記載します。
- 実行処理はJavaの標準APIを使用し、Java 21とLombokでビルド可能な構成を維持します。

**互換性上の例外:** 既存の`java -cp bin Vb6WinFormsConverter ...`コマンドを変更しないよう、起動クラス`Vb6WinFormsConverter`はデフォルトパッケージを保持します。データ保持クラスは`entity`パッケージに配置しています。将来パッケージを導入するときは、利用者の実行コマンドとの互換性を検討してください。

Eclipseでは`Ctrl+Shift+F`で整形できます。プロジェクトの`.settings/org.eclipse.jdt.core.prefs`と`.editorconfig`にタブ（表示幅4）・UTF-8の設定を登録しています。

### Lombok（getter/setterの自動生成）

3つのEntityに[`@Data`](https://projectlombok.org/features/Data)を使用し、手書きの単純なgetter/setterを省略しています。
`final`のコレクションにはgetterのみが生成され、呼び出し側は従来どおりその内容を更新できます。
`getProperty(String)`など属性の検索処理や集計メソッド、コンストラクタは手書きで保持しています。

`@Data`はequals/hashCode/toStringも生成するため、本プロジェクトでは次のように調整しています。

- Entityは可変で親子の循環参照があるため、equals/hashCodeは手書きで**従来のインスタンス同一性**を維持します。
- toStringは`@ToString(onlyExplicitlyIncluded = true)`で名前・件数等の指定フィールドだけを表示し、親子・コレクションを再帰的にたどりません。
- Lombokはコンパイル時だけ必要です。`lib/lombok-1.18.48.jar`を共有し、`.classpath`と`build.sh`に登録済みです。
  配布元・SHA-256・ライセンスは`lib/README.md`に記載しています。

#### EclipseへのLombok導入

1. Eclipseを終了し、Java 21で`java -jar lib/lombok-1.18.48.jar`を実行します。
2. インストーラで使用するEclipseを選択します。自動検出されない場合は**Specify location...**からEclipseの実行ファイルを指定します。
3. **Install / Update**で導入した後、Eclipseを起動します。既に起動していた場合は完全に終了してから起動してください。
4. **Help → About Eclipse IDE**の著作権表示末尾にLombokのバージョンがあることを確認します。
5. プロジェクトをRefreshし、**Project → Clean...**で再ビルドします。生成getter/setterの未定義エラーがないことを確認してください。

代替として、**Help → Install New Software...**から公式更新サイト`https://projectlombok.org/p2`でも導入できます。
公式サイトはこのインストーラが未署名であること、通常のRestartだけでは不十分で完全終了・再起動が必要であることを案内しています。
手順の出典は[公式Eclipseガイド](https://projectlombok.org/setup/eclipse)です。Eclipse画面での導入操作はこの環境では未検証です。
JARをBuild Pathに追加するだけでは、Eclipseのエディタ・コンパイラへの導入は完了しません。

### Eclipse用Checkstyle（共有lint）

`checkstyle.xml`が規則本体、`.checkstyle`がプロジェクト相対の参照と対象範囲（`src/**/*.java`）です。
`.project`にもCheckstyleのbuilder/natureを登録しているため、プラグイン導入後は共有設定で検査できます。
個人の絶対パスやワークスペース設定をGitに登録する必要はありません。Checkstyleは検査用ツールです。コンパイル用のLombokとは独立しており、実行時にはどちらも不要です。

#### プラグインの導入

2026-11-08確認時点の[Eclipse Checkstyle Plugin最新リリースは14.0.0](https://github.com/checkstyle/eclipse-cs/releases/tag/14.0.0)です。
本設定は同梱エンジンに合わせて**Checkstyle 14.0.0**で検証しています。
Checkstyle本体とEclipseプラグインはリリース周期が異なるため、番号を混同しないでください。

1. Eclipseを**Java 21以上**で起動できる環境を用意します。プラグイン14.0.0の
   [公式manifest](https://github.com/checkstyle/eclipse-cs/blob/14.0.0/net.sf.eclipsecs.checkstyle/META-INF/MANIFEST.MF)
   は`JavaSE-21`を要求します。Eclipse自体がさらに新しいJavaを要求する版では、その要求にも従います。
   必要なら`eclipse.ini`の`-vmargs`より前に`-vm`とJDKの`bin/javaw.exe`の絶対パスを別々の行で指定します。
   本プロジェクトもJava 21を対象とし、JRE System Libraryは`JavaSE-21`、compiler complianceは21に設定しています。
2. **Help → Eclipse Marketplace...** で`Checkstyle`または`Checkstyle Plug-in`を検索し、**Checkstyle Plug-in**の**Install**を選びます。
3. インストール内容・ライセンス等を確認して完了し、Eclipseを再起動します。
4. **Help → About Eclipse IDE → Installation Details**でインストールされたプラグインのバージョンを確認します。

[公式の導入案内](https://github.com/checkstyle/eclipse-cs#installation)は最新版にMarketplaceを案内しています。
Marketplaceが使えない場合は**Help → Install New Software... → Add...**で名前`Checkstyle`、
Location **`https://checkstyle.org/eclipse-cs-update-site/`**を登録してインストールします。
特定の旧版が必要な場合だけ、**Show only the latest versions of available software**を外して版を選びます。
古い記事のSourceForge更新URLは使いません。Java 21未満でEclipseを起動している環境では、最新版14.0.0は利用できません。
旧プラグインへの適合性は未検証のため、まずEclipseの起動Javaを更新してください。

#### このプロジェクトで有効化する（推奨）

1. プラグイン導入後にプロジェクトを取り込みます。既に開いている場合は更新（**Refresh**）します。
2. プロジェクトを右クリックし**Properties → Checkstyle**を開きます。
3. **Local Check Configurations**に`Future Java (project)`があることを確認します。
   設定ファイルはプロジェクト内の`checkstyle.xml`です。
4. メイン画面の検査設定に`Future Java (project)`が選択され、**Checkstyle active for this project**がオンであることを確認し、**Apply and Close**します。
   `.checkstyle`の検査対象は`src/`配下のJavaソースです。`build/`内の検証データや生成物は対象にしません。
5. **Project → Build Automatically**をオンにします。初回はプロジェクトを右クリックし**Checkstyle → Check Code with Checkstyle**で全体を検査します。
6. **Window → Show View → Problems**で警告を確認します。警告をダブルクリックすると該当行へ移動できます。

共有設定が表示されない場合は、Properties → Checkstyle → Local Check Configurations → **New...**で
Type **Project Relative Configuration**を選び、名前`Future Java (project)`、Locationは**Browse...**から
`/convertVB6Form/checkstyle.xml`を選択します。その後、メイン画面でその設定を選んで有効化してください。
設定ファイルの保護をオンにし、GUIによる規則の書き換えを防ぐことを推奨します。

#### 外部の設定ファイルとして取り込む場合

別プロジェクトから同じ規則を使う場合は、**Window → Preferences → Checkstyle → New...**で
Type **External Configuration**、任意の名前、Locationに`checkstyle.xml`の**絶対パス**を指定し、**OK → Apply and Close**します。
次に対象プロジェクトの**Properties → Checkstyle**でその名前を選び、**Checkstyle active for this project**をオンにします。
External Configurationはファイルをコピーせず参照します。移動・削除すると参照できなくなります。
チーム共有では本リポジトリのProject Relative Configurationを使ってください。
Internal Configurationの**Import...**はコピーの取り込みなので、元ファイルの更新には追従しません。
詳細は[公式設定種別ガイド](https://checkstyle.org/eclipse-cs/#!/configtypes)と
[プロジェクト設定ガイド](https://checkstyle.org/eclipse-cs/#!/project-setup)を参照してください。

#### 適用規則と意図的な制限

Future規約の完全な自動判定ではなく、機械的に検査できる部分を選んだプロジェクト用設定です。
すべてwarningとして表示します。警告を無視してよいという意味ではありません。

| 対象 | 自動検査 |
| --- | --- |
| 命名 | 型・メソッド・フィールド・定数・引数・ローカル変数・存在するパッケージの命名形式 |
| 構造 | トップレベル型は1ファイル1型、ファイル名と型名の一致、内部・ローカル・匿名型の禁止（プロジェクト独自要件） |
| import・宣言 | ワイルドカード・重複・未使用import、フィールドの公開制限、修飾子順序・冗長修飾子 |
| 書式・基本品質 | 制御文の波括弧、波括弧位置、1行1文、演算子やカンマ等の空白、空文、boolean比較の簡略化、equals/hashCodeの対 |
| Javadoc | private・packageを含む型・メソッド・コンストラクタの存在、型のauthor/version、引数・戻り値・明示した例外のタグ |

- **日本語Javadoc、内容の正しさ、`@since`、フィールド説明はレビューで確認**します。日本語文字の有無だけでは説明品質を判定できません。
  `@throws`は宣言・明示的なthrowを確認しますが、呼び出し先から伝播するすべての例外や型の継承関係まで解決しません。
- **起動クラスのデフォルトパッケージは既存の起動コマンドとの互換性のため維持**します。Entityクラスは`entity`パッケージを使用します。`PackageDeclaration`は有効にしていません。
- **インデントはタブのみ、表示幅は4**とします。スペースによるインデントやタブとスペースの混在は、コメント行・継続行も含め`RegexpSingleline`で検出します。
  演算子間の空白や文字列内の空白は使用できます。Javadocの行頭`*`の前にもスペースを置きません。EclipseのJavadoc自動整形は無効にし、この形式を維持します。
  インデントの段数はEclipseの共有formatter設定とレビューで確認します。`Indentation`による段数の強制は行いません。
- **if・for直前の空行はプロジェクト独自規約**として`RegexpMultiline`で検査します。対象は行頭のif/forと`} else if`形式です。
  ソース行を検査する方式のため、将来テキストブロックや複数行コメント内に同形式の行を追加する場合は誤検出の有無を確認してください。
  Eclipseで整形しても空行を自動追加する機能ではないため、Checkstyleの指摘に従って追加してください。
- **長い行の数値上限、メソッド長、循環的複雑度、MagicNumber、全引数final、ユーティリティクラス化の強制は採用しません。**
  規約にない一律の数値制限や、座標変換・生成文字列への過剰な警告を避けます。長い生成文字列も読みやすさはレビューします。
- 不等号の左向き統一、インターフェース型の選択、命名の意味、適切なfinal、例外設計、リソース管理、非推奨APIの使用などは、
  型解決や設計判断が必要なためこのlintだけでは網羅しません。未使用のローカル変数や非推奨APIはEclipseのJavaコンパイラ警告も併用します。
- `HiddenField`はローカル変数・コンストラクタ等への誤警告や規約の例外との調整が必要なため採用せず、引数とフィールドの衝突はレビューします。
  getter/setterや`to...`の意味も正規表現だけでは強制しません。
- 追加プラグイン専用の独自チェックやソース全体の抑制設定は使っていません。設定変更時は以下の検知テストを再実行してください。

#### コマンドラインでの再検証

Java 21以上と[Checkstyle 14.0.0公式リリース](https://github.com/checkstyle/checkstyle/releases/tag/checkstyle-14.0.0)の
`checkstyle-14.0.0-all.jar`を用意します。アプリの実行には不要です。通常の（依存を含まない）JARだけでは動きません。

```bash
# Git Bash / Linux等。パスは手元の配置に合わせる
export CHECKSTYLE_CLASSPATH=/absolute/path/checkstyle-14.0.0-all.jar
bash tests/checkstyle_test.sh
bash tests/smoke_test.sh
```

Windows PowerShellから直接lintを実行する場合は次のとおりです（`java`がJava 21以上であること）。

```powershell
java -cp C:\tools\checkstyle-14.0.0-all.jar com.puppycrawl.tools.checkstyle.Main -c checkstyle.xml -f xml -o checkstyle-result.xml src
```

warningはCLI終了コードが0でも発生し得るため、結果XMLに`<error ... severity="warning" ...>`がないことも確認します。
`tests/checkstyle_test.sh`は既存4ソースの違反0件に加え、意図的な違反を検出すること、内部・ローカル・匿名クラスをすべて検出することを確認します。
2026-11-08にCheckstyle 14.0.0／Java 21でlint検知テストと`--release 21`によるビルド・変換スモークテストを実行し、いずれも成功しました。
Eclipse画面上の操作はこの検証環境では実行しておらず、共有設定の読込形式と手順はプラグイン14.0.0の公式実装・資料で確認しています。

### スモークテスト

同梱Lombokでコンパイルし、最小限のVB6フォームを生成して変換結果を確認できます。実行時にLombokは不要です。

```bash
bash tests/smoke_test.sh
```

このテストはJava 21コンパイル、Twips→Pixel換算、固定ラベルの自動サイズ調整、Designer.vbと監査レポートの生成を確認します。テスト対象フォームは一時ディレクトリに作成し、完了時に削除します。
あわせて`EntityBehaviorTest.java`で、Lombok生成アクセサ、親子の循環参照、同一性比較、属性変更後のHashSet検索を検査します。実行時のクラスパスにLombokは含めません。

## Javaソースの構成とJavadoc

ソースコードは **1ファイル1クラス** で構成し、内部クラスは使用していません。データ保持クラスはクラス名に`Entity`を付け、`src/entity/`（`package entity`）に配置しています。メンバ変数の定義とメソッド・コンストラクタのJavadocの間には空行を入れています。実行時はJava 21の標準APIだけで動作します。EclipseではLombokの導入が必要です。

| ファイル | 担当処理 |
| --- | --- |
| `Vb6WinFormsConverter.java` | .frm解析、Twips座標の変換、SSTabの補正、VB.NETファイル・監査資料の出力 |
| `entity/Vb6FormNodeEntity.java` | コントロール階層、属性、Twips・Pixel座標の保持 |
| `entity/Vb6TabInfoEntity.java` | SSTabのタブ枚数、子コントロールの所属、TabPageの管理 |
| `entity/Vb6ConversionStateEntity.java` | 入力フォームの変換状態、警告、出力件数の管理 |

各クラス、手書きメソッド、フィールドに日本語のJavadocを記載しています。生成アクセサも文書化するため、先に`delombok`で通常のJavaソースへ展開し、JDKの`javadoc`コマンドでHTMLリファレンスを生成します。`-private`を指定することで、内部処理に使うprivateメソッドもドキュメントに含めます。

```bash
java -jar lib/lombok-1.18.48.jar delombok src -d build/delombok --encoding UTF-8
javadoc -private -classpath lib/lombok-1.18.48.jar -Xdoclint:all,-missing -encoding UTF-8 -charset UTF-8 -docencoding UTF-8 \
  -d docs/api build/delombok/*.java build/delombok/entity/*.java
```

生成物の`docs/api/`はGitの管理対象から除外しています。
`-Xdoclint:all,-missing`は生成メソッドの不足タグの警告だけを除外し、構文や参照先等の検査を維持します。手書きソースのJavadocはCheckstyleでも検査します。

## Javaで変換する

引数を省略すると、**実行時の作業フォルダ**を基準に`sample/input/`直下のすべての`.frm`ファイルを探し、`sample/output/`へ変換します。
拡張子は大文字小文字を区別せず、他のファイルやサブフォルダは読み飛ばします。EclipseではRun Configurationsの**Working directory**をプロジェクトフォルダに設定してください。

```bash
java -cp bin Vb6WinFormsConverter
```

`sample/input/`がない場合、対象の`.frm`がない場合は、理由と**HowTo**を標準エラーへ表示し、終了コード2で終了します。
引数なしで使うには、手元のVB6フォームを`sample/input/`直下に配置してください。公開リポジトリの`sample/output/`には生成済みVB.NETのサンプルがあり、`sample/input/`に元の`.frm`は含みません。
複数のフォームがある場合は、`sample/output/combined/`に全画面をまとめ、`VB6Migrated_Migrated.vbproj`と`VB6Migrated_Migrated.slnx`を一組作成します。新規の起動コードではファイル名順の先頭を起動画面とし、同名のフォームには連番を付けます。1件の場合は`sample/output/`直下に出力します。
入力ファイル・出力先・DPIを指定する場合は、従来どおり入力・出力・任意のDPIを引数で指定します。

Java 21 以上の実行環境で、src配下の全Javaファイルをコンパイルして実行します（ビルド成果物はリポジトリには含めません）。

```bash
# 文字コードを明示してbinへコンパイル（Termux / Ubuntuでも利用可能）
bash build.sh

# コンパイル後に実行
java -Dfile.encoding=UTF-8 -cp bin Vb6WinFormsConverter ReportWindowCoorsDummy.frm output
```

ビルドスクリプトを使わず直接コンパイルする場合は、必ず `-encoding UTF-8` を指定してください。

```bash
mkdir -p bin
javac -encoding UTF-8 --release 21 -cp lib/lombok-1.18.48.jar \
  -processorpath lib/lombok-1.18.48.jar -d bin src/*.java src/entity/*.java
```

**重要:** このJavaソースには日本語のコメントや文字列が含まれます。環境によって `javac` の既定文字コードが `US-ASCII` になり、`unmappable character for encoding US-ASCII` が発生します。ソースを削除・変更せず、上記のように `-encoding UTF-8` を指定してください。

実行時にメッセージの日本語が `???` になる場合は、Ubuntuの端末でUTF-8ロケールを有効にしてください。

```bash
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
```


オプションでDPIを指定できます。例：

```bash
java -Dfile.encoding=UTF-8 -cp bin Vb6WinFormsConverter ReportWindowCoorsDummy.frm output 120
```

**推奨はまず96 DPI**です。フォームがTwips座標系（標準 `ScaleMode=1`）であることが前提です。変換された `.vb` ファイルはUTF-8で保存されます。VB6の元 `.frm` はWindows-31Jで読み込み、上書きしません。

## Visual Studioで画面を開く

WindowsでVisual Studio 2022 17.14以降の「.NET デスクトップ開発」ワークロードと、.NET 8のWindowsデスクトップ開発環境を用意します。
`.slnx`は[Visual Studio 17.14で正式サポート](https://devblogs.microsoft.com/dotnet/introducing-slnx-support-dotnet-cli/)されています。
コマンドラインで`.slnx`を扱う場合は.NET SDK 9.0.200以降を使用します。生成プロジェクトのターゲットは引き続き`net8.0-windows`です。
生成した`.slnx`と添付サンプルは.NET SDK 10.0.403の`dotnet sln <ファイル名.slnx> list`で読み込み・プロジェクト参照を確認しています。

1. 生成した`ReportWindow_Migrated.slnx`（添付例は`sample/output/ReportWindow_Migrated.slnx`）を**File → Open → Project/Solution**で開きます。`.vbproj`を直接開くこともできます。
2. `ReportWindow.vb` をフォームとして開き、デザイナー画面と5タブの配置を確認します。
3. `ReportWindow_migration_report.txt` の警告を検討し、必要な画像・COM相当機能・イベント処理を移植します。
4. `dotnet build` でビルドします（本資料作成環境ではVB.NETの実コンパイルまでは確認していません）。

## ラベルの表示サイズについて

VB6とWinFormsではフォントの描画領域や文字の余白が異なります。TwipsをPixelに単純換算しただけでは、例えば「郵便番号：」などの文字が切れる場合があります。

- **固定文字のLabel**（.frmでCaptionに文字が設定されているもの）は、既定でWinFormsの`AutoSize=True`を生成します。文字幅と高さが自動調整されます。
- **可変表示用のLabel**（Captionが空のもの）は`AutoSize=False`のままにし、元の固定サイズを保持します。
- VB6側で`AutoSize`が明示されている場合、その設定を優先します。折り返し表示（`WordWrap`）のラベルは既定では固定サイズを維持します。
- 文字が広がる分、隣のコントロールと重なる可能性はあります。特に日本語の長いラベルと狭い入力欄はVisual Studioで目視確認してください。

## 座標変換仕様

- 標準Twipsの場合：`pixels = round(twips × dpi ÷ 1440)`、96DPIでは `pixels = round(twips ÷ 15)`。
- `TabDlg.SSTab` → `TabControl`、`TabCaption(n)` → `TabPage.Text`。
- `Tab(n).Control(i)` からタブ所属を取得。SSTabの非選択タブ内の `Left` が約 `-75000` Twipsずれる場合に `+75000` 補正。
- SSTab直下のコントロールの `Top` から `TabHeight` を控除して `TabPage` の相対座標へ移行。見た目は手作業で最終確認が必要です。
- `VB.Frame` → `GroupBox`。フレーム内の子コントロール座標はフレーム相対で保持します。
- 標準のLabel、TextBox、Button、CheckBox、ComboBox、ListBox、PictureBox(Panel)、Image(PictureBox)、ListView、ProgressBar、WebBrowserなどにマッピングします。
- `Index` があるVB6コントロール配列は `CalenderButton(7)` → `CalenderButton_7` のように個別のフィールドへ変換します。
- 自動スケーリングは `AutoScaleMode.None` とし、元の座標値を再拡大しない設計です。

## 添付サンプルでの結果（96DPI）

- VB6の`Begin`コントロール宣言：フォームを含む **111件**。
- タブ：**5個**、SSTabの非選択座標補正：**34件**。
- SSTabの `Tab(n).Control` に載っていない所属不明の直接子コントロール：**10件**を推定配置（レポートで確認）。
- `SysInfo1`（ActiveX）：**1件**未移行（GUIコントロールには直接置換できません）。
- `ReportWindow.frx` は提供されていないため、画像やFRXに格納されたコンボボックス項目などの復元は未対応。
- VB6の実行コード、COM機能、イベント処理、制御配列の動作は対象外です。

## 注意

生成したWinFormsは **画面配置の初期移行結果**です。元のVB6フォームと完全な見た目・動作一致を保証しません。フォント、DPI、タブ表示高さ、ブラウザコントロール、`ImageList`の画像、レポート出力などの動作はWindows上で検証してください。

Windows Forms の `WebBrowser` は旧式のブラウザコンポーネントなので、移行後のWebページ表示に問題があれば別途WebView2などへ置き換える必要があります。

## リポジトリのファイル

- `src/Vb6WinFormsConverter.java`：変換処理本体・VB.NETコード生成
- `src/entity/Vb6FormNodeEntity.java`：VB6コントロールと座標・親子関係の保持
- `src/entity/Vb6TabInfoEntity.java`：SSTabのタブ所属とTabPageの管理
- `src/entity/Vb6ConversionStateEntity.java`：フォーム単位の変換情報・警告と件数の管理
- `sample/input/`: 変換したいVB6の`.frm`・必要に応じて`.frx`を配置する入力フォルダ
- `sample/output/`: 添付されたダミーフォームから生成した .NET 8 WinForms のサンプルとレポート。引数なし実行時の出力先
- 元の `.frm` や `.frx` は公開リポジトリに含めません。各自のローカルファイルを指定してください。
- 生成した `Designer.vb` は Windows 上でのビルド・デザイナー表示を確認してから本番利用してください。
