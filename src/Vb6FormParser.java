import static constants.Vb6ConversionConstants.BEGIN;
import static constants.Vb6ConversionConstants.BEGIN_PROPERTY;
import static constants.Vb6ConversionConstants.PROPERTY;
import static constants.Vb6ConversionConstants.VB6_CHARSET;

import entity.Vb6ConversionStateEntity;
import entity.Vb6FormNodeEntity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.regex.Matcher;

/**
 * VB6の.frmからコントロール階層・属性・元コードを読み取る。
 *
 * @author convertVB6Form contributors
 * @version 1.0
 * @since 1.0
 */
final class Vb6FormParser {
	/** インスタンス化を防ぐ。 */
	private Vb6FormParser() {
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
	static Vb6ConversionStateEntity parseForm(Path source) throws IOException {
		// このフォームの解析ノードと元コードを保持し、レイアウト解決へ渡す状態。
		Vb6ConversionStateEntity stateEntity = new Vb6ConversionStateEntity();

		// フォーム定義とVB6コードを含む、Windows-31Jから復号した全文。
		String raw = Files.readString(source, VB6_CHARSET);

		// splitの-1は末尾の空行も保持する指定。元コードをレポートに残すため省略しない。
		// 改行はCRLF（\r\n）またはLF（\n）に一致し、単独のCRでは分割しない。
		// 例: "Begin\r\nEnd\n" → ["Begin", "End", ""]。最後の空要素も残す。
		String[] lines = raw.split("\\r?\\n", -1);

		// Beginでpush、EndでpopすることでVB6のネスト構造を復元する。
		Deque<Vb6FormNodeEntity> stack = new ArrayDeque<>();

		// VB6コードの開始行。見つからなければ行数を保持する。
		int codeStart = lines.length;

		// フォーム定義を先頭から読み、スタックでコントロール階層を復元する。
		for (int i = 0; i < lines.length; i++) {
			// フォーム定義の構文を判定するため、両端の空白を除いた現在行。
			String line = lines[i].trim();

			// ここから先はVB6イベント処理などのコード部なので、レイアウト解析を終了する。

			// VB6コード部の開始位置を保存し、画面定義の解析を終了する。
			if (line.startsWith("Attribute VB_Name")) {
				codeStart = i;
				break;
			}

			// Begin行からコントロールの型名と名前を抽出する照合器。
			Matcher begin = BEGIN.matcher(line);

			// BeginProperty行から複合属性名を抽出する照合器。
			Matcher nested = BEGIN_PROPERTY.matcher(line);

			// コントロールの開始宣言からノードを作成し、階層と一覧に登録する。
			if (begin.matches()) {
				// 新しいコントロールの所属先。スタックが空ならルートになる。
				Vb6FormNodeEntity parent = stack.peek();

				// BEGINのグループ1はVB6型名、グループ2はコントロール名。
				Vb6FormNodeEntity node = new Vb6FormNodeEntity("control", begin.group(1), begin.group(2), parent);

				// 現在の親コントロールに子を登録し、元の包含関係を保持する。
				if (parent != null) {
					parent.getChildren().add(node);

				// 最初のトップレベルノードを変換対象フォームとして採用する。
				} else if (stateEntity.getForm() == null) {
					stateEntity.setForm(node);
				} else {
					stateEntity.addWarning("複数のトップレベルフォームがあります: " + node.getName());
				}
				stack.push(node);
				stateEntity.getAll().add(node);

				// BeginProperty Fontなどは別コントロールではなく親コントロールの属性。

			// Fontなどの複合属性を親コントロールに属する属性ノードとして読み込む。
			} else if (nested.matches()) {
				// Fontなどの複合属性を登録する親コントロール。
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

				// End系の行で閉じた要素。開始側の種別と対応するか検証する。
				Vb6FormNodeEntity popped = stack.pop();

				// コントロールと複合属性で終了宣言の種類が違う場合は、対応不整合を通知する。
				if (line.equals("End") && !popped.getKind().equals("control") ||
				line.equals("EndProperty") && !popped.getKind().equals("property")) {
					throw new IOException("Begin/Endの対応が不正です (行 " + (i + 1) + ")");
				}

			// 開いている定義の内側だけをプロパティの読み込み対象とする。
			} else if (!stack.isEmpty()) {
				// 現在のノードに登録する属性名と値を抽出する照合器。
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
		if (stateEntity.getForm() == null || !"VB.Form".equals(stateEntity.getForm().getVbType())) {
			throw new IOException("VB.Formが見つかりません");
		}

		// レイアウト以降のVB6コードを、移行レポートに参考掲載するため保存する。
		if (codeStart < lines.length) {
			stateEntity.setVb6Code(String.join("\n", Arrays.copyOfRange(lines, codeStart, lines.length)));
		}

		// コントロール階層と元のVB6コードを含む解析結果を、後続の変換処理へ渡す。
		return stateEntity;
	}
}
