package com.mugsun.boot.ai.entity;

import com.mugsun.boot.common.crypto.Sm4TypeHandler;
import com.mugsun.core.mybatis.base.BaseEntity;
import com.mybatisflex.annotation.Table;
import com.mybatisflex.annotation.Column;

@Table("ai_vector_store")
public class AiVectorStore extends BaseEntity {

	private String tenantId;
	private String name;
	private String storeType;
	private String host;
	private Integer port;
	private String databaseName;
	private String tableName;
	private String collection;
	private Integer dbIndex;
	private String username;
	@Column(typeHandler = Sm4TypeHandler.class)
	private String password;
	private Integer dimensions;
	private String metric;
	private String indexType;
	private Integer builtinFlag;
	private String remark;
	/** 页面连接串，不落库，拆进 host / port / database。 */
	@Column(ignore = true)
	private String url;

	public String getTenantId() {
		return tenantId;
	}

	public void setTenantId(String tenantId) {
		this.tenantId = tenantId;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getStoreType() {
		return storeType;
	}

	public void setStoreType(String storeType) {
		this.storeType = storeType;
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public Integer getPort() {
		return port;
	}

	public void setPort(Integer port) {
		this.port = port;
	}

	public String getDatabaseName() {
		return databaseName;
	}

	public void setDatabaseName(String databaseName) {
		this.databaseName = databaseName;
	}

	public String getTableName() {
		return tableName;
	}

	public void setTableName(String tableName) {
		this.tableName = tableName;
	}

	public String getCollection() {
		return collection;
	}

	public void setCollection(String collection) {
		this.collection = collection;
	}

	public Integer getDbIndex() {
		return dbIndex;
	}

	public void setDbIndex(Integer dbIndex) {
		this.dbIndex = dbIndex;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public Integer getDimensions() {
		return dimensions;
	}

	public void setDimensions(Integer dimensions) {
		this.dimensions = dimensions;
	}

	public String getMetric() {
		return metric;
	}

	public void setMetric(String metric) {
		this.metric = metric;
	}

	public String getIndexType() {
		return indexType;
	}

	public void setIndexType(String indexType) {
		this.indexType = indexType;
	}

	public Integer getBuiltinFlag() {
		return builtinFlag;
	}

	public void setBuiltinFlag(Integer builtinFlag) {
		this.builtinFlag = builtinFlag;
	}

	public String getRemark() {
		return remark;
	}

	public void setRemark(String remark) {
		this.remark = remark;
	}

	/** 页面只填一个连接串，拆进 host / port / database。 */
	public String getUrl() {
		if (host == null || host.isBlank()) {
			return null;
		}
		if ("pgvector".equalsIgnoreCase(storeType)) {
			return "jdbc:postgresql://" + host + ":" + (port == null ? 5432 : port) + "/"
				+ (databaseName == null ? "" : databaseName);
		}
		return port == null ? host : host + ":" + port;
	}

	public void setUrl(String url) {
		if (url == null || url.isBlank()) {
			return;
		}
		String rest = url.trim();
		int scheme = rest.indexOf("://");
		if (rest.startsWith("jdbc:") && scheme >= 0) {
			rest = rest.substring(scheme + 3);
		}
		int slash = rest.indexOf('/');
		String hostPort = slash < 0 ? rest : rest.substring(0, slash);
		String db = slash < 0 ? null : rest.substring(slash + 1).split("\\?")[0];
		int colon = hostPort.lastIndexOf(':');
		if (colon > 0) {
			this.host = hostPort.substring(0, colon);
			try {
				this.port = Integer.valueOf(hostPort.substring(colon + 1));
			} catch (NumberFormatException ignored) {
				this.host = hostPort;
			}
		} else {
			this.host = hostPort;
		}
		if (db != null && !db.isBlank()) {
			this.databaseName = db;
		}
	}
}
