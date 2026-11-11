import static constants.Vb6ConversionConstants.TWIPS_PER_INCH;

import entity.Vb6ConversionStateEntity;
import entity.Vb6FormNodeEntity;
import java.nio.file.Path;
import java.util.Arrays;

/**
* 元フォームの座標、変換後の配置、警告を確認するための監査資料を生成する。
*
* @author convertVB6Form contributors
* @version 1.0
* @since 1.0
*/
final class Vb6ReportGenerator {
	/** インスタンス化を防ぐ。 */
	private Vb6ReportGenerator() {
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
	* @param stateEntity 変換状態
	* @return 座標監査CSV全文
	*/
	static String toCoordinateAuditCsv(Vb6ConversionStateEntity stateEntity) {
		// 元座標・変換後座標・親子関係などの監査CSV全文を蓄積する。
		StringBuilder b = new StringBuilder();
		Vb6Format.appendLine(b, "VB6Type,VB6Name,WinFormsType,WinFormsField,VB6Parent,WinFormsParent,TabIndex,LeftTwips,TopTwips,WidthTwips,HeightTwips,PixelX,PixelY,PixelWidth,PixelHeight,Output");

		// 各ノードの変換前後の座標と親子関係を、一件ずつ監査CSVに記録する。
		for (Vb6FormNodeEntity n : stateEntity.getAll()) {
			// 親ノードがない場合はCSVを空欄にし、ある場合は元の親の識別子を記録する。
			String parent = n.getParent() == null ? "" : n.getParent().getId();

			// 解決済みの親名があればそれを使い、なければ元の親のフィールド名、親もなければ空欄を使う。
			String resolved = n.getResolvedParent().isEmpty() ? (n.getParent() == null ? "" : n.getParent().getField()) : n.getResolvedParent();

			// 監査CSVの列順に揃えた、このコントロール1件分の値。
			String[] v = {
				// WinForms型が未対応でnullの場合は、文字列nullを出さずCSVの型名を空欄にする。
				n.getVbType(), n.getId(), n.getWinType() == null ? "" : n.getWinType(), n.getField(), parent, resolved,

				// タブ番号が負なら未所属として空欄にし、0以上ならその番号を記録する。
				n.getTabIndex() < 0 ? "" : Integer.toString(n.getTabIndex()),
				Integer.toString(n.getLeftTwips()), Integer.toString(n.getTopTwips()), Integer.toString(n.getWidthTwips()), Integer.toString(n.getHeightTwips()),
				Integer.toString(n.getPixelLeft()), Integer.toString(n.getPixelTop()), Integer.toString(n.getPixelWidth()), Integer.toString(n.getPixelHeight()),

				// 対応済みのコントロールはYES、未対応のコントロールはNOとして記録する。
				n.isSupported() ? "YES" : "NO"
			};
			Vb6Format.appendLine(b, String.join(",", Arrays.stream(v).map(Vb6ReportGenerator::toCsvField).toList()));
		}

		// すべてのノードの変換前後の座標と親子関係を記録した監査CSVを返す。
		return b.toString();
	}

	/**
	* 変換条件、補正・推定件数、警告、未移植コードをレポートに出力する。
	* @param stateEntity 変換状態
	* @param source 入力.frmパス
	* @return 移行レポート全文
	*/
	static String toMigrationReport(Vb6ConversionStateEntity stateEntity, Path source) {
		// 変換条件・警告・未移植コードを記載する移行レポート全文を蓄積する。
		StringBuilder b = new StringBuilder();
		Vb6Format.appendLine(b, "VB6 -> VB.NET WinForms 座標変換レポート");
		Vb6Format.appendLine(b, "入力: " + source.getFileName());
		Vb6Format.appendLine(b, "フォーム: " + stateEntity.getClassName());
		Vb6Format.appendLine(b, "DPI: " + stateEntity.getDpi() + " (1px = " + TWIPS_PER_INCH / stateEntity.getDpi() + " Twips)");
		Vb6Format.appendLine(b, "フォームを含む定義数: " + stateEntity.getAll().size());
		Vb6Format.appendLine(b, "SSTab非表示座標補正数: " + stateEntity.getRemappedTabCount());
		Vb6Format.appendLine(b, "タブ割り当て推定数: " + stateEntity.getUnknownTabCount());
		Vb6Format.appendLine(b, "警告数: " + stateEntity.getWarnings().size());
		Vb6Format.appendLine(b, "");
		Vb6Format.appendLine(b, "移行範囲: フォーム配置、標準コントロール、Frame親子構造、SSTab->TabControl、基本的な属性とフォント。");
		Vb6Format.appendLine(b, "制限: VB6実行コード・イベント・コントロール配列イベント・COMオブジェクトの動作は自動変換しません。");
		Vb6Format.appendLine(b, "制限: .frx は添付されていないため Picture/TabPicture/ItemData/ImageList画像を復元しません。");
		Vb6Format.appendLine(b, "制限: 元フォーム内の ScaleMode はTwips（標準）を前提としています。");
		Vb6Format.appendLine(b, "制限: SSTabのY座標はTabHeightを引き、負の小さい値は0に丸めます。手動で見た目を確認してください。");
		Vb6Format.appendLine(b, "制限: Form.AutoScaleMode=NoneのためDPI倍率・フォント環境で見た目がずれることがあります。");
		Vb6Format.appendLine(b, "注意: VB.NETの.Windows.Forms.Formの自動生成レイアウトとしてVisual Studioで開けますが、未サポート要素は警告を確認してください。");
		Vb6Format.appendLine(b, "");
		Vb6Format.appendLine(b, "=== 警告 ===");

		// 警告が一件もない場合は、空欄ではなく「なし」と明示する。
		if (stateEntity.getWarnings().isEmpty()) {
			Vb6Format.appendLine(b, "なし");
		}

		// 収集済みの警告を登録順に出力し、未対応事項や推定配置を確認できるようにする。
		for (String warn : stateEntity.getWarnings()) {
			Vb6Format.appendLine(b, warn);
		}
		Vb6Format.appendLine(b, "");
		Vb6Format.appendLine(b, "=== 元.frm内のコード（参考・未移植） ===");
		Vb6Format.appendLine(b, stateEntity.getVb6Code());

		// 変換条件・警告・参考用の元コードをまとめた移行レポートを返す。
		return b.toString();
	}
}
