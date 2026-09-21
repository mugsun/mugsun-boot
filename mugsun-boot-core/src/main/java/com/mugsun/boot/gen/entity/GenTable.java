package com.mugsun.boot.gen.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

/**
 * 代码生成 - 表级配置。
 */
@Table("gen_table")
public class GenTable extends BaseEntity {

	/** 表名 */
	private String tableName;
	/** 表注释 */
	private String tableComment;
	/** 实体类名（如 BizCustomer） */
	private String entityName;
	/** 模块名（如 system，决定包与路由前缀） */
	private String moduleName;
	/** 业务名（如 customer，决定路径/权限码） */
	private String businessName;
	/** 功能名（如 客户，用于菜单/注释） */
	private String functionName;
	/** 作者 */
	private String functionAuthor;
	/** 基础包名 */
	private String basePackage;
	/** 表前缀（生成实体名时剥离） */
	private String tablePrefix;
	/** 生成方式：zip / disk */
	private String genType;
	/** 上级菜单 id（生成菜单 SQL 用） */
	private Long parentMenuId;
	/** 预留选项（JSON，主子表/树表等扩展） */
	private String options;
	/** 模板类别：crud（单表）/ tree（树表）/ master（主子表一对多） */
	private String tplCategory;
	/** 树表父级字段列名（如 parent_id） */
	private String treeParentField;
	/** 主子表：子表名 */
	private String subTableName;
	/** 主子表：子表中指向主表的外键列 */
	private String subJoinField;

	public String getTableName() {
		return tableName;
	}

	public void setTableName(String tableName) {
		this.tableName = tableName;
	}

	public String getTableComment() {
		return tableComment;
	}

	public void setTableComment(String tableComment) {
		this.tableComment = tableComment;
	}

	public String getEntityName() {
		return entityName;
	}

	public void setEntityName(String entityName) {
		this.entityName = entityName;
	}

	public String getModuleName() {
		return moduleName;
	}

	public void setModuleName(String moduleName) {
		this.moduleName = moduleName;
	}

	public String getBusinessName() {
		return businessName;
	}

	public void setBusinessName(String businessName) {
		this.businessName = businessName;
	}

	public String getFunctionName() {
		return functionName;
	}

	public void setFunctionName(String functionName) {
		this.functionName = functionName;
	}

	public String getFunctionAuthor() {
		return functionAuthor;
	}

	public void setFunctionAuthor(String functionAuthor) {
		this.functionAuthor = functionAuthor;
	}

	public String getBasePackage() {
		return basePackage;
	}

	public void setBasePackage(String basePackage) {
		this.basePackage = basePackage;
	}

	public String getTablePrefix() {
		return tablePrefix;
	}

	public void setTablePrefix(String tablePrefix) {
		this.tablePrefix = tablePrefix;
	}

	public String getGenType() {
		return genType;
	}

	public void setGenType(String genType) {
		this.genType = genType;
	}

	public Long getParentMenuId() {
		return parentMenuId;
	}

	public void setParentMenuId(Long parentMenuId) {
		this.parentMenuId = parentMenuId;
	}

	public String getOptions() {
		return options;
	}

	public void setOptions(String options) {
		this.options = options;
	}

	public String getTplCategory() {
		return tplCategory;
	}

	public void setTplCategory(String tplCategory) {
		this.tplCategory = tplCategory;
	}

	public String getTreeParentField() {
		return treeParentField;
	}

	public void setTreeParentField(String treeParentField) {
		this.treeParentField = treeParentField;
	}

	public String getSubTableName() {
		return subTableName;
	}

	public void setSubTableName(String subTableName) {
		this.subTableName = subTableName;
	}

	public String getSubJoinField() {
		return subJoinField;
	}

	public void setSubJoinField(String subJoinField) {
		this.subJoinField = subJoinField;
	}
}
