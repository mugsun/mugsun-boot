package com.mugsun.boot.ai.service;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.boot.ai.AiModuleService;
import com.mugsun.boot.ai.entity.AiKnowledge;
import com.mugsun.boot.ai.entity.AiKnowledgeAsset;
import com.mugsun.boot.ai.entity.AiKnowledgeSegment;
import com.mugsun.boot.ai.entity.AiModel;
import com.mugsun.boot.ai.mapper.AiKnowledgeAssetMapper;
import com.mugsun.boot.ai.mapper.AiKnowledgeMapper;
import com.mugsun.boot.ai.mapper.AiKnowledgeSegmentMapper;
import com.mugsun.boot.ai.support.AiKbVectorStore;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.ai.support.AiRrfFusion;
import com.mugsun.boot.ai.support.AiTextSplitter;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class AiKnowledgeBizService {

	private final AiModuleService moduleService;
	private final AiKnowledgeMapper knowledgeMapper;
	private final AiKnowledgeAssetMapper assetMapper;
	private final AiKnowledgeSegmentMapper segmentMapper;
	private final AiModelBizService modelBizService;
	private final AiLlmClient llmClient;
	private final AiKbVectorStore vectorStore;
	private final WebClient.Builder webClientBuilder;
	private final com.mugsun.boot.ai.support.AiOutboundProperties outbound;
	private final com.mugsun.boot.ai.support.OutboundWebClientFactory outboundWebClients;

	public AiKnowledgeBizService(AiModuleService moduleService, AiKnowledgeMapper knowledgeMapper,
								 AiKnowledgeAssetMapper assetMapper, AiKnowledgeSegmentMapper segmentMapper,
								 AiModelBizService modelBizService, AiLlmClient llmClient,
								 AiKbVectorStore vectorStore, WebClient.Builder webClientBuilder,
								 com.mugsun.boot.ai.support.AiOutboundProperties outbound,
								 com.mugsun.boot.ai.support.OutboundWebClientFactory outboundWebClients) {
		this.outboundWebClients = outboundWebClients;
		this.moduleService = moduleService;
		this.knowledgeMapper = knowledgeMapper;
		this.assetMapper = assetMapper;
		this.segmentMapper = segmentMapper;
		this.modelBizService = modelBizService;
		this.llmClient = llmClient;
		this.vectorStore = vectorStore;
		this.webClientBuilder = webClientBuilder;
		this.outbound = outbound;
	}

	private static final java.util.Set<String> MODES = java.util.Set.of("hybrid", "vector", "keyword");

	public Page<AiKnowledge> page(long pageNum, long pageSize, String name) {
		moduleService.requireEnabled();
		if (pageNum < 1) {
			throw new ServiceException("页码请从 1 开始");
		}
		if (pageSize < 1 || pageSize > 100) {
			throw new ServiceException("每页条数请在 1 到 100 之间");
		}
		QueryWrapper q = QueryWrapper.create().orderBy("id", false);
		if (name != null && !name.isBlank()) {
			q.like("name", name.trim());
		}
		Page<AiKnowledge> page = knowledgeMapper.paginate(pageNum, pageSize, q);
		for (AiKnowledge kb : page.getRecords()) {
			long docs = assetMapper.selectCountByQuery(QueryWrapper.create().eq("knowledge_id", kb.getId()));
			long segs = segmentMapper.selectCountByQuery(QueryWrapper.create().eq("knowledge_id", kb.getId()));
			kb.setDocCount((int) docs);
			kb.setSegmentCount((int) segs);
		}
		return page;
	}

	public AiKnowledge detail(Long id) {
		moduleService.requireEnabled();
		return require(id);
	}

	public AiKnowledge submit(AiKnowledge body) {
		moduleService.requireEnabled();
		if (body == null) {
			throw new ServiceException("请填写知识库名称。用来在列表里区分");
		}
		String name = body.getName() == null ? "" : body.getName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写知识库名称。用来在列表里区分");
		}
		if (name.length() > 64) {
			throw new ServiceException("知识库名称请控制在 64 个字以内");
		}
		String description = body.getDescription() == null ? null : body.getDescription().trim();
		if (description != null && description.length() > 200) {
			throw new ServiceException("描述请控制在 200 个字以内");
		}
		String mode = body.getRetrievalMode() == null ? "" : body.getRetrievalMode().trim();
		if (mode.isEmpty()) {
			mode = "hybrid";
		}
		if (!MODES.contains(mode)) {
			throw new ServiceException("检索请选择混合、向量或关键词");
		}
		if (body.getTopK() != null && (body.getTopK() < 1 || body.getTopK() > 20)) {
			throw new ServiceException("返回条数请填 1 到 20");
		}
		if (body.getMinScore() != null
			&& (body.getMinScore().compareTo(BigDecimal.ZERO) < 0 || body.getMinScore().compareTo(BigDecimal.ONE) > 0)) {
			throw new ServiceException("最低分请填 0 到 1 之间的数");
		}
		if (body.getStatus() != null && body.getStatus() != 0 && body.getStatus() != 1) {
			throw new ServiceException("状态请选择启用或停用");
		}
		if (body.getEmbeddingModelId() != null) {
			AiModel model = modelBizService.requireRaw(body.getEmbeddingModelId());
			if (!AiConstants.MODEL_TYPE_EMBEDDING.equals(model.getModelType())) {
				throw new ServiceException("请选择向量模型。对话模型不能用来做知识库检索");
			}
		}
		body.setName(name);
		body.setDescription(description == null || description.isEmpty() ? null : description);
		body.setRetrievalMode(mode);
		requireUniqueName(name, body.getId());
		if (body.getId() == null) {
			body.sanitizeForInsert();
			body.setTenantId(TenantContext.current());
			if (body.getStatus() == null) {
				body.setStatus(AiConstants.STATUS_ENABLE);
			}
			if (body.getTopK() == null) {
				body.setTopK(6);
			}
			if (body.getMinScore() == null) {
				body.setMinScore(new BigDecimal("0.35"));
			}
			if (body.getRerankFlag() == null) {
				body.setRerankFlag(AiConstants.FLAG_NO);
			}
			knowledgeMapper.insert(body);
			return body;
		}
		AiKnowledge db = require(body.getId());
		body.setTenantId(db.getTenantId());
		body.sanitizeForUpdate();
		knowledgeMapper.update(body);
		return body;
	}

	public List<AiKnowledge> remove(List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的知识库");
		}
		List<AiKnowledge> rows = new ArrayList<>();
		for (Long id : ids) {
			if (id == null) {
				throw new ServiceException("知识库编号请填写数字，可以从列表里复制");
			}
			rows.add(require(id));
		}
		for (AiKnowledge row : rows) {
			vectorStore.deleteByKnowledge(row.getId());
			knowledgeMapper.deleteById(row.getId());
		}
		return rows;
	}

	/** 复制知识库配置壳（不含资料/分段），名称加「-副本」 */
	public AiKnowledge copy(Long id) {
		moduleService.requireEnabled();
		AiKnowledge src = require(id);
		AiKnowledge dst = new AiKnowledge();
		dst.sanitizeForInsert();
		dst.setTenantId(TenantContext.current());
		dst.setIcon(src.getIcon());
		String copyName = (src.getName() == null ? "知识库" : src.getName()) + "-副本";
		if (copyName.length() > 64) {
			throw new ServiceException("复制后的名称会超过 64 个字。请先把原知识库名称改短再复制");
		}
		requireUniqueName(copyName, null);
		dst.setName(copyName);
		dst.setDescription(src.getDescription());
		dst.setVectorStoreId(src.getVectorStoreId());
		dst.setEmbeddingModelId(src.getEmbeddingModelId());
		dst.setDimensions(src.getDimensions());
		dst.setRetrievalMode(src.getRetrievalMode());
		dst.setTopK(src.getTopK());
		dst.setMinScore(src.getMinScore());
		dst.setRerankFlag(src.getRerankFlag());
		dst.setRerankModelId(src.getRerankModelId());
		dst.setStatus(src.getStatus() == null ? AiConstants.STATUS_ENABLE : src.getStatus());
		knowledgeMapper.insert(dst);
		return dst;
	}

	/** 列表页快捷测试：走同一套 hitTest，返回摘要 */
	public Map<String, Object> test(Long id, String query) {
		moduleService.requireEnabled();
		if (id == null) {
			throw new ServiceException("请先在列表里选择要测试的知识库");
		}
		if (query == null || query.isBlank()) {
			throw new ServiceException("请先填写要测试的问题。用来看能不能检索到资料");
		}
		String q = query.trim();
		List<Map<String, Object>> hits = hitTest(id, q, 0);
		Map<String, Object> out = new HashMap<>();
		out.put("ok", true);
		out.put("hitCount", hits.size());
		out.put("query", q);
		out.put("hits", hits);
		out.put("message", hits.isEmpty()
			? "没有命中资料。请先上传内容，或换一个更接近原文的问题再测"
			: ("命中 " + hits.size() + " 条。可以打开知识库核对这些分段"));
		return out;
	}

	public void updateStatus(Long id, Integer status) {
		moduleService.requireEnabled();
		if (id == null) {
			throw new ServiceException("请先在列表里选择要改状态的知识库");
		}
		AiKnowledge kb = require(id);
		if (status == null || (status != 0 && status != 1)) {
			throw new ServiceException("状态请选择启用或停用");
		}
		kb.setStatus(status);
		kb.sanitizeForUpdate();
		knowledgeMapper.update(kb);
	}

	public AiKnowledgeAsset uploadAsset(AiKnowledgeAsset body) {
		moduleService.requireEnabled();
		AiKnowledge kb = require(body.getKnowledgeId());
		body.sanitizeForInsert();
		body.setTenantId(TenantContext.current());
		if (body.getVectorStatus() == null) {
			body.setVectorStatus("pending");
		}
		if (body.getProgress() == null) {
			body.setProgress(0);
		}
		String text = resolveContent(body);
		if (text == null || text.isBlank()) {
			throw new ServiceException("请先贴上正文，或填写能打开的文件地址。没有内容就没法分段");
		}
		body.setContentText(text);
		assetMapper.insert(body);

		int len = body.getSegmentLength() == null || body.getSegmentLength() <= 0 ? 500 : body.getSegmentLength();
		int overlap = body.getSegmentOverlap() == null ? 50 : Math.max(0, body.getSegmentOverlap());
		List<String> chunks = chunk(text, len, overlap, body.getSegmentType(), body.getSegmentSymbol());
		int seq = 0;
		List<AiKnowledgeSegment> segs = new ArrayList<>();
		for (String part : chunks) {
			if (part.isBlank()) {
				continue;
			}
			AiKnowledgeSegment seg = new AiKnowledgeSegment();
			seg.sanitizeForInsert();
			seg.setKnowledgeId(body.getKnowledgeId());
			seg.setAssetId(body.getId());
			seg.setTenantId(body.getTenantId());
			seg.setSeq(++seq);
			seg.setContent(part.trim());
			seg.setTokenCount(Math.max(1, part.length() / 2));
			seg.setVectorStatus("pending");
			seg.setEnabled(AiConstants.FLAG_YES);
			segmentMapper.insert(seg);
			segs.add(seg);
		}
		body.setSegmentCount(seq);
		body.setProgress(40);
		body.sanitizeForUpdate();
		assetMapper.update(body);

		vectorizeSegments(kb, segs);
		body.setVectorStatus("success");
		body.setProgress(100);
		body.sanitizeForUpdate();
		assetMapper.update(body);
		return body;
	}

	/** 对知识库分段向量化；rebuild=true 时先清向量再全量重建，否则只处理非 success */
	public Map<String, Object> vectorize(Long knowledgeId, boolean rebuild) {
		moduleService.requireEnabled();
		AiKnowledge kb = require(knowledgeId);
		List<AiKnowledgeSegment> all = segmentMapper.selectListByQuery(
			QueryWrapper.create().eq("knowledge_id", knowledgeId)
				.eq("enabled", AiConstants.FLAG_YES)
				.orderBy("id", true));
		List<AiKnowledgeSegment> segs = new ArrayList<>();
		if (rebuild) {
			for (AiKnowledgeSegment s : all) {
				vectorStore.deleteBySegment(s.getId());
				s.setVectorStatus("pending");
				s.setEmbeddingId(null);
				s.sanitizeForUpdate();
				segmentMapper.update(s);
				segs.add(s);
			}
			List<AiKnowledgeAsset> assets = assets(knowledgeId);
			for (AiKnowledgeAsset a : assets) {
				a.setVectorStatus("pending");
				a.setProgress(0);
				a.sanitizeForUpdate();
				assetMapper.update(a);
			}
		} else {
			for (AiKnowledgeSegment s : all) {
				String st = s.getVectorStatus();
				if (st == null || st.isBlank() || !"success".equalsIgnoreCase(st)) {
					segs.add(s);
				}
			}
		}
		int n = vectorizeSegments(kb, segs);
		boolean vectorOn = vectorStore.isVectorAvailable();
		if (rebuild || n > 0) {
			List<AiKnowledgeAsset> assets = assets(knowledgeId);
			for (AiKnowledgeAsset a : assets) {
				List<AiKnowledgeSegment> assetSegs = segmentMapper.selectListByQuery(
					QueryWrapper.create().eq("asset_id", a.getId()).eq("enabled", AiConstants.FLAG_YES));
				long pending = assetSegs.stream()
					.filter(s -> s.getVectorStatus() == null || !"success".equalsIgnoreCase(s.getVectorStatus()))
					.count();
				a.setVectorStatus(pending == 0 && !assetSegs.isEmpty() ? "success" : "pending");
				a.setProgress(pending == 0 && !assetSegs.isEmpty() ? 100 : 40);
				a.sanitizeForUpdate();
				assetMapper.update(a);
			}
		}
		Map<String, Object> stats = vectorStats(knowledgeId);
		stats.put("ok", true);
		stats.put("count", n);
		stats.put("rebuild", rebuild);
		stats.put("vectorAvailable", vectorOn);
		if (!vectorOn) {
			stats.put("message", "未安装向量扩展，已回落关键词检索，未写入向量");
		}
		return stats;
	}

	public Map<String, Object> vectorStats(Long knowledgeId) {
		moduleService.requireEnabled();
		require(knowledgeId);
		List<AiKnowledgeSegment> segs = segmentMapper.selectListByQuery(
			QueryWrapper.create().eq("knowledge_id", knowledgeId).eq("enabled", AiConstants.FLAG_YES));
		int total = segs.size();
		int success = 0;
		int pending = 0;
		int failed = 0;
		for (AiKnowledgeSegment s : segs) {
			String st = s.getVectorStatus() == null ? "" : s.getVectorStatus().toLowerCase(Locale.ROOT);
			switch (st) {
				case "success" -> success++;
				case "failed" -> failed++;
				default -> pending++;
			}
		}
		Map<String, Object> m = new HashMap<>();
		m.put("total", total);
		m.put("success", success);
		m.put("pending", pending);
		m.put("failed", failed);
		return m;
	}

	public List<AiKnowledgeAsset> assets(Long knowledgeId) {
		moduleService.requireEnabled();
		return assetMapper.selectListByQuery(
			QueryWrapper.create().eq("knowledge_id", knowledgeId).orderBy("id", false));
	}

	public List<AiKnowledgeAsset> removeAssets(List<Long> ids) {
		moduleService.requireEnabled();
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的资料");
		}
		List<AiKnowledgeAsset> rows = new ArrayList<>();
		for (Long id : ids) {
			if (id == null) {
				throw new ServiceException("资料编号请填写数字，可以从列表里复制");
			}
			AiKnowledgeAsset asset = assetMapper.selectOneById(id);
			if (asset == null) {
				throw new ServiceException("这份资料不存在。请刷新列表后再试");
			}
			rows.add(asset);
		}
		for (AiKnowledgeAsset a : rows) {
			Long id = a.getId();
			List<AiKnowledgeSegment> segs = segmentMapper.selectListByQuery(
				QueryWrapper.create().eq("asset_id", id));
			for (AiKnowledgeSegment s : segs) {
				vectorStore.deleteBySegment(s.getId());
				segmentMapper.deleteById(s.getId());
			}
			assetMapper.deleteById(id);
		}
		return rows;
	}

	public AiKnowledgeAsset resegmentAsset(Long assetId) {
		moduleService.requireEnabled();
		AiKnowledgeAsset asset = assetMapper.selectOneById(assetId);
		if (asset == null) {
			throw new ServiceException("这份资料不存在。请刷新列表后再试");
		}
		AiKnowledge kb = require(asset.getKnowledgeId());
		List<AiKnowledgeSegment> old = segmentMapper.selectListByQuery(
			QueryWrapper.create().eq("asset_id", assetId));
		for (AiKnowledgeSegment s : old) {
			vectorStore.deleteBySegment(s.getId());
			segmentMapper.deleteById(s.getId());
		}
		String text = resolveContent(asset);
		if (text == null || text.isBlank()) {
			throw new ServiceException("这份资料还没有正文。请重新上传后再分段");
		}
		int len = asset.getSegmentLength() == null || asset.getSegmentLength() <= 0 ? 500 : asset.getSegmentLength();
		int overlap = asset.getSegmentOverlap() == null ? 50 : Math.max(0, asset.getSegmentOverlap());
		List<String> chunks = chunk(text, len, overlap, asset.getSegmentType(), asset.getSegmentSymbol());
		int seq = 0;
		List<AiKnowledgeSegment> segs = new ArrayList<>();
		for (String part : chunks) {
			if (part.isBlank()) {
				continue;
			}
			AiKnowledgeSegment seg = new AiKnowledgeSegment();
			seg.sanitizeForInsert();
			seg.setKnowledgeId(asset.getKnowledgeId());
			seg.setAssetId(asset.getId());
			seg.setTenantId(asset.getTenantId());
			seg.setSeq(++seq);
			seg.setContent(part.trim());
			seg.setTokenCount(Math.max(1, part.length() / 2));
			seg.setVectorStatus("pending");
			seg.setEnabled(AiConstants.FLAG_YES);
			segmentMapper.insert(seg);
			segs.add(seg);
		}
		asset.setSegmentCount(seq);
		asset.setProgress(40);
		asset.setVectorStatus("pending");
		asset.sanitizeForUpdate();
		assetMapper.update(asset);
		vectorizeSegments(kb, segs);
		asset.setVectorStatus("success");
		asset.setProgress(100);
		asset.sanitizeForUpdate();
		assetMapper.update(asset);
		return asset;
	}

	public AiKnowledgeSegment saveSegment(AiKnowledgeSegment body) {
		moduleService.requireEnabled();
		if (body.getId() == null) {
			throw new ServiceException("请先在列表里选择要修改的分段");
		}
		AiKnowledgeSegment db = segmentMapper.selectOneById(body.getId());
		if (db == null) {
			throw new ServiceException("这个分段不存在。请刷新列表后再试");
		}
		if (body.getContent() != null) {
			db.setContent(body.getContent());
			db.setTokenCount(Math.max(1, body.getContent().length() / 2));
			db.setVectorStatus("pending");
		}
		db.sanitizeForUpdate();
		segmentMapper.update(db);
		AiKnowledge kb = require(db.getKnowledgeId());
		vectorizeSegments(kb, List.of(db));
		return db;
	}

	public String segmentStatus(Long id, Object enabledRaw) {
		moduleService.requireEnabled();
		if (id == null) {
			throw new ServiceException("请先在列表里选择要改状态的分段");
		}
		AiKnowledgeSegment db = segmentMapper.selectOneById(id);
		if (db == null) {
			throw new ServiceException("这个分段不存在。请刷新列表后再试");
		}
		require(db.getKnowledgeId());
		if (enabledRaw == null || String.valueOf(enabledRaw).isBlank()) {
			throw new ServiceException("状态请选择启用或停用");
		}
		String text = String.valueOf(enabledRaw).trim();
		if (!"0".equals(text) && !"1".equals(text)) {
			throw new ServiceException("状态请选择启用或停用");
		}
		int enabled = Integer.parseInt(text);
		db.setEnabled(enabled);
		db.sanitizeForUpdate();
		segmentMapper.update(db);
		return enabled == 1 ? "已启用。检索时会带上这段" : "已停用。检索时不会再带上这段";
	}

	public List<AiKnowledgeSegment> segments(Long knowledgeId, Long assetId) {
		moduleService.requireEnabled();
		QueryWrapper q = QueryWrapper.create().eq("knowledge_id", knowledgeId).orderBy("seq", true);
		if (assetId != null) {
			q.eq("asset_id", assetId);
		}
		return segmentMapper.selectListByQuery(q);
	}

	/**
	 * 命中测试 / 对话 RAG：按知识库 retrievalMode 做 vector / keyword / hybrid(RRF)。
	 * 尊重 minScore；rerankFlag=1 时做轻量词面加权（无外部 rerank 模型）。
	 */
	public List<Map<String, Object>> hitTest(Long knowledgeId, String query, int topK) {
		moduleService.requireEnabled();
		AiKnowledge kb = require(knowledgeId);
		if (query == null || query.isBlank()) {
			return List.of();
		}
		int k = topK > 0 ? Math.min(topK, 20)
			: (kb.getTopK() == null ? 6 : Math.max(1, Math.min(kb.getTopK(), 20)));
		String mode = kb.getRetrievalMode() == null || kb.getRetrievalMode().isBlank()
			? "hybrid" : kb.getRetrievalMode().trim().toLowerCase(Locale.ROOT);
		Double minScore = kb.getMinScore() == null ? null : kb.getMinScore().doubleValue();
		// 候选池放大，融合后再截 topK
		int pool = Math.min(50, Math.max(k * 3, k));

		List<Map<String, Object>> vectorHits = List.of();
		List<Map<String, Object>> keywordHits = keywordSearch(knowledgeId, query.trim(), pool);

		if (!"keyword".equals(mode) && vectorStore.isVectorAvailable()) {
			float[] qv = embedText(kb, query);
			// 向量路在入库前按 minScore 过滤；hybrid 融合分与余弦分不可比，不再对 RRF 分二次卡阈值
			List<Map<String, Object>> raw = vectorStore.search(knowledgeId, qv, pool, minScore);
			vectorHits = enrichHits(raw);
		}

		List<Map<String, Object>> hits;
		switch (mode) {
			case "vector" -> hits = vectorHits;
			case "keyword" -> hits = keywordHits;
			default -> {
				if (vectorHits.isEmpty()) {
					hits = keywordHits;
				} else if (keywordHits.isEmpty()) {
					hits = vectorHits;
				} else {
					hits = enrichHits(AiRrfFusion.fuse(k * 2, vectorHits, keywordHits));
				}
			}
		}

		if (Integer.valueOf(AiConstants.FLAG_YES).equals(kb.getRerankFlag()) || "hybrid".equals(mode)) {
			// hybrid 默认做轻量词面加权，避免长问句仅靠向量漂到无关段
			hits = AiRrfFusion.lexicalBoost(hits, query, k);
		} else if (hits.size() > k) {
			hits = new ArrayList<>(hits.subList(0, k));
		}
		return hits;
	}

	private List<Map<String, Object>> enrichHits(List<Map<String, Object>> raw) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (raw == null) {
			return out;
		}
		for (Map<String, Object> vh : raw) {
			Object sid = vh.get("segmentId");
			if (sid == null) {
				continue;
			}
			Long segId = sid instanceof Number n ? n.longValue() : Long.valueOf(sid.toString());
			AiKnowledgeSegment s = segmentMapper.selectOneById(segId);
			Map<String, Object> m = new HashMap<>(vh);
			m.put("segmentId", segId);
			m.put("content", s == null ? vh.getOrDefault("content", vh.get("contentPreview")) : s.getContent());
			if (s != null) {
				m.put("assetId", s.getAssetId());
			}
			if (!m.containsKey("source") || m.get("source") == null) {
				m.put("source", "vector");
			}
			out.add(m);
		}
		return out;
	}

	private List<Map<String, Object>> keywordSearch(Long knowledgeId, String query, int topK) {
		List<String> tokens = tokenizeQuery(query);
		// 知识库规模通常不大：拉启用分段在内存里按词面覆盖率打分（避免 Flex OR 歧义 + 整句 LIKE 永不命中）
		List<AiKnowledgeSegment> segs = segmentMapper.selectListByQuery(
			QueryWrapper.create().eq("knowledge_id", knowledgeId)
				.eq("enabled", AiConstants.FLAG_YES)
				.orderBy("id", true)
				.limit(500));
		List<String> terms = tokens.isEmpty()
			? List.of(query.trim().toLowerCase(Locale.ROOT))
			: tokens;
		List<Map<String, Object>> hits = new ArrayList<>();
		for (AiKnowledgeSegment s : segs) {
			String content = s.getContent() == null ? "" : s.getContent();
			String lower = content.toLowerCase(Locale.ROOT);
			int matched = 0;
			int firstIdx = Integer.MAX_VALUE;
			for (String t : terms) {
				String tl = t.toLowerCase(Locale.ROOT);
				if (tl.isBlank()) {
					continue;
				}
				int idx = lower.indexOf(tl);
				if (idx >= 0) {
					matched++;
					firstIdx = Math.min(firstIdx, idx);
				}
			}
			int denom = Math.max(1, terms.size());
			double cover = matched / (double) denom;
			if (cover <= 0) {
				continue;
			}
			double pos = firstIdx == Integer.MAX_VALUE ? 0
				: (1.0 - Math.min(1.0, firstIdx / (double) Math.max(1, lower.length())));
			double score = 0.35 + 0.45 * cover + 0.2 * pos;
			Map<String, Object> m = new HashMap<>();
			m.put("segmentId", s.getId());
			m.put("assetId", s.getAssetId());
			m.put("content", content);
			m.put("score", Math.round(score * 1_000_000d) / 1_000_000d);
			m.put("source", "keyword");
			hits.add(m);
		}
		hits.sort(Comparator.comparingDouble((Map<String, Object> h) ->
			h.get("score") instanceof Number n ? n.doubleValue() : 0).reversed());
		if (hits.size() > topK) {
			return new ArrayList<>(hits.subList(0, topK));
		}
		return hits;
	}

	/** 拆中英关键词；过滤过短停用词，保证「检索模式」「创建知识库」等能命中 */
	static List<String> tokenizeQuery(String query) {
		if (query == null || query.isBlank()) {
			return List.of();
		}
		String raw = query.trim();
		List<String> out = new ArrayList<>();
		for (String w : raw.split("[^A-Za-z0-9_\u4e00-\u9fff]+")) {
			if (w.isBlank()) {
				continue;
			}
			if (w.matches("[A-Za-z0-9_]+")) {
				if (w.length() >= 2) {
					out.add(w);
				}
				continue;
			}
			if (w.length() <= 8) {
				out.add(w);
			} else {
				out.add(w.substring(0, Math.min(8, w.length())));
				for (int i = 0; i + 2 <= w.length(); i++) {
					out.add(w.substring(i, i + 2));
				}
			}
		}
		List<String> uniq = new ArrayList<>();
		for (String t : out) {
			if (!uniq.contains(t)) {
				uniq.add(t);
			}
		}
		return uniq.size() > 24 ? uniq.subList(0, 24) : uniq;
	}

	private int vectorizeSegments(AiKnowledge kb, List<AiKnowledgeSegment> segs) {
		if (segs == null || segs.isEmpty()) {
			return 0;
		}
		int ok = 0;
		AiModel embModel = resolveEmbeddingModel(kb);
		List<String> batch = new ArrayList<>();
		List<AiKnowledgeSegment> batchSegs = new ArrayList<>();
		int batchSize = 8;
		for (AiKnowledgeSegment seg : segs) {
			batch.add(seg.getContent());
			batchSegs.add(seg);
			if (batch.size() >= batchSize) {
				ok += flushEmbedBatch(kb, embModel, batch, batchSegs);
				batch.clear();
				batchSegs.clear();
			}
		}
		if (!batch.isEmpty()) {
			ok += flushEmbedBatch(kb, embModel, batch, batchSegs);
		}
		return ok;
	}

	private int flushEmbedBatch(AiKnowledge kb, AiModel embModel, List<String> texts, List<AiKnowledgeSegment> segs) {
		if (!vectorStore.isVectorAvailable()) {
			for (AiKnowledgeSegment seg : segs) {
				seg.setVectorStatus("keyword");
				seg.sanitizeForUpdate();
				segmentMapper.update(seg);
			}
			return 0;
		}
		List<float[]> vectors;
		try {
			if (embModel != null) {
				vectors = llmClient.embedBatch(embModel, texts);
			} else {
				vectors = new ArrayList<>();
				for (String t : texts) {
					vectors.add(AiKbVectorStore.localHashEmbed(t));
				}
			}
		} catch (Exception e) {
			vectors = new ArrayList<>();
			for (String t : texts) {
				vectors.add(AiKbVectorStore.localHashEmbed(t));
			}
		}
		int n = Math.min(vectors.size(), segs.size());
		for (int i = 0; i < n; i++) {
			AiKnowledgeSegment seg = segs.get(i);
			String preview = seg.getContent() == null ? "" :
				(seg.getContent().length() > 200 ? seg.getContent().substring(0, 200) : seg.getContent());
			vectorStore.upsert(kb.getId(), seg.getId(), seg.getAssetId(), vectors.get(i), preview);
			seg.setVectorStatus("success");
			seg.setEmbeddingId(String.valueOf(seg.getId()));
			seg.sanitizeForUpdate();
			segmentMapper.update(seg);
		}
		return n;
	}

	private float[] embedText(AiKnowledge kb, String text) {
		AiModel embModel = resolveEmbeddingModel(kb);
		try {
			if (embModel != null) {
				return llmClient.embed(embModel, text);
			}
		} catch (Exception ignored) {
		}
		return AiKbVectorStore.localHashEmbed(text);
	}

	private AiModel resolveEmbeddingModel(AiKnowledge kb) {
		try {
			if (kb.getEmbeddingModelId() != null) {
				return modelBizService.requireRaw(kb.getEmbeddingModelId());
			}
			return modelBizService.requireDefaultEmbedding();
		} catch (Exception e) {
			return null;
		}
	}

	private String resolveContent(AiKnowledgeAsset body) {
		if (body.getContentText() != null && !body.getContentText().isBlank()) {
			return body.getContentText();
		}
		if (body.getFileUrl() != null && !body.getFileUrl().isBlank()) {
			String url = body.getFileUrl().trim();
			if (url.startsWith("http://") || url.startsWith("https://")) {
				url = outbound.guard(url);
				try {
					byte[] bytes = outboundWebClients.create().get().uri(url)
						.retrieve().bodyToMono(byte[].class).block(Duration.ofSeconds(30));
					if (bytes != null && bytes.length > 0) {
						return new String(bytes, StandardCharsets.UTF_8);
					}
				} catch (Exception ignored) {
				}
			}
		}
		return body.getFileName();
	}

	static List<String> chunk(String text, int length, int overlap, String type, String symbol) {
		return AiTextSplitter.split(text, length, overlap, type, symbol);
	}

	private void requireUniqueName(String name, Long excludeId) {
		QueryWrapper q = QueryWrapper.create().eq("name", name);
		if (excludeId != null) {
			q.ne("id", excludeId);
		}
		if (knowledgeMapper.selectCountByQuery(q) > 0) {
			throw new ServiceException("已经有叫「" + name + "」的知识库。请换一个名称，或打开原来那个");
		}
	}

	private AiKnowledge require(Long id) {
		AiKnowledge k = knowledgeMapper.selectOneById(id);
		if (k == null) {
			throw new ServiceException(AiConstants.MSG_KB_MISSING);
		}
		return k;
	}
}
