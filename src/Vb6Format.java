import static constants.Vb6ConversionConstants.TWIPS_PER_INCH;

/**
* 変換時の属性値の解釈と、生成するVB.NETリテラルや改行の整形を担う。
*
* @author convertVB6Form contributors
* @version 1.0
* @since 1.0
*/
final class Vb6Format {
	/** インスタンス化を防ぐ。 */
	private Vb6Format() {
	}

	/**
	* 数値プロパティを読み取り、末尾のVB6コメントを取り除く。
	* @param value 属性値。
	* 未設定ならnull
	* @param fallback 解析失敗時の既定値
	* @return 数値化した属性値
	*/
	static int parseInteger(String value, int fallback) {

		// 未設定の数値属性には、呼び出し元が指定した既定値を使う。
		if (value == null) {
			// 属性が未設定の場合は、呼び出し元が指定した既定値を返す。
			return fallback;
		}
		try {
			// 空白1文字以上と直後の「'」に一致し、VB6の末尾コメントを分離する。
			// 例: 「120 'Twips」→ ["120", "Twips"]。「120'Twips」は空白がなく分割しない。
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
	static int toPixels(long twips, double dpi) {
		// Twipsを指定DPIで換算し、最も近い整数ピクセル値を返す。
		return (int) Math.round(twips * dpi / TWIPS_PER_INCH);
	}

	/**
	* VB6文字列リテラルを取り出し、二重の引用符を元に戻す。
	* @param vbValue .frmの元の値
	* @return デコードした文字列。
	* nullの場合は空文字列
	*/
	static String parseVbString(String vbValue) {

		// 未設定の文字列属性を空文字列として扱い、後続の文字列処理を簡潔にする。
		if (vbValue == null) {
			// 未設定の文字列属性は、後続処理で扱える空文字列として返す。
			return "";
		}

		// 先頭の引用符や末尾のVB6コメントを判定するため、両端の空白を除いた属性値。
		String t = vbValue.trim();

		// 最大2分割の先頭を取り出し、末尾のVB6コメントを除いた値を返す。
		if (!t.startsWith("\"")) {
			// 空白1文字以上と「'」を区切りにし、引用符で囲まれていない値の末尾コメントを分離する。
			// 例: 「Enabled '説明」→ ["Enabled", "説明"]。「Enabled'説明」は分割しない。
			// 最大2分割の先頭を取り出し、末尾のVB6コメントを除いた値を返す。
			return t.split("\\s+'", 2)[0];
		}

		// 引用符のエスケープを復元した、VB6文字列属性の本文を蓄積する。
		StringBuilder b = new StringBuilder();

		// 開始引用符の次から読み、引用符のエスケープと終端を区別する。
		for (int i = 1; i < t.length(); i++) {
			// 引用符のエスケープか終端かを判定する現在の文字。
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
	static String toVbStringLiteral(String javaString) {
		// 引用符と改行をVB.NETで表現できる形に置き換えた文字列式を返す。
		return "\"" + javaString.replace("\"", "\"\"").replace("\r", "")
		.replace("\n", "\" & vbCrLf & \"") + "\"";
	}

	/**
	* 数値で記述されたVB6真偽値を判定する。
	* @param value VB6属性値
	* @return 非ゼロならtrue
	*/
	static boolean isVbTrue(String value) {
		// VB6の数値が0以外なら真とし、未設定や不正値は偽として返す。
		return parseInteger(value, 0) != 0;
	}

	/**
	* JavaのbooleanをVB.NETリテラルへ変換する。
	* @param value 真偽値
	* @return TrueまたはFalse
	*/
	static String toVbBooleanLiteral(boolean value) {
		// 値が真ならVB.NETのTrueを、偽ならFalseを返す。
		return value ? "True" : "False";
	}

	/**
	* 文字列バッファに1行をCRLF付きで追記する。
	* @param b 生成するVB.NETコードや監査資料の行を蓄積する文字列バッファ
	* @param s 追記する内容
	*/
	static void appendLine(StringBuilder b, String s) {
		b.append(s).append("\r\n");
	}
}
