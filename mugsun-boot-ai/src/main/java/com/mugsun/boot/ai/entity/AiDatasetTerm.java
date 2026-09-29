package com.mugsun.boot.ai.entity;

import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;

@Table("ai_dataset_term")
public class AiDatasetTerm extends BaseEntity {

	private Long datasetId;
	private Long terminologyId;

	public Long getDatasetId() {
		return datasetId;
	}

	public void setDatasetId(Long datasetId) {
		this.datasetId = datasetId;
	}

	public Long getTerminologyId() {
		return terminologyId;
	}

	public void setTerminologyId(Long terminologyId) {
		this.terminologyId = terminologyId;
	}
}
