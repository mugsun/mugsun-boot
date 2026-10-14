package com.mugsun.boot.system.service;

import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.system.entity.SysOss;
import com.mugsun.boot.system.mapper.SysOssMapper;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.query.QueryWrapper;
import org.dromara.x.file.storage.core.FileStorageProperties;
import org.dromara.x.file.storage.core.FileStorageService;
import org.dromara.x.file.storage.core.platform.AliyunOssFileStorage;
import org.dromara.x.file.storage.core.platform.AliyunOssFileStorageClientFactory;
import org.dromara.x.file.storage.core.platform.FileStorage;
import org.dromara.x.file.storage.core.platform.LocalPlusFileStorage;
import org.dromara.x.file.storage.core.platform.MinioFileStorage;
import org.dromara.x.file.storage.core.platform.MinioFileStorageClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 对象存储配置解析：按启用配置动态注册并选用 x-file-storage 存储平台，实现渠道运行时切换。
 * <p>category 固定键（{@link OssConstants}）映射 Java 配置类，禁类名入库；未知类别/缺必填项 fail-fast。
 * <p>热更新：volatile 配置指纹（行全字段拼接）+ synchronized 双检重建（同 MailNotifyChannel 范式），
 * 切主（enable 互斥）或编辑启用行后下一次取用即生效，无需重启。
 */
@Service
public class OssService {

	private static final Logger log = LoggerFactory.getLogger(OssService.class);

	private final FileStorageService fileStorageService;
	private final SysOssMapper ossMapper;

	/** 已注册启用配置指纹 + 平台名（指纹一致直接复用，变更时双检重建） */
	private volatile String fingerprint;
	private volatile String platform;

	public OssService(FileStorageService fileStorageService, SysOssMapper ossMapper) {
		this.fileStorageService = fileStorageService;
		this.ossMapper = ossMapper;
	}

	/** 不用 synchronized：锁内有阻塞 IO，虚拟线程在 synchronized 上会钉住载体线程（JDK 21） */
	private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();

	/** 当前启用的存储平台名；无启用配置时回退 yaml 默认平台 */
	public String activePlatform() {
		SysOss oss = ossMapper.selectOneByQuery(
			QueryWrapper.create().eq("status", OssConstants.STATUS_ENABLE).orderBy("id", false));
		if (oss == null) {
			return fileStorageService.getDefaultPlatform();
		}
		String fp = fingerprintOf(oss);
		if (!fp.equals(fingerprint)) {
			lock.lock();
			try {
				if (!fp.equals(fingerprint)) {
					register(oss);
					this.platform = oss.getOssCode();
					this.fingerprint = fp;
					log.info("对象存储平台已按启用配置重建 platform={}, category={}", oss.getOssCode(), oss.getCategory());
				}
			} finally {
				lock.unlock();
			}
		}
		return platform;
	}

	/** 存储平台是否仍注册在案（平台配置被删/下线的历史附件据此标记不可下载，前端预览据此跳过） */
	public boolean platformRegistered(String platform) {
		if (platform == null || platform.isBlank()) {
			return false;
		}
		if (fileStorageService.getDefaultPlatform().equals(platform)) {
			return true;
		}
		return fileStorageService.getFileStorageList().stream()
			.anyMatch(fs -> platform.equals(fs.getPlatform()));
	}

	/**
	 * 平台所属类别（local/minio/aliyun）：DB 注册平台查 sys_oss 行；
	 * yaml 默认平台及配置行已删的历史平台按 local 处理（本地下载语义兜底）。
	 */
	public String categoryOf(String platform) {
		if (platform == null || fileStorageService.getDefaultPlatform().equals(platform)) {
			return OssConstants.CATEGORY_LOCAL;
		}
		SysOss oss = ossMapper.selectOneByQuery(
			QueryWrapper.create().eq("oss_code", platform).orderBy("id", false));
		return oss == null ? OssConstants.CATEGORY_LOCAL : oss.getCategory();
	}

	/**
	 * 按平台 url 组成规则预算对象访问 url（与 x-file-storage 平台 save 后的 FileInfo.url 一致：
	 * domain + basePath + path + filename；domain 空时退化为对象键）。
	 * DB 注册平台 basePath 固定空串，与注册逻辑保持一致。
	 */
	public String predictUrl(String platform, String path, String filename) {
		SysOss oss = ossMapper.selectOneByQuery(
			QueryWrapper.create().eq("oss_code", platform).orderBy("id", false));
		String domain = oss == null || oss.getDomain() == null ? "" : oss.getDomain();
		return domain + path + filename;
	}

	/** 配置指纹：启用行全字段拼接，任一字段变更即触发重建（update_time 秒级精度不足以覆盖同秒连改，故取全字段） */
	private String fingerprintOf(SysOss oss) {
		return String.join("|", String.valueOf(oss.getId()), n(oss.getOssCode()), n(oss.getCategory()),
			n(oss.getEndpoint()), n(oss.getAccessKey()), n(oss.getSecretKey()), n(oss.getBucketName()),
			n(oss.getDomain()), n(oss.getStoragePath()));
	}

	private String n(String v) {
		return v == null ? "" : v;
	}

	/** 按启用配置重建存储平台：先关闭并移除同名旧注册再追加，确保配置变更即时生效、client 不泄漏 */
	private void register(SysOss oss) {
		List<FileStorage> list = fileStorageService.getFileStorageList();
		list.removeIf(fs -> {
			if (oss.getOssCode().equals(fs.getPlatform())) {
				try {
					fs.close();
				} catch (Exception e) {
					log.warn("关闭旧存储平台实例失败 platform={}: {}", oss.getOssCode(), e.getMessage());
				}
				return true;
			}
			return false;
		});
		list.add(buildStorage(oss));
	}

	/** 按 category 固定键构建存储平台（禁类名入库：库表只存类别键，Java 类映射收敛于此） */
	private FileStorage buildStorage(SysOss oss) {
		switch (oss.getCategory() == null ? "" : oss.getCategory()) {
			case OssConstants.CATEGORY_LOCAL:
				return buildLocal(oss);
			case OssConstants.CATEGORY_MINIO:
				return buildMinio(oss);
			case OssConstants.CATEGORY_ALIYUN:
				return buildAliyun(oss);
			default:
				throw new ServiceException("请选择本地存储、MinIO 或阿里云");
		}
	}

	/**
	 * 检查这一套配置能不能用：本地试写一个文件再删掉；远程只确认地址能连上，不把连接原文返回给页面。
	 */
	public String probe(SysOss oss) {
		if (oss == null) {
			throw new ServiceException("请从列表里选择一套存储后再检查");
		}
		if (OssConstants.CATEGORY_LOCAL.equals(oss.getCategory())) {
			return probeLocal(oss);
		}
		buildStorage(oss);
		String endpoint = oss.getEndpoint() == null ? "" : oss.getEndpoint().trim();
		String url = endpoint.startsWith("http://") || endpoint.startsWith("https://") ? endpoint : "http://" + endpoint;
		try {
			URI uri = URI.create(url);
			String host = uri.getHost();
			int port = uri.getPort();
			if (host == null || host.isBlank()) {
				throw new ServiceException("服务地址看不懂。请写成 http://主机:端口 这样的形式后再检查");
			}
			if (port < 0) {
				port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
			}
			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress(host, port), 2000);
			}
		} catch (ServiceException ex) {
			throw ex;
		} catch (RuntimeException | java.io.IOException ex) {
			log.warn("对象存储检查没有连上 category={}", oss.getCategory());
			throw new ServiceException("这套存储暂时连不上。请检查地址、桶名和密钥是否正确后再试");
		}
		return "地址可以连上。启用后，新上传的文件会存到这套存储";
	}

	private String probeLocal(SysOss oss) {
		if (oss.getStoragePath() == null || oss.getStoragePath().isBlank()) {
			throw new ServiceException("本地存储请填写目录。请先在表单里填好本机目录再检查");
		}
		Path dir = Path.of(oss.getStoragePath().trim());
		if (!Files.isDirectory(dir)) {
			throw new ServiceException("这个目录还不存在，或不是文件夹。请先在服务器上建好目录后再检查");
		}
		if (!Files.isWritable(dir)) {
			throw new ServiceException("应用写不了这个目录。请检查目录权限后再试");
		}
		Path file = dir.resolve("mugsun-probe-" + System.nanoTime() + ".txt");
		try {
			Files.writeString(file, "ok");
			Files.deleteIfExists(file);
		} catch (java.io.IOException ex) {
			try {
				Files.deleteIfExists(file);
			} catch (java.io.IOException ignored) {
				log.warn("清理试写文件失败");
			}
			throw new ServiceException("试写文件没有成功。请检查目录权限后再试");
		}
		return "这个目录可以写入。启用后，新上传的文件会存到这里";
	}

	private FileStorage buildLocal(SysOss oss) {
		require(oss.getStoragePath(), "本地存储请填写目录。请先在表单里填好本机目录再保存");
		FileStorageProperties.LocalPlusConfig config = new FileStorageProperties.LocalPlusConfig();
		config.setPlatform(oss.getOssCode());
		config.setStoragePath(oss.getStoragePath());
		config.setBasePath("");
		config.setDomain(oss.getDomain() == null ? "" : oss.getDomain());
		return new LocalPlusFileStorage(config);
	}

	private FileStorage buildMinio(SysOss oss) {
		require(oss.getEndpoint(), "MinIO 请填写服务地址。写成 http://主机:端口 后再保存");
		require(oss.getBucketName(), "MinIO 请填写桶名后再保存");
		require(oss.getAccessKey(), "MinIO 请填写 AccessKey 后再保存");
		require(oss.getSecretKey(), "MinIO 请填写 SecretKey 后再保存");
		FileStorageProperties.MinioConfig config = new FileStorageProperties.MinioConfig();
		config.setPlatform(oss.getOssCode());
		config.setEndPoint(oss.getEndpoint());
		config.setAccessKey(oss.getAccessKey());
		config.setSecretKey(oss.getSecretKey());
		config.setBucketName(oss.getBucketName());
		config.setBasePath("");
		// 平台 url = domain + basePath + path + filename 直接拼接，domain 必须空串兜底（null 会拼出 "null" 前缀）
		config.setDomain(oss.getDomain() == null ? "" : oss.getDomain());
		return new MinioFileStorage(config, new MinioFileStorageClientFactory(config));
	}

	private FileStorage buildAliyun(SysOss oss) {
		require(oss.getEndpoint(), "阿里云请填写服务地址后再保存");
		require(oss.getBucketName(), "阿里云请填写桶名后再保存");
		require(oss.getAccessKey(), "阿里云请填写 AccessKey 后再保存");
		require(oss.getSecretKey(), "阿里云请填写 SecretKey 后再保存");
		FileStorageProperties.AliyunOssConfig config = new FileStorageProperties.AliyunOssConfig();
		config.setPlatform(oss.getOssCode());
		config.setEndPoint(oss.getEndpoint());
		config.setAccessKey(oss.getAccessKey());
		config.setSecretKey(oss.getSecretKey());
		config.setBucketName(oss.getBucketName());
		config.setBasePath("");
		// 同 MinIO：domain 空串兜底，防 url 拼接出现 "null" 前缀
		config.setDomain(oss.getDomain() == null ? "" : oss.getDomain());
		return new AliyunOssFileStorage(config, new AliyunOssFileStorageClientFactory(config));
	}

	private void require(String value, String message) {
		if (value == null || value.isBlank()) {
			throw new ServiceException(message);
		}
	}
}
