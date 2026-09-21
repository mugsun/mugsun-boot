package #(entityPkg);

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;
#if(isTree)
import com.mugsun.core.tool.tree.INode;
import com.mybatisflex.annotation.Column;
import java.util.List;
#end
#if(isMaster)
import com.mybatisflex.annotation.Column;
import java.util.List;
#end
#for(imp : imports)
import #(imp);
#end

/**
 * #(functionName)
 *
 * @author #(author)
 */
@Table("#(tableName)")
public class #(entityName) #(classDecl) {
#for(col : columns)

	/** #(col.comment) */
	private #(col.javaType) #(col.javaField);
#end
#if(isTree)

	/** 子节点（树构建，非数据库列） */
	@Column(ignore = true)
	private List<#(entityName)> children;

	/** 是否有子节点（懒加载标记，非数据库列，/tree 按分组统计回填） */
	@Column(ignore = true)
	private boolean hasChildren;
#end
#if(isMaster)

	/** 子表数据（级联，非数据库列） */
	@Column(ignore = true)
	private List<#(childEntityName)> #(subListField);
#end
#for(col : columns)

	public #(col.javaType) get#(col.capField)() {
		return #(col.javaField);
	}

	public void set#(col.capField)(#(col.javaType) #(col.javaField)) {
		this.#(col.javaField) = #(col.javaField);
	}
#end
#if(isTree)

	@Override
	public List<#(entityName)> getChildren() {
		return children;
	}

	@Override
	public void setChildren(List<#(entityName)> children) {
		this.children = children;
	}

	public boolean isHasChildren() {
		return hasChildren;
	}

	public void setHasChildren(boolean hasChildren) {
		this.hasChildren = hasChildren;
	}
#end
#if(isMaster)

	public List<#(childEntityName)> get#(subListCap)() {
		return #(subListField);
	}

	public void set#(subListCap)(List<#(childEntityName)> #(subListField)) {
		this.#(subListField) = #(subListField);
	}
#end
}
