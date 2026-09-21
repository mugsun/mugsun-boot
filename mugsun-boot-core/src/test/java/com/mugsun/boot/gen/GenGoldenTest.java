package com.mugsun.boot.gen;

import com.mugsun.boot.gen.entity.GenColumn;
import com.mugsun.boot.gen.entity.GenTable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 代码生成 golden-file 快照回归：固定元数据 → 渲染全栈产物 → 比对已存快照，守模板改动不破产物。
 * 覆盖 crud（全控件）与 tree（树表 INode + /tree + 树前端）两类模板。
 * <p>模板有意变更后执行 {@code mvn test -Dtest=GenGoldenTest -Dgen.updateGolden=true} 重写快照并提交。
 */
class GenGoldenTest {

	private static final Path GOLDEN_DIR = Path.of("src/test/resources/generator/golden");
	private static final boolean UPDATE = Boolean.getBoolean("gen.updateGolden");

	@Test
	void crudSnapshotMatchesGolden() throws IOException {
		snapshot(crudTable(), crudColumns(), List.of(), "");
	}

	@Test
	void treeSnapshotMatchesGolden() throws IOException {
		snapshot(treeTable(), treeColumns(), List.of(), "tree-");
	}

	@Test
	void masterSnapshotMatchesGolden() throws IOException {
		snapshot(masterTable(), masterColumns(), masterChildColumns(), "master-");
	}

	private void snapshot(GenTable table, List<GenColumn> columns, List<GenColumn> childColumns, String prefix) throws IOException {
		List<GenRenderService.Product> products = new GenRenderService(null, null, null).generate(table, columns, childColumns);
		for (GenRenderService.Product p : products) {
			Path golden = GOLDEN_DIR.resolve(prefix + p.category() + ".txt");
			if (UPDATE) {
				Files.createDirectories(GOLDEN_DIR);
				Files.writeString(golden, p.content(), StandardCharsets.UTF_8);
			} else {
				assertTrue(Files.exists(golden), "缺少 golden 快照：" + golden);
				assertEquals(Files.readString(golden, StandardCharsets.UTF_8), p.content(),
					"产物与 golden 不一致（模板回归）：" + prefix + p.category());
			}
		}
	}

	// ---------- crud 固定元数据（覆盖全控件） ----------

	private GenTable crudTable() {
		return baseTable("gen_product", "Product", "product", "商品");
	}

	private List<GenColumn> crudColumns() {
		List<GenColumn> cols = new ArrayList<>();
		cols.add(col(1, "id", "商品ID", "int8", "Long", "id", 1, 0, 0, 0, "input", null));
		cols.add(col(2, "product_name", "商品名称", "varchar", "String", "productName", 0, 1, 1, 1, "input", null));
		cols.add(col(3, "price", "价格", "numeric", "BigDecimal", "price", 0, 1, 1, 0, "number", null));
		cols.add(col(4, "status", "状态", "int4", "Integer", "status", 0, 1, 1, 1, "select", "product_status"));
		cols.add(col(5, "remark", "备注", "varchar", "String", "remark", 0, 0, 1, 0, "textarea", null));
		cols.add(col(6, "online", "上架", "int4", "Integer", "online", 0, 1, 1, 0, "switch", null));
		cols.add(col(7, "publish_time", "发布时间", "timestamp", "LocalDateTime", "publishTime", 0, 1, 1, 0, "datetime", null));
		cols.add(col(8, "create_time", "创建时间", "timestamp", "LocalDateTime", "createTime", 0, 1, 0, 0, "datetime", null));
		cols.add(col(9, "is_deleted", "删除标记", "int4", "Integer", "isDeleted", 0, 0, 0, 0, "switch", null));
		return cols;
	}

	// ---------- tree 固定元数据（树表） ----------

	private GenTable treeTable() {
		GenTable t = baseTable("gen_category", "Category", "category", "分类");
		t.setTplCategory("tree");
		t.setTreeParentField("parent_id");
		return t;
	}

	private List<GenColumn> treeColumns() {
		List<GenColumn> cols = new ArrayList<>();
		cols.add(col(1, "id", "分类ID", "int8", "Long", "id", 1, 0, 0, 0, "input", null));
		cols.add(col(2, "parent_id", "父级", "int8", "Long", "parentId", 0, 1, 1, 0, "number", null));
		cols.add(col(3, "category_name", "分类名称", "varchar", "String", "categoryName", 0, 1, 1, 1, "input", null));
		cols.add(col(4, "sort", "排序", "int4", "Integer", "sort", 0, 1, 1, 0, "number", null));
		cols.add(col(5, "create_time", "创建时间", "timestamp", "LocalDateTime", "createTime", 0, 1, 0, 0, "datetime", null));
		cols.add(col(6, "is_deleted", "删除标记", "int4", "Integer", "isDeleted", 0, 0, 0, 0, "switch", null));
		return cols;
	}

	// ---------- master 固定元数据（主子表一对多） ----------

	private GenTable masterTable() {
		GenTable t = baseTable("gen_order", "Order", "order", "订单");
		t.setTplCategory("master");
		t.setSubTableName("gen_order_item");
		t.setSubJoinField("order_id");
		return t;
	}

	private List<GenColumn> masterColumns() {
		List<GenColumn> cols = new ArrayList<>();
		cols.add(col(1, "id", "订单ID", "int8", "Long", "id", 1, 0, 0, 0, "input", null));
		cols.add(col(2, "order_no", "订单号", "varchar", "String", "orderNo", 0, 1, 1, 1, "input", null));
		cols.add(col(3, "amount", "金额", "numeric", "BigDecimal", "amount", 0, 1, 1, 0, "number", null));
		cols.add(col(4, "create_time", "创建时间", "timestamp", "LocalDateTime", "createTime", 0, 1, 0, 0, "datetime", null));
		cols.add(col(5, "is_deleted", "删除标记", "int4", "Integer", "isDeleted", 0, 0, 0, 0, "switch", null));
		return cols;
	}

	private List<GenColumn> masterChildColumns() {
		List<GenColumn> cols = new ArrayList<>();
		cols.add(col(1, "id", "明细ID", "int8", "Long", "id", 1, 0, 0, 0, "input", null));
		cols.add(col(2, "order_id", "订单ID", "int8", "Long", "orderId", 0, 0, 1, 0, "number", null));
		cols.add(col(3, "product_name", "商品名称", "varchar", "String", "productName", 0, 1, 1, 0, "input", null));
		cols.add(col(4, "qty", "数量", "int4", "Integer", "qty", 0, 1, 1, 0, "number", null));
		cols.add(col(5, "create_time", "创建时间", "timestamp", "LocalDateTime", "createTime", 0, 1, 0, 0, "datetime", null));
		cols.add(col(6, "is_deleted", "删除标记", "int4", "Integer", "isDeleted", 0, 0, 0, 0, "switch", null));
		return cols;
	}

	private GenTable baseTable(String tableName, String entityName, String business, String functionName) {
		GenTable t = new GenTable();
		t.setId(1000000000000000001L);
		t.setTableName(tableName);
		t.setEntityName(entityName);
		t.setModuleName("demo");
		t.setBusinessName(business);
		t.setFunctionName(functionName);
		t.setFunctionAuthor("mugsun");
		t.setBasePackage("com.mugsun.boot");
		t.setTablePrefix("gen_");
		return t;
	}

	private GenColumn col(int sort, String name, String comment, String colType, String javaType, String field,
						  int isPk, int isList, int isEdit, int isQuery, String htmlType, String dict) {
		GenColumn c = new GenColumn();
		c.setColumnName(name);
		c.setColumnComment(comment);
		c.setColumnType(colType);
		c.setJavaType(javaType);
		c.setJavaField(field);
		c.setIsPk(isPk);
		c.setIsList(isList);
		c.setIsEdit(isEdit);
		c.setIsInsert(isEdit);
		c.setIsQuery(isQuery);
		c.setQueryType("String".equals(javaType) ? "LIKE" : "EQ");
		c.setHtmlType(htmlType);
		c.setDictType(dict);
		c.setSort(sort);
		return c;
	}
}
