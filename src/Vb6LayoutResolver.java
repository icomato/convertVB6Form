import static constants.Vb6ConversionConstants.DEFAULT_TAB_HEIGHT_TWIPS;
import static constants.Vb6ConversionConstants.HIDDEN_TAB_LEFT_THRESHOLD_TWIPS;
import static constants.Vb6ConversionConstants.SSTAB_HIDE_TWIPS;
import static constants.Vb6ConversionConstants.TAB_CONTROL;
import static constants.Vb6ConversionConstants.TAB_TOP_CLAMP_LIMIT_TWIPS;
import static constants.Vb6ConversionConstants.VB_SCALE_MODE_TWIPS;

import entity.Vb6ConversionStateEntity;
import entity.Vb6FormNodeEntity;
import entity.Vb6TabInfoEntity;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析したフォームのWinForms型、タブ所属、出力時の座標を解決する。
 *
 * @author convertVB6Form contributors
 * @version 1.0
 * @since 1.0
 */
final class Vb6LayoutResolver {
	/** インスタンス化を防ぐ。 */
	private Vb6LayoutResolver() {
	}

	/**
	 * コントロール名・WinForms型・座標・親子関係を解決する。
	 * SSTabでは明示的なTab(n).Controlと兄弟要素から所属を決め、
	 * 非表示タブ用の負座標とTabHeightを補正する。
	 * @param stateEntity 更新する解析・変換状態
	 */
	static void resolveLayout(Vb6ConversionStateEntity stateEntity) {
		// VB6のVB_Name（見つからなければフォーム名）を出力先のクラス名に採用。
		// 複数行モードで、行頭の「Attribute VB_Name = "空でない名前"」を部分検索する。
		// 等号前後の空白は任意。行頭の空白は許可せず、大文字小文字も区別する。
		// 例: Attribute VB_Name = "ReportWindow" → グループ1はReportWindow。
		// Attribute VB_Name = "" は名前が空のため一致しない。
		// フォームのクラス名に使用するVB_Name属性をVB6コードから抽出する。
		Matcher classMatch = Pattern.compile("(?m)^Attribute VB_Name\\s*=\\s*\"([^\"]+)\"")
		.matcher(stateEntity.getVb6Code());

		// VB_Nameが見つかればその値を、見つからなければフォームの要素名をクラス名に使う。
		stateEntity.setClassName(toSafeIdentifier(classMatch.find() ? classMatch.group(1) : stateEntity.getForm().getName()));

		// フォームの座標単位が前提と異なる場合は、暫定換算であることを警告する。
		if (stateEntity.getForm().hasProperty("ScaleMode") && Vb6Format.parseInteger(stateEntity.getForm().getProperty("ScaleMode"), VB_SCALE_MODE_TWIPS) != VB_SCALE_MODE_TWIPS) {
			stateEntity.addWarning("Form.ScaleMode がTwips以外です。座標は96DPIのTwipsとして暫定変換します");
		}

		// 各ノードの識別子・出力型・座標を決定し、変換可否と追加確認事項を記録する。
		for (Vb6FormNodeEntity n : stateEntity.getAll()) {
			// Indexがあれば名前に(番号)を付け、なければ元の名前だけをVB6識別子に使う。
			n.setId(n.getName() + (n.hasProperty("Index") ? "(" + Vb6Format.parseInteger(n.getProperty("Index"), 0) + ")" : ""));

			// Indexがあれば_番号を付け、なければ名前だけを安全で一意な出力フィールド名にする。
			n.setField(toUniqueFieldName(stateEntity, toSafeIdentifier(n.getName() + (n.hasProperty("Index")
			? "_" + Vb6Format.parseInteger(n.getProperty("Index"), 0) : ""))));
			n.setWinType(toWinFormsControlType(n.getVbType()));

			// 対応するWinForms型がないノードを出力対象から外し、件数と警告を記録する。
			if (n.getWinType() == null) {
				n.setSupported(false);
				stateEntity.incrementIgnoredCount();
				stateEntity.addWarning(n.getId() + " (" + n.getVbType() + ") はWinForms相当コントロールがなく省略しました");

			// フォーム本体を除き、対応型のあるコントロールを出力件数に数える。
			} else if (n != stateEntity.getForm()) {
				stateEntity.incrementVisualCount();
			}

			// コントロール配列の個別変換と、配列イベントが別途移植対象であることを知らせる。
			if (n.hasProperty("Index")) {
				stateEntity.addWarning(n.getId() + " はVB6のコントロール配列です。WinFormsでは " + n.getField() + " へ個別変換しました。配列イベント処理の移植は別途必要です");
			}

			// 位置・サイズの未指定値は0 Twips。フォームの幅・高さだけはClientWidth・ClientHeightも参照する。
			n.setLeftTwips(Vb6Format.parseInteger(n.getProperty("Left"), 0));
			n.setTopTwips(Vb6Format.parseInteger(n.getProperty("Top"), 0));
			n.setWidthTwips(Vb6Format.parseInteger(n.getProperty("Width"), Vb6Format.parseInteger(n.getProperty("ClientWidth"), 0)));
			n.setHeightTwips(Vb6Format.parseInteger(n.getProperty("Height"), Vb6Format.parseInteger(n.getProperty("ClientHeight"), 0)));
			n.setPixelLeft(Vb6Format.toPixels(n.getLeftTwips(), stateEntity.getDpi()));
			n.setPixelTop(Vb6Format.toPixels(n.getTopTwips(), stateEntity.getDpi()));
			n.setPixelWidth(Vb6Format.toPixels(n.getWidthTwips(), stateEntity.getDpi()));
			n.setPixelHeight(Vb6Format.toPixels(n.getHeightTwips(), stateEntity.getDpi()));

			// 参照表を更新した結果、同じVB6識別子が存在した場合は重複を警告する。
			if (n.getId() != null && stateEntity.getById().put(n.getId(), n) != null) {
				stateEntity.addWarning("VB6コントロール識別子が重複しています: " + n.getId());
			}

			// SSTabは後で子要素を適切なTabPageへ移すため、タブ数と見出し高を保持する。

			// SSTabのタブ数と見出し高さを保存し、TabPage生成と座標補正に備える。
			if ("TabDlg.SSTab".equals(n.getVbType())) {
				// SSTabのページ数・見出しの高さ・所属情報をまとめる管理オブジェクト。
				Vb6TabInfoEntity t = new Vb6TabInfoEntity();
				t.setTab(n);

				// タブ数は少なくとも1枚とし、未指定の場合も1枚を生成する。
				t.setCount(Math.max(1, Vb6Format.parseInteger(n.getProperty("Tabs"), 1)));
				t.setTabHeightTwips(Vb6Format.parseInteger(n.getProperty("TabHeight"), DEFAULT_TAB_HEIGHT_TWIPS));
				stateEntity.getTabs().put(n, t);
			}

			// 画像属性はFRX参照を含む可能性があるため、画像が未移行であることを知らせる。
			if (n.hasProperty("Picture")) {
				stateEntity.addWarning(n.getId() + " のPictureは .frx 参照の可能性があります。画像リソースは未移行です");
			}

			// リスト初期値のFRX依存を警告し、移行後のデータ確認を促す。
			if (n.hasProperty("ItemData") || n.hasProperty("List")) {
				stateEntity.addWarning(n.getId() + " のリスト初期値はFRXを参照する場合があります。実データは未移行です");
			}

			// コントロールの座標単位がTwips以外なら、子の配置の手動確認を促す。
			if (n.hasProperty("ScaleMode") && Vb6Format.parseInteger(n.getProperty("ScaleMode"), VB_SCALE_MODE_TWIPS) != VB_SCALE_MODE_TWIPS) {
				stateEntity.addWarning(n.getId() + " のScaleModeはTwipsではありません。子コントロール座標の手動検証が必要です");
			}
		}

		// 各SSTabの明示的な所属情報を読み込み、必要なTabPageを準備する。
		for (Vb6TabInfoEntity ti : stateEntity.getTabs().values()) {

			// SSTabの属性から、子コントロールのタブ所属を表す定義を探す。
			for (Map.Entry<String, String> e : ti.getTab().getProps().entrySet()) {
				// 属性名がタブ所属の定義か調べ、タブ番号を取り出す照合器。
				Matcher match = TAB_CONTROL.matcher(e.getKey());

				// Tab(n).Control(m)の定義を、子識別子からタブ番号を引く対応表に変換する。
				if (match.matches()) {
					// TAB_CONTROLのグループ1は0始まりのタブ番号。グループ2の並び順は所属判定に使わない。
					int tabIdx = Integer.parseInt(match.group(1));

					// 明示所属を引くキーとなる元のコントロール識別子。
					String original = Vb6Format.parseVbString(e.getValue());
					ti.getAssignments().put(original, tabIdx);
				}
			}

			// 宣言されたタブ枚数に合わせて、出力先のTabPageノードを作成する。
			for (int i = 0; i < ti.getCount(); i++) {
				// 後でフィールドを生成し、子コントロールを追加するタブページのノード。
				Vb6FormNodeEntity page = new Vb6FormNodeEntity("generated", "WinForms.TabPage", ti.getTab().getName() + "_TabPage" + i, ti.getTab());
				page.setField(toUniqueFieldName(stateEntity, toSafeIdentifier(page.getName())));
				page.setWinType("System.Windows.Forms.TabPage");
				page.setTabIndex(i);
				page.setId(page.getName());
				ti.getPages().add(page);
			}

			// タブ見出し画像は復元できないため、移行対象外であることを記録する。
			if (ti.getTab().hasProperty("TabPicture(0)")) {
				stateEntity.addWarning(ti.getTab().getId() + " のTabPictureは移行しません（.frx画像未添付）");
			}
		}

		// SSTab直下のコントロールは親をTabPageへ変更してから座標を補正する。
		// Frame等の内側では元の親子関係と相対座標を維持する。

		// 各ノードの出力先の親を確定し、SSTab直下の座標だけを補正する。
		for (Vb6FormNodeEntity n : stateEntity.getAll()) {

			// SSTab直下の子を実際のTabPageに割り当て、タブ内の相対座標に直す。
			if (n.getParent() != null && stateEntity.getTabs().containsKey(n.getParent())) {
				// 直近の親SSTabに対応する明示所属と生成済みページの情報。
				Vb6TabInfoEntity ti = stateEntity.getTabs().get(n.getParent());

				// この子を割り当てるタブ番号。明示指定がなければ後で推定する。
				Integer tab = ti.getAssignments().get(n.getId());

				// 所属を推定した場合に、警告と集計へ反映するためのフラグ。
				boolean inferred = false;

				// 明示されたタブ所属がない場合、同名コントロール配列から推定する。
				// それでも不明なら符号から暫定配置して警告に記録する。

				// 所属の明示がない子は、同名の配列要素からタブを推定する。
				if (tab == null) {
					// 同名の配列要素が明示的に所属するタブ番号の候補集合。
					Set<Integer> siblings = new LinkedHashSet<>();

					// 同じSSTabの子を調べ、同名コントロールの既知の所属を収集する。
					for (Vb6FormNodeEntity other : n.getParent().getChildren()) {

						// 自分自身と別名のコントロールは、所属を推定する根拠から除外する。
						if (other == n || !other.getName().equals(n.getName())) {
							continue;
						}

						// 同名の配列要素に明示されたタブ番号。推定の根拠に使う。
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
						// Leftが非負なら先頭タブ、負なら存在する範囲で2枚目のタブに推定配置する。
						tab = n.getLeftTwips() >= 0 ? 0 : Math.min(1, ti.getCount() - 1);
					}
					inferred = true;
				}

				// 範囲外のタブ番号は警告したうえでTab0に補正し、ページ参照エラーを防ぐ。
				if (tab < 0 || tab >= ti.getCount()) {
					stateEntity.addWarning(n.getId() + " のタブ番号が不正です: " + tab + "。Tab0に置き換えます");
					tab = 0;
				}
				n.setTabIndex(tab);

				// 推定配置の件数と根拠不足を記録し、移行後に確認できるようにする。
				if (inferred) {
					stateEntity.incrementUnknownTabCount();
					stateEntity.addWarning(n.getId() + " はTab(n).Control一覧にありません。タブ" + tab + " に推定配置しました（要確認）");
				}

				// SSTabで非表示のタブを左へ追い出した大きな負座標だけを補正する。
				// 通常の小さな負座標は意図的な位置として扱い、補正しない。
				// 退避座標の境界より左なら補正量を加え、それ以外は元のLeftを維持する。
				long correctedLeft = n.getLeftTwips() < HIDDEN_TAB_LEFT_THRESHOLD_TWIPS
				? (long) n.getLeftTwips() + SSTAB_HIDE_TWIPS : n.getLeftTwips();

				// 非表示タブの退避座標を補正したノードだけを、補正件数に加算する。
				if (correctedLeft != n.getLeftTwips()) {
					stateEntity.incrementRemappedTabCount();
				}
				n.setPixelLeft(Vb6Format.toPixels(correctedLeft, stateEntity.getDpi()));

				// VB6ではタブ見出し基準、WinForms TabPageでは内容領域基準なので、
				// TabHeightを引いてY座標を揃える。
				long correctedTop = (long) n.getTopTwips() - ti.getTabHeightTwips();

				// 見出し付近の小さな負の位置を原点へ丸め、TabPage内でのはみ出しを抑える。
				if (correctedTop < 0 && correctedTop > TAB_TOP_CLAMP_LIMIT_TWIPS) {
					correctedTop = 0;
				}
				n.setPixelTop(Vb6Format.toPixels(correctedTop, stateEntity.getDpi()));
				n.setResolvedParent(ti.getPages().get(tab).getField());

			// SSTab以外の子は、元の親をそのまま出力先の親として保持する。
			} else if (n.getParent() != null) {
				n.setResolvedParent(n.getParent().getField());
			}
		}
	}

	/**
	 * フィールド名を大文字小文字を区別せず一意にする。
	 * @param stateEntity 使用済み名前の管理状態
	 * @param requested フィールド名候補
	 * @return 必要に応じて連番を付けたフィールド名
	 */
	private static String toUniqueFieldName(Vb6ConversionStateEntity stateEntity, String requested) {
		// 使用済みの名前と照合し、重複があれば連番を付け直す候補名。
		String name = requested;

		// 元の候補名を最初に試すため、重複時の連番は2から開始する。
		int i = 2;

		// 大文字小文字を区別しない名前の登録に成功するまで、連番付きの候補を試す。
		while (!stateEntity.getUsedFields().add(name.toLowerCase(Locale.ROOT))) {
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
		// Unicodeの文字（\p{L}）・数字（\p{N}）・アンダースコア以外の各文字に一致する。
		// 一致した文字を1文字ずつ「_」に置換する。例: 「受注-Form 1」→「受注_Form_1」。
		String result = name.replaceAll("[^\\p{L}\\p{N}_]", "_");

		// 名前が空になった場合も、有効な識別子の候補を用意する。
		if (result.isEmpty()) {
			result = "Unnamed";
		}

		// 数字で始まる名前に接頭辞を付け、VB.NETの識別子として使える形にする。
		if (Character.isDigit(result.charAt(0))) {
			result = "ctl_" + result;
		}

		// 生成する識別子との衝突を避けるために照合するVB.NET予約語。
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
}
