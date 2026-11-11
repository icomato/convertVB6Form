package entity;

import lombok.Data;
import lombok.ToString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * VB6のSSTabからTabControl/TabPageへの変換に必要な情報を保持する。
 *
 * <p>タブ数と見出しの高さ、Tab(n).Control(m)で指定された明示所属、
 * 生成するTabPageを管理する。
 * 所属推定は変換本体が行う。
 *
 * @author convertVB6Form contributors
 * @version 1.0 (Javaコーディング規約対応)
 * @since 1.0
 */
@Data
@ToString(onlyExplicitlyIncluded = true)
public final class Vb6TabInfoEntity {
	/** SSTabコントロール本体。 */
	private Vb6FormNodeEntity tab;

	/** タブの枚数。 */
	@ToString.Include
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
	 * 可変の解析ノードは値で比較せず、同じインスタンスかどうかで判定する。
	 * @param other 比較するオブジェクト
	 * @return 同じインスタンスの場合だけtrue
	 */
	@Override
	public boolean equals(Object other) {
		// 属性の値では比較せず、同じインスタンスである場合だけ真を返す。
		return this == other;
	}

	/**
	 * 属性変更や親子の循環参照に影響されない、従来どおりのハッシュ値を返す。
	 * @return インスタンスの識別用ハッシュ値
	 */
	@Override
	public int hashCode() {
		// 属性の変更や循環参照に影響されない、インスタンス単位のハッシュ値を返す。
		return System.identityHashCode(this);
	}
}
