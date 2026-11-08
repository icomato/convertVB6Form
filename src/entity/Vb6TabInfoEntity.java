package entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
* VB6のSSTabからTabControl/TabPageへの変換に必要な情報を保持する。
*
* <p>タブ数と見出しの高さ、Tab(n).Control(m)で指定された明示所属、生成するTabPageを管理する。所属推定は変換本体が行う。
*
* @author convertVB6Form contributors
* @version 1.0 (Javaコーディング規約対応)
* @since 1.0
*/
public final class Vb6TabInfoEntity {
	/** SSTabコントロール本体。 */
	private Vb6FormNodeEntity tab;
	/** タブの枚数。 */
	private int count;
	/** SSTab見出しの高さ（Twips）。 */
	private int tabHeightTwips;
	/** 元コントロール識別子と所属タブ番号の対応表。 */
	private final Map<String, Integer> assignments = new HashMap<>();
	/** 生成するTabPageのノード一覧。 */
	private final List<Vb6FormNodeEntity> pages = new ArrayList<>();

	/**
	* SSTabの所属情報と生成ページを保持する空の管理情報を作成する。
	*/
	public Vb6TabInfoEntity() {
	}

	/**
	* 対象のSSTabノードを取得する。
	* @return 対象のSSTabノード。コレクションの場合は変換処理から変更可能な参照
	*/
	public Vb6FormNodeEntity getTab() {
		return tab;
	}

	/**
	* 対象のSSTabノードを設定する。
	* @param value 新しい対象のSSTabノード
	*/
	public void setTab(Vb6FormNodeEntity value) {
		this.tab = value;
	}

	/**
	* SSTabが持つタブ枚数を取得する。
	* @return SSTabが持つタブ枚数。コレクションの場合は変換処理から変更可能な参照
	*/
	public int getCount() {
		return count;
	}

	/**
	* SSTabが持つタブ枚数を設定する。
	* @param value 新しいSSTabが持つタブ枚数
	*/
	public void setCount(int value) {
		this.count = value;
	}

	/**
	* タブ見出しの高さ（Twips）を取得する。
	* @return タブ見出しの高さ（Twips）。コレクションの場合は変換処理から変更可能な参照
	*/
	public int getTabHeightTwips() {
		return tabHeightTwips;
	}

	/**
	* タブ見出しの高さ（Twips）を設定する。
	* @param value 新しいタブ見出しの高さ（Twips）
	*/
	public void setTabHeightTwips(int value) {
		this.tabHeightTwips = value;
	}

	/**
	* コントロールから所属タブ番号への対応表を取得する。
	* @return コントロールから所属タブ番号への対応表。コレクションの場合は変換処理から変更可能な参照
	*/
	public Map<String, Integer> getAssignments() {
		return assignments;
	}

	/**
	* 生成するTabPageノードの一覧を取得する。
	* @return 生成するTabPageノードの一覧。コレクションの場合は変換処理から変更可能な参照
	*/
	public List<Vb6FormNodeEntity> getPages() {
		return pages;
	}
}
