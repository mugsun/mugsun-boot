package com.mugsun.boot.track.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.mugsun.boot.common.constant.TrackConstants;
import com.mugsun.boot.track.TrackSourcemapService;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * sourcemap 管理 API（/system/track/sourcemap/**，G101）：上传/分页/删除/原文读取。
 * <p>鉴权：类级 @SaCheckLogin；upload/page/remove 走应用编辑码（{@link TrackConstants#PERM_APP_EDIT}），
 * raw 走错误监控查询码（{@link TrackConstants#PERM_ERROR_LIST}）。
 * <p><b>分层纪律</b>：权限校验（StpUtil，业务库）必须留在本层；DB/存储访问全部委托
 * {@link TrackSourcemapService}（@TrackDS 路由埋点库）——在 @TrackDS 切面范围内调用权限校验会把业务库查询
 * 误路由到埋点库；上传操作人 id 亦在本层解析后传入服务层。
 */
@RestController
@RequestMapping("/system/track/sourcemap")
@SaCheckLogin
public class TrackSourcemapController {

	private final TrackSourcemapService sourcemapService;

	public TrackSourcemapController(TrackSourcemapService sourcemapService) {
		this.sourcemapService = sourcemapService;
	}

	/**
	 * 上传 sourcemap：multipart 表单（file 文件 + appKey + release 文本字段）。
	 * 校验链（后缀/大小/JSON 含 mappings/应用归属）全过 → 私有存储落盘 → upsert 元数据；
	 * 响应为投影 {id, appKey, release, filename, sizeBytes, tenantId, createBy, createTime, updateTime}（不含存储坐标）。
	 */
	@PostMapping("/upload")
	@SaCheckPermission(TrackConstants.PERM_APP_EDIT)
	public R<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
										 @RequestParam String appKey,
										 @RequestParam String release) {
		return R.data(sourcemapService.upload(file, appKey, release, StpUtil.getLoginIdAsLong()));
	}

	/** 分页列表：appKey 必填、release 精确可选；records 同 upload 投影（不下发 storage_key 绝对路径） */
	@GetMapping("/page")
	@SaCheckPermission(TrackConstants.PERM_APP_EDIT)
	public R<Page<Map<String, Object>>> page(@RequestParam String appKey,
											 @RequestParam(required = false) String release,
											 @RequestParam(defaultValue = "1") long pageNum,
											 @RequestParam(defaultValue = "10") long pageSize) {
		return R.data(sourcemapService.page(appKey, release, pageNum, pageSize));
	}

	/** 删除：{id} 删对象 + 逻辑删行（跨租户命中「不存在」） */
	@PostMapping("/remove")
	@SaCheckPermission(TrackConstants.PERM_APP_EDIT)
	public R<Void> remove(@RequestBody Map<String, Object> body) {
		Long id = body.get("id") == null ? null : Long.valueOf(body.get("id").toString());
		if (id == null) {
			throw new ServiceException("缺少 id");
		}
		sourcemapService.remove(id);
		return R.success("已删除");
	}

	/**
	 * 读取 .map 原文：application/json 直发字节（非 R 信封），供前端 source-map-js 解析还原堆栈。
	 * <p><b>必须鉴权</b>：sourcemap 内含 sourcesContent 源码明文，等同源码资产——走错误监控查询码
	 * （{@link TrackConstants#PERM_ERROR_LIST}）+ 租户行级隔离，切勿改为仅登录或公开端点。
	 */
	@GetMapping("/raw")
	@SaCheckPermission(TrackConstants.PERM_ERROR_LIST)
	public void raw(@RequestParam Long id, HttpServletResponse response) throws IOException {
		byte[] json = sourcemapService.raw(id);
		response.setContentType(TrackConstants.SOURCEMAP_CONTENT_TYPE + ";charset=UTF-8");
		response.setContentLength(json.length);
		response.getOutputStream().write(json);
	}
}
