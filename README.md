# convertVB6Form

VB6 の `.frm` ファイルからコントロールの座標を解析し、VB.NET Windows Forms (.NET Framework 4.8) のプロジェクトを生成するツールです。

## 現在の版

`ExportFrmPropertyCs_modified.zip` に C# ソースコード一式を収録しています。ZIP を展開して Visual Studio で開いてください。

- 指定フォルダ内の `.frm` を一括解析
- 座標一覧に相対座標・絶対座標を両方出力
- `.Designer.vb`・`.vb`・`.vbproj` の生成
- 未対応要素はログに記録

## 実行例

```cmd
ExportFrmPropertyCs.exe "C:\VB6Source" "C:\VBNetConverted"
```

## 注意

.NET Frameworkでのビルド・実際のVB6フォームを使った検証は未実施です。`.frx` の画像などのバイナリデータと `.bas`・`.cls` の処理コードは自動移行しません。
