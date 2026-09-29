package com.mugsun.boot.ai.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

@Table("ai_dataset_table")
public class AiDatasetTable extends BaseEntity {

	private Long datasetId;
	private Long datasourceId;
	private String tableName;
	private String tableAlias;
	private String tableComment;
	private String fieldsJson;
	private String relationsJson;

	public Long getDatasetId() {
		return datasetId;
	}

	public void setDatasetId(Long datasetId) {
		this.datasetId = datasetId;
	}

	public Long getDatasourceId() {
		return datasourceId;
	}

	public void setDatasourceId(Long datasourceId) {
		this.datasourceId = datasourceId;
	}

	public String getTableName() {
		return tableName;
	}

	public void setTableName(String tableName) {
		this.tableName = tableName;
	}

	public String getTableAlias() {
		return tableAlias;
	}

	public void setTableAlias(String tableAlias) {
		this.tableAlias = tableAlias;
	}

	public String getTableComment() {
		return tableComment;
	}

	public void setTableComment(String tableComment) {
		this.tableComment = tableComment;
	}

	public String getFieldsJson() {
		return fieldsJson;
	}

	public void setFieldsJson(String fieldsJson) {
		this.fieldsJson = fieldsJson;
	}

	public String getRelationsJson() {
		return relationsJson;
	}

	public void setRelationsJson(String relationsJson) {
		this.relationsJson = relationsJson;
	}
}
