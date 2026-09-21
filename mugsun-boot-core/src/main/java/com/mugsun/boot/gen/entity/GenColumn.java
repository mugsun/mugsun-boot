package com.mugsun.boot.gen.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 代码生成 - 字段级配置。
 */
@Table("gen_column")
public class GenColumn extends BaseEntity {

	/** 所属表配置 id */
	private Long tableId;
	/** 列名 */
	private String columnName;
	/** 旧列名（改名追踪：增量同步据此走 RENAME 保数据） */
	private String columnNameOld;
	/** 列注释 */
	private String columnComment;
	/** 数据库列类型 */
	private String columnType;
	/** Java 类型（String/Integer/Long/LocalDateTime/BigDecimal/Boolean） */
	private String javaType;
	/** Java 字段名（驼峰） */
	private String javaField;
	/** 是否主键 */
	private Integer isPk;
	/** 是否自增 */
	private Integer isIncrement;
	/** 是否必填 */
	private Integer isRequired;
	/** 是否为新增字段 */
	private Integer isInsert;
	/** 是否为编辑字段 */
	private Integer isEdit;
	/** 是否为列表字段 */
	private Integer isList;
	/** 是否为查询字段 */
	private Integer isQuery;
	/** 查询方式：EQ/LIKE/BETWEEN/NE/GT/LT */
	private String queryType;
	/** 显示控件：input/textarea/select/datetime/number/switch/radio/checkbox */
	private String htmlType;
	/** 字典类型 */
	private String dictType;
	/** 排序 */
	private Integer sort;

	public Long getTableId() {
		return tableId;
	}

	public void setTableId(Long tableId) {
		this.tableId = tableId;
	}

	public String getColumnName() {
		return columnName;
	}

	public void setColumnName(String columnName) {
		this.columnName = columnName;
	}

	public String getColumnNameOld() {
		return columnNameOld;
	}

	public void setColumnNameOld(String columnNameOld) {
		this.columnNameOld = columnNameOld;
	}

	public String getColumnComment() {
		return columnComment;
	}

	public void setColumnComment(String columnComment) {
		this.columnComment = columnComment;
	}

	public String getColumnType() {
		return columnType;
	}

	public void setColumnType(String columnType) {
		this.columnType = columnType;
	}

	public String getJavaType() {
		return javaType;
	}

	public void setJavaType(String javaType) {
		this.javaType = javaType;
	}

	public String getJavaField() {
		return javaField;
	}

	public void setJavaField(String javaField) {
		this.javaField = javaField;
	}

	public Integer getIsPk() {
		return isPk;
	}

	public void setIsPk(Integer isPk) {
		this.isPk = isPk;
	}

	public Integer getIsIncrement() {
		return isIncrement;
	}

	public void setIsIncrement(Integer isIncrement) {
		this.isIncrement = isIncrement;
	}

	public Integer getIsRequired() {
		return isRequired;
	}

	public void setIsRequired(Integer isRequired) {
		this.isRequired = isRequired;
	}

	public Integer getIsInsert() {
		return isInsert;
	}

	public void setIsInsert(Integer isInsert) {
		this.isInsert = isInsert;
	}

	public Integer getIsEdit() {
		return isEdit;
	}

	public void setIsEdit(Integer isEdit) {
		this.isEdit = isEdit;
	}

	public Integer getIsList() {
		return isList;
	}

	public void setIsList(Integer isList) {
		this.isList = isList;
	}

	public Integer getIsQuery() {
		return isQuery;
	}

	public void setIsQuery(Integer isQuery) {
		this.isQuery = isQuery;
	}

	public String getQueryType() {
		return queryType;
	}

	public void setQueryType(String queryType) {
		this.queryType = queryType;
	}

	public String getHtmlType() {
		return htmlType;
	}

	public void setHtmlType(String htmlType) {
		this.htmlType = htmlType;
	}

	public String getDictType() {
		return dictType;
	}

	public void setDictType(String dictType) {
		this.dictType = dictType;
	}

	public Integer getSort() {
		return sort;
	}

	public void setSort(Integer sort) {
		this.sort = sort;
	}
}
