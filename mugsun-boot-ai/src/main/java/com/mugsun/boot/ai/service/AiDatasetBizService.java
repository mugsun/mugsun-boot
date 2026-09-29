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
		QueryWrapper q = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			q.like("name", name.trim());
		}
		return datasetMapper.paginate(pageNum, pageSize, q);
	}

	public AiDataset detail(Long id) {
		moduleService.requireEnabled();
		return require(id);
	}

	public AiDataset submit(AiDataset body) {
		moduleService.requireEnabled();
		if (body.getName() == null || body.getName().isBlank()) {
			throw new ServiceException("请填写名称");
		}
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			if (body.getEnabled() == null) {
				body.setEnabled(AiConstants.FLAG_YES);
			}
			datasetMapper.insert(body);
		} else {
			body.sanitizeForUpdate();
			datasetMapper.update(body);
		}
		return body;
	}

	public void remove(List<Long> ids) {
		moduleService.requireEnabled();
		for (Long id : ids) {
			require(id);
			datasetMapper.deleteById(id);
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
		List<AiDatasetTable> tables = tables(datasetId);
		if (tables.isEmpty()) {
			throw new ServiceException("请先配置问数表");
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
			throw new ServiceException("SQL 执行失败: " + e.getMessage());
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
		AiDataset ds = require(datasetId);
		AiModel model = ds.getModelId() != null ? modelBizService.requireRaw(ds.getModelId())
			: modelBizService.requireDefaultChat();
		String text = llmClient.chat(model, List.of(
			Map.of("role", "user", "content", "请分析以下业务问题并给出要点（不执行 SQL）：" + question)
		), 1024);
		return Map.of("analysis", text);
	}

	public Map<String, Object> predict(Long datasetId, String question) {
		moduleService.requireEnabled();
		AiDataset ds = require(datasetId);
		AiModel model = ds.getModelId() != null ? modelBizService.requireRaw(ds.getModelId())
			: modelBizService.requireDefaultChat();
		String text = llmClient.chat(model, List.of(
			Map.of("role", "user", "content", "请基于业务语境做趋势预测建议（占位，不保证准确）：" + question)
		), 1024);
		return Map.of("prediction", text);
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
		if (tables.isEmpty()) {
			throw new ServiceException("请先配置问数表");
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
			throw new ServiceException("SQL 执行失败: " + e.getMessage());
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

	private AiDataset require(Long id) {
		AiDataset d = datasetMapper.selectOneById(id);
		if (d == null) {
			throw new ServiceException(AiConstants.MSG_DATASET_MISSING);
		}
		return d;
	}
}
