# VB6 `.frm` → VB.NET WinForms Designer 変換ツール（Java 17）

添付サンプル `ReportWindowCoorsDummy.frm` を対象に作成した、外部ライブラリ不要のコンソールツールです。**画面レイアウトの移行支援**用であり、VB6アプリケーションを丸ごとVB.NETへ移行するものではありません。

## 生成物

- `ReportWindow.Designer.vb`：WinForms フォームおよび標準コントロールのレイアウト。
- `ReportWindow.vb`：`InitializeComponent()` を呼ぶフォームのコードビハインド（既存ファイルは上書きしません）。
- `Program.vb`：アプリケーションのエントリポイント（既存ファイルは上書きしません）。
- `ReportWindow_Migrated.vbproj`：.NET 8 Windows Forms のVisual Studio用プロジェクト（既存ファイルは上書きしません）。
- `ReportWindow_coordinate_audit.csv`：元のTwips座標、変換後ピクセル座標、タブ所属の確認用CSV。
- `ReportWindow_migration_report.txt`：未移行項目、タブ所属の推定、コントロール配列の変更など。

## Javaで変換する

Java 17 以上の実行環境で、ソースをコンパイルして実行します（ビルド成果物はリポジトリには含めません）。

```bash
# 文字コードを明示してコンパイル（Termux / Ubuntuでも利用可能）
bash build.sh

# コンパイル後に実行
java Vb6WinFormsConverter ReportWindowCoorsDummy.frm output
```

ビルドスクリプトを使わず直接コンパイルする場合は、必ず `-encoding UTF-8` を指定してください。

```bash
javac -encoding UTF-8 Vb6WinFormsConverter.java
```

**重要:** このJavaソースには日本語のコメントや文字列が含まれます。環境によって `javac` の既定文字コードが `US-ASCII` になり、`unmappable character for encoding US-ASCII` が発生します。ソースを削除・変更せず、上記のように `-encoding UTF-8` を指定してください。

実行時にメッセージの日本語が `???` になる場合は、Ubuntuの端末でUTF-8ロケールを有効にしてください。

```bash
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
```


オプションでDPIを指定できます。例：

```bash
java Vb6WinFormsConverter ReportWindowCoorsDummy.frm output 120
```

**推奨はまず96 DPI**です。フォームがTwips座標系（標準 `ScaleMode=1`）であることが前提です。変換された `.vb` ファイルはUTF-8で保存されます。VB6の元 `.frm` はWindows-31Jで読み込み、上書きしません。

## Visual Studioで画面を開く

Windowsで .NET 8 SDK と Visual Studio 2022 の「.NET デスクトップ開発」ワークロードを用意します。

1. `sample/ReportWindow_Migrated.vbproj` を開く、または同等のVB.NET Windows Formsプロジェクトへ `.vb` と `.Designer.vb` を追加します。
2. `ReportWindow.vb` をフォームとして開き、デザイナー画面と5タブの配置を確認します。
3. `ReportWindow_migration_report.txt` の警告を検討し、必要な画像・COM相当機能・イベント処理を移植します。
4. `dotnet build` でビルドします（本資料作成環境ではVB.NETの実コンパイルまでは確認していません）。

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

- `Vb6WinFormsConverter.java`: Java 17 ソースコード
- `sample/`: 添付されたダミーフォームから生成した .NET 8 WinForms のサンプルとレポート
- 元の `.frm` や `.frx` は公開リポジトリに含めません。各自のローカルファイルを指定してください。
- 生成した `Designer.vb` は Windows 上でのビルド・デザイナー表示を確認してから本番利用してください。
