import entity.Vb6ConversionStateEntity;
import entity.Vb6FormNodeEntity;
import entity.Vb6TabInfoEntity;

import java.util.HashSet;
import java.util.Set;

/**
* Lombok導入後も、循環参照と可変Entityの同一性が安全であることを確認する。
*
* @author convertVB6Form contributors
* @version 1.0
* @since 1.0
*/
public final class EntityBehaviorTest {

	/** インスタンス化を防ぐ。 */
	private EntityBehaviorTest() {
	}

	/**
	* 生成アクセサを使って親子関係を作り、参照・比較・表示の互換性を検査する。
	* @param args 使用しない
	*/
	public static void main(String[] args) {
		Vb6FormNodeEntity parent = new Vb6FormNodeEntity("control", "VB.Form", "Root", null);
		Vb6FormNodeEntity child = new Vb6FormNodeEntity("control", "VB.Label", "Label1", parent);
		parent.getChildren().add(child);
		child.getProps().put("Caption", "表示文字");
		assert child.getParent() == parent;
		assert "表示文字".equals(child.getProperty("Caption"));
		assert child.hasProperty("Caption");
		assert child.isSupported();
		child.setSupported(false);
		assert !child.isSupported();

		Vb6FormNodeEntity sameValues = new Vb6FormNodeEntity("control", "VB.Form", "Root", null);
		assert parent.equals(parent);
		assert !parent.equals(sameValues);
		assert !parent.equals(null);
		Set<Vb6FormNodeEntity> nodes = new HashSet<>();
		nodes.add(parent);
		nodes.add(sameValues);
		int originalHash = parent.hashCode();
		parent.setName("Renamed");
		assert parent.hashCode() == originalHash;
		assert nodes.size() == 2 && nodes.contains(parent);
		assert parent.toString().contains("Renamed");
		assert child.toString().contains("Label1");

		Vb6TabInfoEntity tab = new Vb6TabInfoEntity();
		tab.setTab(parent);
		tab.setCount(2);
		tab.getPages().add(child);
		assert tab.getCount() == 2 && tab.getTab() == parent;
		assert !tab.equals(new Vb6TabInfoEntity());
		assert tab.toString().contains("count=2");

		Vb6ConversionStateEntity state = new Vb6ConversionStateEntity();
		state.setForm(parent);
		state.setClassName("Sample");
		state.getTabs().put(parent, tab);
		state.incrementVisualCount();
		assert state.getVisualCount() == 1;
		assert state.getTabs().get(parent) == tab;
		assert !state.equals(new Vb6ConversionStateEntity());
		assert state.toString().contains("Sample");
		System.out.println("Entity behavior test passed (identity, mutation and cyclic references).");
	}
}
