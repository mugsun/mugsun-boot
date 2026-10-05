package com.mugsun.boot.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.*;
import com.mugsun.boot.ai.mapper.AiDatasetMapper;
import com.mugsun.boot.ai.mapper.AiDatasetTableMapper;
import com.mugsun.boot.ai.mapper.AiDatasetTermMapper;
import com.mugsun.boot.ai.mapper.AiTerminologyMapper;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.ai.support.SqlSafetyGate;
import com.mugsun.boot.ai.support.SqlTableGate;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.*;

@Service
public class AiDatasetBizService {

	private final AiModuleService moduleService;
	private final AiDatasetMapper datasetMapper;
	private final AiDatasetTableMapper tableMapper;
	private final AiTerminologyMapper terminologyMapper;
	private final AiDatasetTermMapper termLinkMapper;
	private final AiDatasourceBizService datasourceBizService;
	private final AiModelBizService modelBizService;
	private final AiLlmClient llmClient;
	private final ObjectMapper objectMapper;

	public AiDatasetBizService(AiModuleService moduleService, AiDatasetMapper datasetMapper,
							   AiDatasetTableMapper tableMapper, AiTerminologyMapper terminologyMapper,
							   AiDatasetTermMapper termLinkMapper, AiDatasourceBizService datasourceBizService,
							   AiModelBizService modelBizService, AiLlmClient llmClient, ObjectMapper objectMapper) {
		this.moduleService = moduleService;
		this.datasetMapper = datasetMapper;
		this.tableMapper = tableMapper;
		this.terminologyMapper = terminologyMapper;
		this.termLinkMapper = termLinkMapper;
		this.datasourceBizService = datasourceBizService;
		this.modelBizService = modelBizService;
		this.llmClient = llmClient;
		this.objectMapper = objectMapper;
	}

	public Page<AiDataset> page(long pageNum, long pageSize, String name) {
		moduleService.requireEnabled();
		if (pageNum < 1) {
			throw new ServiceException("页码请从 1 开始");
		}
		if (pageSize < 1 || pageSize > 100) {
			throw new ServiceException("每页条数请在 1 到 100 之间");
		}
		QueryWrapper q = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			String keyword = name.trim();
			if (keyword.length() > 64) {
				keyword = keyword.substring(0, 64);
			}
			q.like("name", keyword);
		}
		return datasetMapper.paginate(pageNum, pageSize, q);
	}

	public AiDataset detail(Long id) {
		moduleService.requireEnabled();
		return require(id);
	}

	public AiDataset submit(AiDataset body) {
		moduleService.requireEnabled();
		if (body == null) {
			throw new ServiceException("请填写问数名称。用来在列表里区分");
		}
		String name = body.getName() == null ? "" : body.getName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写问数名称。用来在列表里区分");
		}
		if (name.length() > 64) {
			throw new ServiceException("问数名称请控制在 64 个字以内");
		}
		String description = body.getDescription() == null ? "" : body.getDescription().trim();
		if (description.length() > 200) {
			throw new ServiceException("描述请控制在 200 个字以内");
		}
		if (body.getEnabled() != null && body.getEnabled() != 0 && body.getEnabled() != 1) {
			throw new ServiceException("状态请选择启用或停用");
		}
		requireUnique(name, body.getId());
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			body.setName(name);
			body.setDescription(description.isEmpty() ? null : description);
			if (body.getEnabled() == null) {
				body.setEnabled(AiConstants.FLAG_YES);
			}
			datasetMapper.insert(body);
			return body;
		}
		AiDataset db = require(body.getId());
		db.setName(name);
		db.setDescription(description.isEmpty() ? null : description);
		if (body.getEnabled() != null) {
			db.setEnabled(body.getEnabled());
		}
		datasetMapper.update(db);
		return db;
	}

	public List<AiDataset> remove(List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的问数");
		}
		List<AiDataset> rows = new ArrayList<>();
		for (Long id : ids) {
			rows.add(require(id));
		}
		for (AiDataset row : rows) {
			tableMapper.deleteByQuery(QueryWrapper.create().eq("dataset_id", row.getId()));
			datasetMapper.deleteById(row.getId());
		}
		return rows;
	}

	public AiDataset copy(Long id) {
		moduleService.requireEnabled();
		AiDataset source = require(id);
		String name = source.getName() + "-副本";
		if (name.length() > 64) {
			throw new ServiceException("复制后的名称会超过 64 个字。请先把原问数名称改短再复制");
		}
		requireUnique(name, null);
		AiDataset copy = new AiDataset();
		copy.sanitizeForInsert();
		copy.setTenantId(TenantContext.current());
		copy.setName(name);
		copy.setDescription(source.getDescription());
		copy.setDatasetType(source.getDatasetType());
		copy.setModelId(source.getModelId());
		copy.setEnabled(source.getEnabled());
		copy.setMaxRows(source.getMaxRows());
		copy.setTimeoutMs(source.getTimeoutMs());
		copy.setIcon(source.getIcon());
		datasetMapper.insert(copy);
		for (AiDatasetTable table : tables(id)) {
			AiDatasetTable row = new AiDatasetTable();
			row.sanitizeForInsert();
			row.setDatasetId(copy.getId());
			row.setDatasourceId(table.getDatasourceId());
			row.setTableName(table.getTableName());
			row.setTableAlias(table.getTableAlias());
			row.setTableComment(table.getTableComment());
			row.setFieldsJson(table.getFieldsJson());
			row.setRelationsJson(table.getRelationsJson());
			tableMapper.insert(row);
		}
		return copy;
	}

	public Map<String, Object> config(Long id) {
		moduleService.requireEnabled();
		AiDataset dataset = require(id);
		List<AiDatasetTable> rows = tables(id);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("tables", rows.stream().map(AiDatasetTable::getTableName).toList());
		data.put("datasourceId", rows.isEmpty() || rows.get(0).getDatasourceId() == null
			? null : String.valueOf(rows.get(0).getDatasourceId()));
		data.put("bizDesc", dataset.getDescription() == null ? "" : dataset.getDescription());
		return data;
	}

	public void saveConfig(Long id, Long datasourceId, List<String> tableNames, String bizDesc) {
		moduleService.requireEnabled();
		AiDataset dataset = require(id);
		if (datasourceId == null) {
			throw new ServiceException("请先选择数据源。选好后才能保存表");
		}
		datasourceBizService.requireRaw(datasourceId);
		String description = bizDesc == null ? "" : bizDesc.trim();
		if (description.length() > 200) {
			throw new ServiceException("描述请控制在 200 个字以内");
		}
		List<String> names = new ArrayList<>();
		if (tableNames != null) {
			for (String raw : tableNames) {
				if (raw == null || raw.isBlank()) {
					continue;
				}
				String tableName = raw.trim();
				if (!tableName.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
					throw new ServiceException("表名只能包含字母、数字和下划线。请重新选择");
				}
				if (!names.contains(tableName)) {
					names.add(tableName);
				}
			}
		}
		if (names.isEmpty()) {
			throw new ServiceException("请至少选择一张表。没有表就没法问数");
		}
		dataset.setDescription(description.isEmpty() ? null : description);
		datasetMapper.update(dataset);
		tableMapper.deleteByQuery(QueryWrapper.create().eq("dataset_id", id));
		for (String tableName : names) {
			AiDatasetTable row = new AiDatasetTable();
			row.sanitizeForInsert();
			row.setDatasetId(id);
			row.setDatasourceId(datasourceId);
			row.setTableName(tableName);
			tableMapper.insert(row);
		}
	}

	public AiDatasetTable saveTable(AiDatasetTable body) {
		moduleService.requireEnabled();
		require(body.getDatasetId());
		if (body.getId() == null) {
			body.sanitizeForInsert();
			tableMapper.insert(body);
		} else {
			body.sanitizeForUpdate();
			tableMapper.update(body);
		}
		return body;
	}

	public List<AiDatasetTable> tables(Long datasetId) {
		moduleService.requireEnabled();
		return tableMapper.selectListByQuery(QueryWrapper.create().eq("dataset_id", datasetId));
	}

	public AiTerminology saveTerm(AiTerminology body) {
		moduleService.requireEnabled();
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			terminologyMapper.insert(body);
		} else {
			body.sanitizeForUpdate();
			terminologyMapper.update(body);
		}
		return body;
	}

	public void bindTerm(Long datasetId, Long terminologyId) {
		moduleService.requireEnabled();
		require(datasetId);
		AiDatasetTerm link = new AiDatasetTerm();
		link.sanitizeForInsert();
		link.setDatasetId(datasetId);
		link.setTerminologyId(terminologyId);
		termLinkMapper.insert(link);
	}

	public Map<String, Object> ask(Long datasetId, String question) {
		moduleService.requireEnabled();
		AiDataset ds = require(datasetId);
		if (question == null || question.isBlank()) {
			throw new ServiceException("请先填写问题。用来生成查询");
		}
		List<AiDatasetTable> tables = tables(datasetId);
		if (tables.isEmpty()) {
			throw new ServiceException("还没有配置数据表。请先点配置，选好表后再问");
		}
		AiDatasetTable first = tables.get(0);
		AiDatasource datasource = datasourceBizService.requireRaw(first.getDatasourceId());
		Set<String> allowed = resolveAllowedTables(datasource, tables);
		StringBuilder schema = new StringBuilder();
		for (AiDatasetTable t : tables) {
			schema.append("表 ").append(t.getTableName());
			if (t.getTableAlias() != null) {
				schema.append("(").append(t.getTableAlias()).append(")");
			}
			schema.append("\n").append(t.getFieldsJson() == null ? "" : t.getFieldsJson()).append("\n");
		}
		AiModel model = ds.getModelId() != null ? modelBizService.requireRaw(ds.getModelId())
			: modelBizService.requireDefaultChat();
		String sqlRaw = llmClient.chat(model, List.of(
			Map.of("role", "system", "content",
				"你是 NL2SQL 助手。只输出一条 PostgreSQL SELECT（可 WITH），不要 markdown。"
					+ "只能使用下列表，禁止其它表：\n" + String.join(", ", allowed) + "\n\n" + schema),
			Map.of("role", "user", "content", question == null ? "" : question)
		), 512);
		String sql = SqlSafetyGate.guard(stripSql(sqlRaw));
		assertSqlTablesAllowed(sql, allowed);
		List<Map<String, Object>> rows = new ArrayList<>();
		int maxRows = ds.getMaxRows() == null ? 1000 : ds.getMaxRows();
		try (Connection c = datasourceBizService.open(datasource);
			 Statement st = c.createStatement()) {
			st.setMaxRows(maxRows);
			try (ResultSet rs = st.executeQuery(sql)) {
				ResultSetMetaData meta = rs.getMetaData();
				int cols = meta.getColumnCount();
				while (rs.next()) {
					Map<String, Object> row = new LinkedHashMap<>();
					for (int i = 1; i <= cols; i++) {
						row.put(meta.getColumnLabel(i), rs.getObject(i));
					}
					rows.add(row);
				}
			}
		} catch (ServiceException e) {
			throw e;
		} catch (Exception e) {
			throw new ServiceException("这次没有查出结果。请检查数据源能不能连上，再试一次");
		}
		Map<String, Object> resp = new HashMap<>();
		resp.put("sql", sql);
		resp.put("rows", rows);
		return resp;
	}

	/** 数据集表 ∪ 数据源表白名单（若配置）取交集；无白名单则仅数据集表白名单 */
	private Set<String> resolveAllowedTables(AiDatasource datasource, List<AiDatasetTable> tables) {
		Set<String> fromDataset = new LinkedHashSet<>();
		for (AiDatasetTable t : tables) {
			if (t.getTableName() != null && !t.getTableName().isBlank()) {
				fromDataset.add(t.getTableName().trim().toLowerCase(Locale.ROOT));
			}
		}
		String wl = datasource.getTableWhitelist();
		if (wl == null || wl.isBlank()) {
			return fromDataset;
		}
		Set<String> fromDs = new LinkedHashSet<>();
		for (String part : wl.split("[,;\\s]+")) {
			if (!part.isBlank()) {
				fromDs.add(part.trim().toLowerCase(Locale.ROOT));
			}
		}
		if (fromDs.isEmpty()) {
			return fromDataset;
		}
		Set<String> intersect = new LinkedHashSet<>();
		for (String t : fromDataset) {
			if (fromDs.contains(t)) {
				intersect.add(t);
			}
		}
		if (intersect.isEmpty()) {
			throw new ServiceException("数据集表不在数据源表白名单内");
		}
		return intersect;
	}

	/** 粗提取 FROM/JOIN 后的表名，必须落在允许集合内 */
	private void assertSqlTablesAllowed(String sql, Set<String> allowed) {
		SqlTableGate.assertAllowed(sql, allowed);
	}

	public Map<String, Object> analyze(Long datasetId, String question) {
		moduleService.requireEnabled();
		if (question == null || question.isBlank()) {
			throw new ServiceException("请先填写要分析的问题");
		}
		AiDataset ds = require(datasetId);
		AiModel model = ds.getModelId() != null ? modelBizService.requireRaw(ds.getModelId())
			: modelBizService.requireDefaultChat();
		try {
			String text = llmClient.chat(model, List.of(
				Map.of("role", "user", "content", "请分析以下业务问题并给出要点（不执行 SQL）：" + question)
			), 1024);
			return Map.of("analysis", text == null ? "" : text);
		} catch (Exception ignored) {
			throw new ServiceException("这次没有分析成功。请确认默认对话模型已激活，稍后再试");
		}
	}

	public Map<String, Object> predict(Long datasetId, String question) {
		moduleService.requireEnabled();
		if (question == null || question.isBlank()) {
			throw new ServiceException("请先填写要预测的问题");
		}
		AiDataset ds = require(datasetId);
		AiModel model = ds.getModelId() != null ? modelBizService.requireRaw(ds.getModelId())
			: modelBizService.requireDefaultChat();
		try {
			String text = llmClient.chat(model, List.of(
				Map.of("role", "user", "content", "请基于业务语境做趋势预测建议（占位，不保证准确）：" + question)
			), 1024);
			return Map.of("prediction", text == null ? "" : text);
		} catch (Exception ignored) {
			throw new ServiceException("这次没有预测成功。请确认默认对话模型已激活，稍后再试");
		}
	}

	public List<String> suggest(Long datasetId) {
		moduleService.requireEnabled();
		AiDataset ds = require(datasetId);
		List<AiDatasetTable> tables = tables(datasetId);
		List<String> out = new ArrayList<>();
		out.add("一共有多少条记录？");
		if (!tables.isEmpty()) {
			String t = tables.get(0).getTableName();
			out.add("统计表 " + t + " 的行数");
			out.add("列出 " + t + " 最近 10 条数据");
		}
		if (ds.getName() != null) {
			out.add(ds.getName() + " 的核心指标有哪些？");
		}
		return out;
	}

	public Map<String, Object> rerunSql(Long datasetId, String sqlRaw) {
		moduleService.requireEnabled();
		AiDataset ds = require(datasetId);
		List<AiDatasetTable> tables = tables(datasetId);
		if (sqlRaw == null || sqlRaw.isBlank()) {
			throw new ServiceException("请先填写要重跑的查询");
		}
		if (tables.isEmpty()) {
			throw new ServiceException("还没有配置数据表。请先点配置，选好表后再重跑");
		}
		AiDatasource datasource = datasourceBizService.requireRaw(tables.get(0).getDatasourceId());
		Set<String> allowed = resolveAllowedTables(datasource, tables);
		String sql = SqlSafetyGate.guard(stripSql(sqlRaw));
		assertSqlTablesAllowed(sql, allowed);
		List<Map<String, Object>> rows = new ArrayList<>();
		int maxRows = ds.getMaxRows() == null ? 1000 : ds.getMaxRows();
		try (Connection c = datasourceBizService.open(datasource);
			 Statement st = c.createStatement()) {
			st.setMaxRows(maxRows);
			try (ResultSet rs = st.executeQuery(sql)) {
				ResultSetMetaData meta = rs.getMetaData();
				int cols = meta.getColumnCount();
				while (rs.next()) {
					Map<String, Object> row = new LinkedHashMap<>();
					for (int i = 1; i <= cols; i++) {
						row.put(meta.getColumnLabel(i), rs.getObject(i));
					}
					rows.add(row);
				}
			}
		} catch (ServiceException e) {
			throw e;
		} catch (Exception e) {
			throw new ServiceException("这次没有查出结果。请检查数据源能不能连上，再试一次");
		}
		return Map.of("sql", sql, "rows", rows, "message", "已重跑，共 " + rows.size() + " 行");
	}

	private String stripSql(String raw) {
		if (raw == null) {
			return "";
		}
		String s = raw.trim();
		if (s.startsWith("```")) {
			int nl = s.indexOf('\n');
			if (nl > 0) {
				s = s.substring(nl + 1);
			}
			int end = s.lastIndexOf("```");
			if (end > 0) {
				s = s.substring(0, end);
			}
		}
		return s.trim();
	}

	private void requireUnique(String name, Long id) {
		QueryWrapper query = QueryWrapper.create().eq("name", name);
		if (id != null) {
			query.ne("id", id);
		}
		if (datasetMapper.selectCountByQuery(query) > 0) {
			throw new ServiceException("已经有叫「" + name + "」的问数。请换一个名称，或打开原来那个");
		}
	}

	private AiDataset require(Long id) {
		AiDataset d = datasetMapper.selectOneById(id);
		if (d == null) {
			throw new ServiceException(AiConstants.MSG_DATASET_MISSING);
		}
		return d;
	}
}
