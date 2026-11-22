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
import com.mugsun.boot.ai.support.AiDocumentTextExtractor;
import com.mugsun.boot.ai.support.AiKbDocumentTypes;
import com.mugsun.boot.ai.support.AiKbProperties;
import com.mugsun.boot.ai.support.AiKbVectorStore;
import com.mugsun.boot.ai.support.AiLlmClient;
import com.mugsun.boot.ai.support.AiRrfFusion;
import com.mugsun.boot.ai.support.AiSegmentOptions;
import com.mugsun.boot.ai.support.AiTextSplitter;
import com.mugsun.boot.tenant.TenantContext;
import com.mugsun.boot.common.i18n.StoredSentence;
import com.mugsun.core.i18n.message.I18n;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
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
	private final AiDocumentTextExtractor documentTextExtractor;
	private final AiKbProperties kbProperties;

	public AiKnowledgeBizService(AiModuleService moduleService, AiKnowledgeMapper knowledgeMapper,
								 AiKnowledgeAssetMapper assetMapper, AiKnowledgeSegmentMapper segmentMapper,
								 AiModelBizService modelBizService, AiLlmClient llmClient,
								 AiKbVectorStore vectorStore, WebClient.Builder webClientBuilder,
								 com.mugsun.boot.ai.support.AiOutboundProperties outbound,
								 com.mugsun.boot.ai.support.OutboundWebClientFactory outboundWebClients,
								 AiDocumentTextExtractor documentTextExtractor, AiKbProperties kbProperties) {
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
		this.documentTextExtractor = documentTextExtractor;
		this.kbProperties = kbProperties;
	}

	private static final java.util.Set<String> MODES = java.util.Set.of("hybrid", "vector", "keyword");

	private static final Logger log = LoggerFactory.getLogger(AiKnowledgeBizService.class);

	/** 关键词检索每次从库里读多少个分段 */
	static final int KEYWORD_PAGE_SIZE = 500;

	private static final String EMBED_CALL_FAILED = "向量模型调用失败，这些分段没有向量化。"
		+ "请检查向量模型的地址、密钥和额度，改好后点「开始向量化」重试";
	private static final String EMBED_MODEL_MISSING = "知识库选的向量模型已经不存在，这些分段没有向量化。"
		+ "请在知识库设置里重新选择向量模型，再点「开始向量化」";
	private static final String EMBED_COUNT_MISMATCH = "向量模型返回的向量条数和分段数对不上，这些分段没有向量化。"
		+ "请换一个向量模型，或稍后点「开始向量化」重试";

	/** 知识库该用的向量模型。model 和 problem 都为空表示没有配任何向量模型，入库和查询都用本地哈希向量 */
	private record EmbeddingChoice(AiModel model, String problem) {
	}

	/** 一次向量化的结果：写进向量表几段、失败几段、失败时给用户看的原因 */
	private record VectorizeResult(int ok, int failed, String problem) {
		static final VectorizeResult NONE = new VectorizeResult(0, 0, null);
	}

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
			? I18n.translate("没有命中资料。请先上传内容，或换一个更接近原文的问题再测")
			: I18n.get("命中 {0} 条。可以打开知识库核对这些分段", hits.size()));
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
		if (body == null || body.getKnowledgeId() == null) {
			throw new ServiceException("请先打开一个知识库再上传资料");
		}
		AiKnowledge kb = require(body.getKnowledgeId());
		body.sanitizeForInsert();
		body.setTenantId(TenantContext.current());
		AiSegmentOptions options = AiSegmentOptions.strict(body.getSegmentType(), body.getSegmentLength(),
			body.getSegmentOverlap(), body.getSegmentSymbol());
		applyOptions(body, options);
		requirePastedTextWithinLimit(body.getContentText());
		if (body.getVectorStatus() == null) {
			body.setVectorStatus("pending");
		}
		if (body.getProgress() == null) {
			body.setProgress(0);
		}
		ResolvedContent resolved = resolveContent(body);
		if (!resolved.ok()) {
			// 仅「有文件地址但解析失败」留 failed 行；纯空提交不插脏数据
			boolean hadRemote = body.getFileUrl() != null && !body.getFileUrl().isBlank();
			if (hadRemote) {
				persistFailedAsset(body, resolved.reason());
			}
			throw new ServiceException(StoredSentence.render(resolved.reason()));
		}
		return ingestSuccess(kb, body, resolved.text(), options);
	}

	/**
	 * multipart 上传：校验白名单与大小 → Tika 抽文 → 切段向量；
	 * 解析失败则留下 {@code vectorStatus=failed} 行（无假分段）并抛错。
	 */
	public AiKnowledgeAsset uploadAssetFile(Long knowledgeId, MultipartFile file, Integer segmentLength,
											Integer segmentOverlap, String segmentType, String segmentSymbol) {
		moduleService.requireEnabled();
		if (knowledgeId == null) {
			throw new ServiceException("请先打开一个知识库再上传资料");
		}
		if (file == null || file.isEmpty()) {
			throw new ServiceException("请选择要上传的文件");
		}
		AiKnowledge kb = require(knowledgeId);
		AiSegmentOptions options = AiSegmentOptions.strict(segmentType, segmentLength, segmentOverlap, segmentSymbol);
		String filename = file.getOriginalFilename();
		long max = kbProperties.getMaxUploadBytes();
		if (file.getSize() > max) {
			throw new ServiceException(I18n.get("文件超过 {0}MB。请压缩或拆分后再上传", (max / (1024 * 1024))));
		}
		byte[] bytes;
		try {
			bytes = file.getBytes();
		} catch (Exception e) {
			throw new ServiceException("文件读不出来。请换一个文件再试");
		}
		if (bytes.length == 0) {
			throw new ServiceException("文件是空的。请换一份有正文的资料再上传");
		}
		if (bytes.length > max) {
			throw new ServiceException(I18n.get("文件超过 {0}MB。请压缩或拆分后再上传", (max / (1024 * 1024))));
		}
		int headLen = Math.min(1024, bytes.length);
		String ext = AiKbDocumentTypes.validate(filename, file.getContentType(), bytes, headLen);
		AiKnowledgeAsset body = new AiKnowledgeAsset();
		body.sanitizeForInsert();
		body.setKnowledgeId(knowledgeId);
		body.setTenantId(TenantContext.current());
		body.setFileName(filename == null || filename.isBlank() ? ("document." + ext) : filename.trim());
		body.setFileSize((long) bytes.length);
		body.setFileType(ext);
		applyOptions(body, options);
		body.setVectorStatus("pending");
		body.setProgress(0);
		body.setSegmentCount(0);

		String[] reason = new String[1];
		String text = documentTextExtractor.extractOrNull(bytes, body.getFileName(), AiKbDocumentTypes.mimeOf(ext), reason);
		if (text == null || text.isBlank()) {
			persistFailedAsset(body, reason[0] != null ? reason[0]
				: "没从文件里抽出正文。可能是扫描件或空文档，当前不支持 OCR");
			throw new ServiceException(StoredSentence.render(body.getFailReason()));
		}
		return ingestSuccess(kb, body, text, options);
	}

	private AiKnowledgeAsset ingestSuccess(AiKnowledge kb, AiKnowledgeAsset body, String text, AiSegmentOptions options) {
		// 先切段再入库：段数超限时直接拒绝，不留下资料行和半截分段
		List<String> chunks = chunkWithinLimit(text, options);
		body.setContentText(text);
		body.setFailReason(null);
		body.setVectorStatus("pending");
		if (body.getId() == null) {
			assetMapper.insert(body);
		} else {
			body.sanitizeForUpdate();
			assetMapper.update(body);
		}

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

		applyVectorResult(body, vectorizeSegments(kb, segs));
		body.sanitizeForUpdate();
		assetMapper.update(body);
		return body;
	}

	/** 把向量化结果写到资料上：有分段没向量化成功就如实标 failed 并带上原因，不再一律写 success */
	private static void applyVectorResult(AiKnowledgeAsset asset, VectorizeResult result) {
		if (result.failed() > 0) {
			asset.setVectorStatus("failed");
			asset.setProgress(40);
			asset.setFailReason(truncateFail(result.problem()));
			return;
		}
		asset.setVectorStatus("success");
		asset.setProgress(100);
		if (asset.getFailReason() != null) {
			// 更新时空值不落库，要清掉上一次的失败原因只能写空串
			asset.setFailReason("");
		}
	}

	private void persistFailedAsset(AiKnowledgeAsset body, String reason) {
		String msg = truncateFail(reason == null || reason.isBlank()
			? "资料处理失败。请换文件或贴正文后再试"
			: reason);
		body.setContentText(null);
		body.setSegmentCount(0);
		body.setProgress(0);
		body.setVectorStatus("failed");
		body.setFailReason(msg);
		if (body.getId() == null) {
			assetMapper.insert(body);
		} else {
			body.sanitizeForUpdate();
			assetMapper.update(body);
		}
	}

	private static String truncateFail(String reason) {
		if (reason == null) {
			return "资料处理失败";
		}
		// 不能先 trim：带数字的记录用控制字符分隔，trim 会把末尾的分隔符当空白删掉
		return StoredSentence.fit(reason, 500);
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
		VectorizeResult result = vectorizeSegments(kb, segs);
		int n = result.ok();
		boolean vectorOn = vectorStore.isVectorAvailable();
		if (rebuild || n > 0 || result.failed() > 0) {
			List<AiKnowledgeAsset> assets = assets(knowledgeId);
			for (AiKnowledgeAsset a : assets) {
				List<AiKnowledgeSegment> assetSegs = segmentMapper.selectListByQuery(
					QueryWrapper.create().eq("asset_id", a.getId()).eq("enabled", AiConstants.FLAG_YES));
				long failed = assetSegs.stream()
					.filter(s -> "failed".equalsIgnoreCase(s.getVectorStatus()))
					.count();
				long pending = assetSegs.stream()
					.filter(s -> s.getVectorStatus() == null || !"success".equalsIgnoreCase(s.getVectorStatus()))
					.count();
				if (failed > 0) {
					a.setVectorStatus("failed");
					a.setProgress(40);
					a.setFailReason(truncateFail(result.problem() != null ? result.problem() : EMBED_CALL_FAILED));
				} else {
					boolean done = pending == 0 && !assetSegs.isEmpty();
					a.setVectorStatus(done ? "success" : "pending");
					a.setProgress(done ? 100 : 40);
					if (done && a.getFailReason() != null) {
						a.setFailReason("");
					}
				}
				a.sanitizeForUpdate();
				assetMapper.update(a);
			}
		}
		Map<String, Object> stats = vectorStats(knowledgeId);
		stats.put("ok", result.failed() == 0);
		stats.put("count", n);
		stats.put("rebuild", rebuild);
		stats.put("vectorAvailable", vectorOn);
		if (!vectorOn) {
			stats.put("message", I18n.translate("未安装向量扩展，已回落关键词检索，未写入向量"));
		} else if (result.failed() > 0) {
			stats.put("message", I18n.translate(result.problem()));
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
		ResolvedContent resolved = resolveContent(asset);
		if (!resolved.ok()) {
			throw new ServiceException(resolved.reason() != null ? StoredSentence.render(resolved.reason())
				: "这份资料还没有正文。请重新上传后再分段");
		}
		AiSegmentOptions options = AiSegmentOptions.lenient(asset.getSegmentType(), asset.getSegmentLength(),
			asset.getSegmentOverlap(), asset.getSegmentSymbol());
		List<String> chunks = chunkWithinLimit(resolved.text(), options);
		// 新分段切出来了再删旧的：正文取不到或段数超限时，原来的分段原样保留
		List<AiKnowledgeSegment> old = segmentMapper.selectListByQuery(
			QueryWrapper.create().eq("asset_id", assetId));
		for (AiKnowledgeSegment s : old) {
			vectorStore.deleteBySegment(s.getId());
			segmentMapper.deleteById(s.getId());
		}
		applyOptions(asset, options);
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
		applyVectorResult(asset, vectorizeSegments(kb, segs));
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
			String content = body.getContent().trim();
			if (content.isEmpty()) {
				throw new ServiceException("分段内容不能为空。不想让这段参与检索，可以把它停用");
			}
			// 早先按段落、按行切出来的分段可能本来就超长：不变长就允许改，只拦继续变长
			int limit = Math.max(AiSegmentOptions.MAX_SEGMENT_CHARS, db.getContent() == null ? 0 : db.getContent().length());
			if (content.length() > limit) {
				throw new ServiceException(I18n.get("分段内容请控制在 {0} 字以内，现在是 {1} 字。内容多的话请拆成几段，或者作为一份新资料上传",
					limit, content.length()));
			}
			db.setContent(content);
			db.setTokenCount(Math.max(1, content.length() / 2));
			db.setVectorStatus("pending");
		}
		db.sanitizeForUpdate();
		segmentMapper.update(db);
		AiKnowledge kb = require(db.getKnowledgeId());
		VectorizeResult result = vectorizeSegments(kb, List.of(db));
		if (result.failed() > 0 && db.getAssetId() != null) {
			// 这一段没向量化成功，所属资料也要如实标出来；成功时不动资料状态（别的分段可能还是失败的）
			AiKnowledgeAsset asset = assetMapper.selectOneById(db.getAssetId());
			if (asset != null) {
				applyVectorResult(asset, result);
				asset.sanitizeForUpdate();
				assetMapper.update(asset);
			}
		}
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

	/** 管理端的命中测试：停用的知识库直接报错，和停用时「检索不会再使用这个知识库」的提示一致 */
	public List<Map<String, Object>> hitTest(Long knowledgeId, String query, int topK) {
		moduleService.requireEnabled();
		AiKnowledge kb = require(knowledgeId);
		if (disabled(kb)) {
			throw new ServiceException("这个知识库已停用，对话和工作流都不会检索它。要测试命中效果，请先启用");
		}
		return search(kb, query, topK);
	}

	/** 对话、工作流运行时的检索：停用的知识库当作没有资料，返回空，不打断对话 */
	public List<Map<String, Object>> retrieve(Long knowledgeId, String query, int topK) {
		moduleService.requireEnabled();
		AiKnowledge kb = require(knowledgeId);
		if (disabled(kb)) {
			return List.of();
		}
		return search(kb, query, topK);
	}

	private static boolean disabled(AiKnowledge kb) {
		return kb.getStatus() != null && kb.getStatus() == AiConstants.STATUS_DISABLE;
	}

	/**
	 * 按知识库 retrievalMode 做 vector / keyword / hybrid(RRF)。
	 * 尊重 minScore；rerankFlag=1 时做轻量词面加权（无外部 rerank 模型）。
	 */
	private List<Map<String, Object>> search(AiKnowledge kb, String query, int topK) {
		Long knowledgeId = kb.getId();
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

		// 向量这条路这次有没有真的走：没装向量扩展、或者向量模型调不通时都算没走
		boolean vectorRan = false;
		if (!"keyword".equals(mode) && vectorStore.isVectorAvailable()) {
			float[] qv = queryVector(kb, query);
			if (qv != null) {
				// 向量路在入库前按 minScore 过滤；hybrid 融合分与余弦分不可比，不再对 RRF 分二次卡阈值
				List<Map<String, Object>> raw = vectorStore.search(knowledgeId, qv, pool, minScore);
				vectorHits = enrichHits(raw);
				vectorRan = true;
			}
		}

		List<Map<String, Object>> hits;
		switch (mode) {
			// 纯向量模式下向量这条路走不了，就用关键词结果，不返回空
			case "vector" -> hits = vectorRan ? vectorHits : keywordHits;
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
		List<String> terms = new ArrayList<>();
		for (String t : tokens.isEmpty() ? List.of(query.trim()) : tokens) {
			String tl = t.toLowerCase(Locale.ROOT);
			if (!tl.isBlank()) {
				terms.add(tl);
			}
		}
		// 稳定排序：同分时 id 小的在前，和一次取全再排序的结果一样
		Comparator<Map<String, Object>> byScoreDesc = Comparator.comparingDouble((Map<String, Object> h) ->
			h.get("score") instanceof Number n ? n.doubleValue() : 0).reversed();
		int keep = Math.max(1, topK);
		List<Map<String, Object>> hits = new ArrayList<>();
		Long afterId = null;
		while (true) {
			// 按 id 一页一页把启用的分段全部看一遍，在内存里按词面覆盖率打分
			// （不用 Flex 的 OR + LIKE：有歧义，整句 LIKE 也命中不了）。
			// 只取最早的 500 段的话，知识库一大，后面的分段永远搜不到
			QueryWrapper pageQuery = QueryWrapper.create().eq("knowledge_id", knowledgeId)
				.eq("enabled", AiConstants.FLAG_YES);
			if (afterId != null) {
				pageQuery.gt("id", afterId);
			}
			List<AiKnowledgeSegment> page = segmentMapper.selectListByQuery(
				pageQuery.orderBy("id", true).limit(KEYWORD_PAGE_SIZE));
			for (AiKnowledgeSegment s : page) {
				Map<String, Object> hit = keywordHit(s, terms);
				if (hit != null) {
					hits.add(hit);
				}
			}
			// 内存里只留分数最高的一小批，知识库再大也不会把所有命中的正文都攒着
			if (hits.size() > keep * 4) {
				hits.sort(byScoreDesc);
				hits = new ArrayList<>(hits.subList(0, keep));
			}
			if (page.size() < KEYWORD_PAGE_SIZE) {
				break;
			}
			afterId = page.get(page.size() - 1).getId();
		}
		hits.sort(byScoreDesc);
		return hits.size() > keep ? new ArrayList<>(hits.subList(0, keep)) : hits;
	}

	/** 一个分段的关键词得分：命中的词越多、出现得越靠前分越高；一个词都没命中返回 null */
	private static Map<String, Object> keywordHit(AiKnowledgeSegment s, List<String> terms) {
		String content = s.getContent() == null ? "" : s.getContent();
		String lower = content.toLowerCase(Locale.ROOT);
		int matched = 0;
		int firstIdx = Integer.MAX_VALUE;
		for (String t : terms) {
			int idx = lower.indexOf(t);
			if (idx >= 0) {
				matched++;
				firstIdx = Math.min(firstIdx, idx);
			}
		}
		if (matched == 0) {
			return null;
		}
		double cover = matched / (double) Math.max(1, terms.size());
		double pos = 1.0 - Math.min(1.0, firstIdx / (double) Math.max(1, lower.length()));
		double score = 0.35 + 0.45 * cover + 0.2 * pos;
		Map<String, Object> m = new HashMap<>();
		m.put("segmentId", s.getId());
		m.put("assetId", s.getAssetId());
		m.put("content", content);
		m.put("score", Math.round(score * 1_000_000d) / 1_000_000d);
		m.put("source", "keyword");
		return m;
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

	/**
	 * 给分段写向量。配了向量模型却调不通时，分段标成 failed、旧向量删掉，不拿本地哈希向量顶替：
	 * 哈希向量和模型算的向量不在一个空间里，混在一起检索结果基本是随机的，界面上还显示成功。
	 */
	private VectorizeResult vectorizeSegments(AiKnowledge kb, List<AiKnowledgeSegment> segs) {
		if (segs == null || segs.isEmpty()) {
			return VectorizeResult.NONE;
		}
		if (!vectorStore.isVectorAvailable()) {
			for (AiKnowledgeSegment seg : segs) {
				seg.setVectorStatus("keyword");
				seg.sanitizeForUpdate();
				segmentMapper.update(seg);
			}
			return VectorizeResult.NONE;
		}
		EmbeddingChoice emb = chooseEmbedding(kb);
		String problem = emb.problem();
		int ok = 0;
		int failed = 0;
		int batchSize = 8;
		for (int from = 0; from < segs.size(); from += batchSize) {
			List<AiKnowledgeSegment> batch = segs.subList(from, Math.min(segs.size(), from + batchSize));
			// 已经失败过就不再调模型：同一个原因会让后面每一批都失败，一批一批等超时没有意义
			if (problem == null) {
				problem = flushEmbedBatch(kb, emb.model(), batch);
			}
			if (problem == null) {
				ok += batch.size();
			} else {
				markFailed(batch);
				failed += batch.size();
			}
		}
		return new VectorizeResult(ok, failed, problem);
	}

	/** 成功返回 null；失败返回给用户看的原因。模型返回的原始报错只进日志，不给用户看 */
	private String flushEmbedBatch(AiKnowledge kb, AiModel embModel, List<AiKnowledgeSegment> segs) {
		List<String> texts = new ArrayList<>(segs.size());
		for (AiKnowledgeSegment seg : segs) {
			texts.add(seg.getContent());
		}
		List<float[]> vectors;
		if (embModel == null) {
			// 整个系统一个向量模型都没配：入库和查询两边都用本地哈希，是一致的
			vectors = new ArrayList<>(texts.size());
			for (String t : texts) {
				vectors.add(AiKbVectorStore.localHashEmbed(t));
			}
		} else {
			try {
				vectors = llmClient.embedBatch(embModel, texts);
			} catch (Exception e) {
				log.warn("[ai-kb] embedding failed: kb={} model={} segments={} cause={}",
					kb.getId(), embModel.getId(), segs.size(), briefCause(e));
				return EMBED_CALL_FAILED;
			}
			if (!sameCount(vectors, segs.size())) {
				log.warn("[ai-kb] embedding count mismatch: kb={} model={} segments={} vectors={}",
					kb.getId(), embModel.getId(), segs.size(), vectors == null ? 0 : vectors.size());
				return EMBED_COUNT_MISMATCH;
			}
		}
		for (int i = 0; i < segs.size(); i++) {
			AiKnowledgeSegment seg = segs.get(i);
			String preview = seg.getContent() == null ? "" :
				(seg.getContent().length() > 200 ? seg.getContent().substring(0, 200) : seg.getContent());
			vectorStore.upsert(kb.getId(), seg.getId(), seg.getAssetId(), vectors.get(i), preview);
			seg.setVectorStatus("success");
			seg.setEmbeddingId(String.valueOf(seg.getId()));
			seg.sanitizeForUpdate();
			segmentMapper.update(seg);
		}
		return null;
	}

	private static boolean sameCount(List<float[]> vectors, int expected) {
		if (vectors == null || vectors.size() != expected) {
			return false;
		}
		for (float[] v : vectors) {
			if (v == null || v.length == 0) {
				return false;
			}
		}
		return true;
	}

	private void markFailed(List<AiKnowledgeSegment> segs) {
		for (AiKnowledgeSegment seg : segs) {
			// 留着的旧向量可能是换模型之前算的，和以后的查询向量对不上，一并删掉
			vectorStore.deleteBySegment(seg.getId());
			seg.setVectorStatus("failed");
			seg.sanitizeForUpdate();
			segmentMapper.update(seg);
		}
	}

	/**
	 * 查询用的向量，要和入库时来自同一个模型。配了向量模型却调不通时返回 null（这次只走关键词），
	 * 不拿本地哈希向量去比模型算出来的向量。
	 */
	private float[] queryVector(AiKnowledge kb, String text) {
		EmbeddingChoice emb = chooseEmbedding(kb);
		if (emb.problem() != null) {
			log.warn("[ai-kb] query embedding skipped, embedding model is gone: kb={} model={}",
				kb.getId(), kb.getEmbeddingModelId());
			return null;
		}
		if (emb.model() == null) {
			return AiKbVectorStore.localHashEmbed(text);
		}
		try {
			float[] v = llmClient.embed(emb.model(), text);
			if (v != null && v.length > 0) {
				return v;
			}
			log.warn("[ai-kb] query embedding is empty: kb={} model={}", kb.getId(), emb.model().getId());
		} catch (Exception e) {
			log.warn("[ai-kb] query embedding failed: kb={} model={} cause={}",
				kb.getId(), emb.model().getId(), briefCause(e));
		}
		return null;
	}

	/**
	 * 知识库用哪个向量模型：知识库自己选的优先，没选就用系统默认的。
	 * 整个系统一个向量模型都没配时 model 为空，入库和查询都用本地哈希；
	 * 知识库选的模型已经被删掉时给出 problem，不悄悄换成本地哈希。
	 */
	private EmbeddingChoice chooseEmbedding(AiKnowledge kb) {
		if (kb.getEmbeddingModelId() != null) {
			try {
				return new EmbeddingChoice(modelBizService.requireRaw(kb.getEmbeddingModelId()), null);
			} catch (ServiceException e) {
				return new EmbeddingChoice(null, EMBED_MODEL_MISSING);
			}
		}
		try {
			return new EmbeddingChoice(modelBizService.requireDefaultEmbedding(), null);
		} catch (ServiceException e) {
			return new EmbeddingChoice(null, null);
		}
	}

	/** 写日志用的失败原因：压成一行、去掉网址里问号后面的部分（有的厂商把密钥放在那里）、限长 */
	private static String briefCause(Throwable e) {
		String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
		msg = msg.replaceAll("[\\r\\n]+", " ").replaceAll("\\?[^\\s\"']*", "?...");
		return msg.length() > 300 ? msg.substring(0, 300) : msg;
	}

	/**
	 * 解析资料正文：{@code contentText} 优先；否则 http(s) {@code fileUrl} 下载字节后走 Tika。
	 * 不再把二进制当 UTF-8 整串读，也不再用文件名冒充正文。
	 */
	private ResolvedContent resolveContent(AiKnowledgeAsset body) {
		if (body.getContentText() != null && !body.getContentText().isBlank()) {
			return ResolvedContent.ok(body.getContentText());
		}
		if (body.getFileUrl() != null && !body.getFileUrl().isBlank()) {
			String url = body.getFileUrl().trim();
			if (!(url.startsWith("http://") || url.startsWith("https://"))) {
				return ResolvedContent.fail("文件地址请用 http 或 https 开头的链接");
			}
			String nameHint = body.getFileName();
			if (nameHint == null || nameHint.isBlank()) {
				nameHint = url;
			}
			try {
				AiKbDocumentTypes.requireExt(nameHint);
			} catch (ServiceException ex) {
				// URL 路径无白名单后缀时，仍允许后续靠 Content-Type / 魔数；先记下
			}
			final String guarded;
			try {
				guarded = outbound.guard(url);
			} catch (ServiceException ex) {
				return ResolvedContent.fail(ex.getMessage());
			}
			byte[] bytes;
			try {
				bytes = outboundWebClients.create().get().uri(guarded)
					.retrieve().bodyToMono(byte[].class).block(Duration.ofSeconds(30));
			} catch (Exception e) {
				return ResolvedContent.fail("打不开这个文件地址。请检查链接是否可访问，或改为直接上传文件");
			}
			if (bytes == null || bytes.length == 0) {
				return ResolvedContent.fail("文件地址没有返回内容。请换一个链接或直接上传文件");
			}
			long max = kbProperties.getMaxUploadBytes();
			if (bytes.length > max) {
				return ResolvedContent.fail(StoredSentence.pack("文件超过 {0}MB。请压缩或拆分后再上传", max / (1024 * 1024)));
			}
			String filename = body.getFileName();
			if (filename == null || filename.isBlank()) {
				filename = AiKbDocumentTypes.extOf(url).isEmpty() ? "remote.bin" : ("remote." + AiKbDocumentTypes.extOf(url));
			}
			String ext;
			try {
				ext = AiKbDocumentTypes.validate(filename, null, bytes, Math.min(1024, bytes.length));
			} catch (ServiceException ex) {
				// URL 文件名不可靠时，尝试用魔数猜扩展名
				ext = guessExtByMagic(bytes);
				if (ext == null) {
					return ResolvedContent.fail(ex.getMessage());
				}
				if (body.getFileName() == null || body.getFileName().isBlank()) {
					body.setFileName("remote." + ext);
				}
				body.setFileType(ext);
			}
			body.setFileSize((long) bytes.length);
			if (body.getFileType() == null || body.getFileType().isBlank()) {
				body.setFileType(ext);
			}
			String[] reason = new String[1];
			String text = documentTextExtractor.extractOrNull(bytes, body.getFileName(),
				AiKbDocumentTypes.mimeOf(ext), reason);
			if (text == null || text.isBlank()) {
				return ResolvedContent.fail(reason[0] != null ? reason[0]
					: "没从文件里抽出正文。可能是扫描件或空文档，当前不支持 OCR");
			}
			return ResolvedContent.ok(text);
		}
		return ResolvedContent.fail("请先贴上正文，或上传 PDF/DOCX/PPTX，或填写能打开的文件地址");
	}

	private static String guessExtByMagic(byte[] bytes) {
		int n = Math.min(1024, bytes == null ? 0 : bytes.length);
		if (AiKbDocumentTypes.magicMatches("pdf", bytes, n)) {
			return "pdf";
		}
		if (AiKbDocumentTypes.magicMatches("docx", bytes, n)) {
			// zip：无法区分 docx/pptx，拒绝猜测以免错解析
			return null;
		}
		return null;
	}

	/**
	 * reason 会存进资料的失败原因里，所以放原句：固定的句子直接写，带数字的用 {@link StoredSentence#pack}；
	 * 要给人看的时候再用 {@link StoredSentence#render} 按读的人的语言拼出来
	 */
	private record ResolvedContent(String text, String reason) {
		static ResolvedContent ok(String text) {
			return new ResolvedContent(text, null);
		}

		static ResolvedContent fail(String reason) {
			return new ResolvedContent(null, reason);
		}

		boolean ok() {
			return text != null && !text.isBlank();
		}
	}

	/** 把校验过的分段设置写回资料行，库里存的就是实际用的值 */
	private static void applyOptions(AiKnowledgeAsset asset, AiSegmentOptions options) {
		asset.setSegmentType(options.type());
		asset.setSegmentLength(options.length());
		asset.setSegmentOverlap(options.overlap());
		asset.setSegmentSymbol(options.symbol());
	}

	/** 按分段设置切段；段数超过 {@code mugsun.ai.kb.max-segments-per-asset} 时拒绝，切到上限就停 */
	private List<String> chunkWithinLimit(String text, AiSegmentOptions options) {
		int max = kbProperties.getMaxSegmentsPerAsset();
		try {
			return AiTextSplitter.split(text, options.length(), options.overlap(), options.type(), options.symbol(), max);
		} catch (AiTextSplitter.TooManyPiecesException e) {
			throw new ServiceException(I18n.get("这份资料按当前设置会切出超过 {0} 段。请调大分段长度、换一种分段方式，或把资料拆成几份再上传", max));
		}
	}

	/** 直接贴正文和传文件用同一个大小上限（按 UTF-8 字节数），不然不走文件就能绕过上限 */
	private void requirePastedTextWithinLimit(String text) {
		if (text == null) {
			return;
		}
		long max = kbProperties.getMaxUploadBytes();
		long bytes = 0;
		for (int i = 0; i < text.length() && bytes <= max; i++) {
			char c = text.charAt(i);
			// 代理对的两个 char 合起来是 4 字节，各算 2
			bytes += c < 0x80 ? 1 : (c < 0x800 || Character.isSurrogate(c) ? 2 : 3);
		}
		if (bytes > max) {
			throw new ServiceException(I18n.get("正文超过 {0}MB。请拆成几份再上传", (max / (1024 * 1024))));
		}
	}

	private void requireUniqueName(String name, Long excludeId) {
		QueryWrapper q = QueryWrapper.create().eq("name", name);
		if (excludeId != null) {
			q.ne("id", excludeId);
		}
		if (knowledgeMapper.selectCountByQuery(q) > 0) {
			throw new ServiceException(I18n.get("已经有叫「{0}」的知识库。请换一个名称，或打开原来那个", name));
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
