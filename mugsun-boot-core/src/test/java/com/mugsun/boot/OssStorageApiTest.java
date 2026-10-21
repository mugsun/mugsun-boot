package com.mugsun.boot;

import com.fasterxml.jackson.databind.JsonNode;
import com.mugsun.boot.common.constant.OssConstants;
import com.mugsun.boot.common.crypto.Sm4Util;
import com.mugsun.boot.system.entity.SysOss;
import com.mugsun.boot.system.mapper.SysOssMapper;
import com.mugsun.boot.tenant.TenantContext;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.dromara.x.file.storage.core.recorder.FileRecorder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G89 对象存储多云集成测试：MinIO Testcontainer 端到端——
 * 配置入库（AK/SK 密文）→ 切主热更 → multipart 上传/下载/物删闭环（FileRecorder）→
 * 两段式直传（presigned-put → 绕过服务端 PUT → create 回填）→ 私有预签名下载 → 切回本地。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OssStorageApiTest extends AbstractIntegrationTest {

	private static final String MINIO_ROOT_USER = "mugsun-it";
	private static final String MINIO_ROOT_PASSWORD = "mugsun-it-secret";
	private static final String BUCKET = "mugsun-it";
	private static final String MINIO_OSS_CODE = "minio-it";
	private static final String LOCAL_OSS_CODE = "local-it";
	/** 本地平台存储目录（绝对路径：LocalPlus 相对路径解析基准与测试 JVM 不一致，绝对路径消歧） */
	private static final String LOCAL_STORAGE_PATH =
		new java.io.File("target/it-files-oss").getAbsolutePath() + "/";

	/** MinIO 官方镜像已从 Docker Hub/Quay 下架，改用可匿名拉取的 pgsty/silo（API 兼容）。 */
	private static final GenericContainer<?> MINIO = new GenericContainer<>(
		DockerImageName.parse("pgsty/silo:RELEASE.2026-09-16T00-00-00Z"))
		.withExposedPorts(9000)
		.withEnv("MINIO_ROOT_USER", MINIO_ROOT_USER)
		.withEnv("MINIO_ROOT_PASSWORD", MINIO_ROOT_PASSWORD)
		.withCommand("server", "/data");

	static {
		MINIO.start();
	}

	private static String minioEndpoint;
	private static MinioClient minioClient;

	@Autowired
	private org.springframework.data.redis.core.StringRedisTemplate redis;

	@Autowired
	private SysOssMapper ossMapper;
	@Autowired
	private FileRecorder fileRecorder;
	@Autowired
	private DataSource dataSource;

	@BeforeAll
	static void initMinio() throws Exception {
		assertThat(MINIO.isRunning()).as("MinIO 容器已启动").isTrue();
		minioEndpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
		minioClient = MinioClient.builder().endpoint(minioEndpoint)
			.credentials(MINIO_ROOT_USER, MINIO_ROOT_PASSWORD).build();
		try {
			minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
		} catch (ErrorResponseException e) {
			// 桶已存在（容器复用场景）放行
			assertThat(e.errorResponse().code()).isIn("BucketAlreadyOwnedByYou", "BucketAlreadyExists");
		}
	}

	/** 类级清理：停用全部 DB 存储配置，回退 yaml 默认本地平台，不污染共享上下文的其他测试类 */
	@AfterAll
	void disableAllConfigs() {
		// 测试线程无会话租户，直连 mapper 须显式忽略租户（fail-closed 约定）
		TenantContext.ignore(() -> ossMapper.selectAll().forEach(o -> {
			if (Integer.valueOf(OssConstants.STATUS_ENABLE).equals(o.getStatus())) {
				o.setStatus(OssConstants.STATUS_DISABLE);
				ossMapper.update(o);
			}
		}));
	}

	@Test
	@Order(1)
	void minioMultipartUploadLifecycle() throws Exception {
		String token = loginAdmin();
		enableMinioConfig(token);

		byte[] content = "minio-multipart-端到端字节".getBytes(StandardCharsets.UTF_8);
		JsonNode data = upload(token, content, "hello-minio.txt", OssConstants.ACCESS_PUBLIC);
		assertThat(data.path("platform").asText()).isEqualTo(MINIO_OSS_CODE);
		String objectKey = data.path("path").asText() + data.path("filename").asText();
		// MinIO 桶内对象真实存在（domain 未配，url 退化为对象键，与平台 url 组成规则一致）
		assertThat(data.path("url").asText()).isEqualTo(objectKey);
		minioClient.statObject(StatObjectArgs.builder().bucket(BUCKET).object(objectKey).build());

		// download-stream 字节一致
		long id = data.path("id").asLong();
		ResponseEntity<byte[]> down = rest.exchange("/system/file/download-stream/" + id, HttpMethod.GET,
			new HttpEntity<>(authHeaders(token)), byte[].class);
		assertThat(down.getStatusCode().value()).isEqualTo(200);
		assertThat(down.getBody()).isEqualTo(content);

		// remove → 桶内对象消失 + FileRecorder 登记销户（物删闭环）
		ResponseEntity<String> rm = post("/system/file/remove", List.of(id), token);
		assertThat(readBody(rm).path("code").asInt()).as("删除回执：" + rm.getBody()).isEqualTo(200);
		assertThatThrownBy(() ->
			minioClient.statObject(StatObjectArgs.builder().bucket(BUCKET).object(objectKey).build()))
			.isInstanceOfSatisfying(ErrorResponseException.class,
				e -> assertThat(e.errorResponse().code()).isEqualTo("NoSuchKey"));
		// 测试线程无会话租户，直连 mapper（recorder 内部）须显式忽略租户（fail-closed 约定）
		assertThat(TenantContext.ignore(() -> fileRecorder.getByUrl(objectKey)))
			.as("FileRecorder 登记已级联销户").isNull();
	}

	@Test
	@Order(2)
	void presignedPutDirectUpload() throws Exception {
		String token = loginAdmin();
		byte[] content = "直传字节-绕过后端服务端".getBytes(StandardCharsets.UTF_8);

		// 1) 签发
		Map<String, Object> signReq = new HashMap<>();
		signReq.put("filename", "direct.txt");
		signReq.put("size", content.length);
		signReq.put("access", OssConstants.ACCESS_PRIVATE);
		JsonNode sign = readBody(post("/system/file/presigned-put", signReq, token));
		assertThat(sign.path("code").asInt()).isEqualTo(200);
		JsonNode signData = sign.path("data");
		assertThat(signData.path("supported").asBoolean()).as("MinIO 支持预签名").isTrue();
		String uploadUrl = signData.path("uploadUrl").asText();
		String ticket = signData.path("ticket").asText();
		assertThat(uploadUrl).isNotBlank();
		assertThat(ticket).isNotBlank();

		// 2) 直传：字节不经过后端，直接 PUT 到 MinIO
		putBytes(uploadUrl, signData.path("headers"), content);

		// 3) 回填登记（size 客户端上报展示值）
		Map<String, Object> createReq = new HashMap<>();
		createReq.put("ticket", ticket);
		createReq.put("size", content.length);
		JsonNode created = readBody(post("/system/file/create", createReq, token));
		assertThat(created.path("code").asInt()).as("create 回填：" + created).isEqualTo(200);
		JsonNode attach = created.path("data");
		assertThat(attach.path("id").asLong()).isPositive();
		// 私有附件响应不暴露 url
		assertThat(attach.path("url").isNull()).isTrue();
		String objectKey = attach.path("path").asText() + attach.path("filename").asText();
		// MinIO 桶内字节与直传内容一致
		byte[] stored = minioClient.getObject(
			GetObjectArgs.builder().bucket(BUCKET).object(objectKey).build()).readAllBytes();
		assertThat(stored).isEqualTo(content);
		assertThat(objectKey).as("登记的是服务端新 key，不是签发给客户端的 key").isNotEqualTo(objectKeyOf(uploadUrl));
		assertThat(objectKeyOf(uploadUrl)).as("直传只签到暂存前缀").contains(OssConstants.PATH_STAGING)
			.doesNotContain(OssConstants.PATH_PUBLIC);
		assertThatThrownBy(() -> minioClient.statObject(StatObjectArgs.builder().bucket(BUCKET).object(objectKeyOf(uploadUrl)).build()))
			.as("登记成功后暂存 key 已删").isInstanceOf(ErrorResponseException.class);
		io.minio.StatObjectResponse st = minioClient.statObject(StatObjectArgs.builder().bucket(BUCKET).object(objectKey).build());
		assertThat(st.headers().get("Content-Disposition")).as("复制后仍是 attachment").isEqualTo("attachment");
		String stagedName = objectKeyOf(uploadUrl).substring(objectKeyOf(uploadUrl).lastIndexOf('/') + 1);
		assertThat(redis.opsForList().range(com.mugsun.boot.system.file.PendingObjectCleaner.QUEUE_KEY, 0, -1))
			.as("签名过期后再删一次暂存 key 的延迟项")
			.anyMatch(j -> j.contains(stagedName) && j.contains("notBefore"));

		// 2b) 预签名 URL 还没过期：用它再 PUT 一份等长的篡改内容，已登记的对象不受影响
		byte[] tampered = content.clone();
		java.util.Arrays.fill(tampered, (byte) 'X');
		putBytes(uploadUrl, signData.path("headers"), tampered);
		byte[] after = minioClient.getObject(
			GetObjectArgs.builder().bucket(BUCKET).object(objectKey).build()).readAllBytes();
		assertThat(after).as("登记对象内容不变").isEqualTo(content);
		minioClient.removeObject(io.minio.RemoveObjectArgs.builder().bucket(BUCKET).object(objectKeyOf(uploadUrl)).build());

		// 4) 伪造未签发凭证 create 被拒；重放已消费凭证同样被拒（一次性）
		Map<String, Object> forged = new HashMap<>();
		forged.put("ticket", "forged-ticket-never-signed");
		JsonNode forgedResp = readBody(post("/system/file/create", forged, token));
		assertThat(forgedResp.path("code").asInt()).as("伪造凭证应被拒").isNotEqualTo(200);
		JsonNode replayResp = readBody(post("/system/file/create", createReq, token));
		assertThat(replayResp.path("code").asInt()).as("重放凭证应被拒").isNotEqualTo(200);

		// 清理登记与对象
		ResponseEntity<String> rm = post("/system/file/remove", List.of(attach.path("id").asLong()), token);
		assertThat(readBody(rm).path("code").asInt()).isEqualTo(200);
	}

	@Test
	@Order(3)
	void privateDownloadPresignedUrl() throws Exception {
		String token = loginAdmin();
		byte[] content = "私有附件-限时签名下载".getBytes(StandardCharsets.UTF_8);
		JsonNode data = upload(token, content, "private.txt", OssConstants.ACCESS_PRIVATE);
		assertThat(data.path("url").isNull()).as("私有附件上传回执不暴露 url").isTrue();
		long id = data.path("id").asLong();

		// download/{id} 云平台私有附件 → 限时预签名 GET URL，可真实 GET 到字节
		JsonNode down = readBody(get("/system/file/download/" + id, token));
		assertThat(down.path("code").asInt()).isEqualTo(200);
		String presigned = down.path("data").asText();
		assertThat(presigned).contains("X-Amz-Signature");
		assertThat(getBytes(presigned)).isEqualTo(content);

		post("/system/file/remove", List.of(id), token);
	}

	@Test
	@Order(4)
	void akSkEncryptedAtRest() throws Exception {
		String token = loginAdmin();
		// 直查原始列值：密文落库、非明文、可解回
		try (Connection conn = dataSource.getConnection();
			 PreparedStatement ps = conn.prepareStatement("SELECT access_key, secret_key FROM sys_oss WHERE oss_code = ?")) {
			ps.setString(1, MINIO_OSS_CODE);
			try (ResultSet rs = ps.executeQuery()) {
				assertThat(rs.next()).as("minio 配置行存在").isTrue();
				String accessKeyCol = rs.getString(1);
				String secretKeyCol = rs.getString(2);
				assertThat(accessKeyCol).isNotEqualTo(MINIO_ROOT_USER);
				assertThat(secretKeyCol).isNotEqualTo(MINIO_ROOT_PASSWORD);
				assertThat(Sm4Util.decrypt(accessKeyCol)).isEqualTo(MINIO_ROOT_USER);
				assertThat(Sm4Util.decrypt(secretKeyCol)).isEqualTo(MINIO_ROOT_PASSWORD);
			}
		}
		// 管理端 page 不回传 secretKey
		JsonNode page = readBody(get("/system/oss/page?pageNum=1&pageSize=50", token));
		for (JsonNode row : page.path("data").path("records")) {
			assertThat(row.path("secretKey").isNull()).as("page 不回传 secretKey ossCode=" + row.path("ossCode").asText()).isTrue();
		}
	}

	@Test
	@Order(5)
	void switchBackToLocalAtRuntime() throws Exception {
		String token = loginAdmin();
		// 建本地配置并切主（同租户互斥，minio 配置自动禁用）
		Map<String, Object> local = new HashMap<>();
		local.put("name", "本地-IT");
		local.put("ossCode", LOCAL_OSS_CODE);
		local.put("category", OssConstants.CATEGORY_LOCAL);
		local.put("storagePath", LOCAL_STORAGE_PATH);
		JsonNode saved = readBody(post("/system/oss/submit", local, token));
		assertThat(saved.path("code").asInt()).isEqualTo(200);
		long localId = ossIdOf(token, LOCAL_OSS_CODE);
		assertThat(readBody(post("/system/oss/enable/" + localId, null, token)).path("code").asInt()).isEqualTo(200);

		// 运行时切回本地：上传/下载恢复本地行为（不重启）
		byte[] content = "切回本地存储字节".getBytes(StandardCharsets.UTF_8);
		JsonNode data = upload(token, content, "local.txt", OssConstants.ACCESS_PUBLIC);
		assertThat(data.path("platform").asText()).isEqualTo(LOCAL_OSS_CODE);
		java.io.File stored = new java.io.File(
			LOCAL_STORAGE_PATH + data.path("path").asText() + data.path("filename").asText());
		assertThat(stored).exists();
		ResponseEntity<byte[]> down = rest.exchange("/system/file/download-stream/" + data.path("id").asLong(),
			HttpMethod.GET, new HttpEntity<>(authHeaders(token)), byte[].class);
		assertThat(down.getBody()).isEqualTo(content);
		ResponseEntity<String> rm = post("/system/file/remove", List.of(data.path("id").asLong()), token);
		assertThat(readBody(rm).path("code").asInt()).isEqualTo(200);
		assertThat(stored).doesNotExist();

		// 未知类别 fail-fast（禁类名入库白名单）
		Map<String, Object> bad = new HashMap<>();
		bad.put("name", "非法类别");
		bad.put("ossCode", "bad-it");
		bad.put("category", "com.evil.Backdoor");
		JsonNode badResp = readBody(post("/system/oss/submit", bad, token));
		assertThat(badResp.path("code").asInt()).as("未知类别应被拒").isNotEqualTo(200);
	}

	// ===== 辅助 =====

	/** 建 MinIO 配置并切主（幂等：已存在则复用） */
	private void enableMinioConfig(String token) {
		Map<String, Object> minio = new HashMap<>();
		minio.put("name", "MinIO-IT");
		minio.put("ossCode", MINIO_OSS_CODE);
		minio.put("category", OssConstants.CATEGORY_MINIO);
		minio.put("endpoint", minioEndpoint);
		minio.put("accessKey", MINIO_ROOT_USER);
		minio.put("secretKey", MINIO_ROOT_PASSWORD);
		minio.put("bucketName", BUCKET);
		// 测试线程无会话租户，直连 mapper 须显式忽略租户（fail-closed 约定）
		SysOss existing = TenantContext.ignore(() -> ossMapper.selectOneByQuery(
			com.mybatisflex.core.query.QueryWrapper.create().eq("oss_code", MINIO_OSS_CODE)));
		long id;
		if (existing == null) {
			JsonNode saved = readBody(post("/system/oss/submit", minio, token));
			assertThat(saved.path("code").asInt()).as("minio 配置入库：" + saved).isEqualTo(200);
			id = ossIdOf(token, MINIO_OSS_CODE);
		} else {
			id = existing.getId();
		}
		assertThat(readBody(post("/system/oss/enable/" + id, null, token)).path("code").asInt()).isEqualTo(200);
	}

	private long ossIdOf(String token, String ossCode) {
		JsonNode page = readBody(get("/system/oss/page?pageNum=1&pageSize=50", token));
		for (JsonNode row : page.path("data").path("records")) {
			if (ossCode.equals(row.path("ossCode").asText())) {
				return row.path("id").asLong();
			}
		}
		throw new IllegalStateException("配置不存在 ossCode=" + ossCode);
	}

	/** multipart 上传，断言成功并返回附件数据 */
	private JsonNode upload(String token, byte[] content, String filename, String access) {
		HttpHeaders headers = authHeaders(token);
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("file", new ByteArrayResource(content) {
			@Override
			public String getFilename() {
				return filename;
			}
		});
		body.add("access", access);
		ResponseEntity<String> resp = rest.exchange("/system/file/upload", HttpMethod.POST,
			new HttpEntity<>(body, headers), String.class);
		JsonNode json = readBody(resp);
		assertThat(json.path("code").asInt()).as("上传应成功：" + json).isEqualTo(200);
		return json.path("data");
	}

	/** 预签名 URL 直传 PUT（字节绕过后端，仅携带签发回执的 headers） */
	@Test
	@Order(2)
	void presignedPutBindsHeadersAndCreateRechecksObject() throws Exception {
		String token = loginAdmin();
		// 1) 签名里必须带上大小、类型、下载方式三个头
		JsonNode sign = signPut(token, "bind.txt", 10);
		String uploadUrl = sign.path("uploadUrl").asText();
		String signed = java.net.URLDecoder.decode(
			uploadUrl.replaceAll(".*[?&]X-Amz-SignedHeaders=([^&]*).*", "$1"), StandardCharsets.UTF_8)
			.toLowerCase(java.util.Locale.ROOT);
		assertThat(signed.split(";")).as("SignedHeaders=" + signed)
			.contains("content-length", "content-type", "content-disposition");

		// 2) 绕过签名直接往凭证路径写一个大小不符的对象：create 回验必须拒绝并删掉对象
		String objectKey = objectKeyOf(uploadUrl);
		byte[] wrong = "twenty-bytes-not-ten".getBytes(StandardCharsets.UTF_8);
		minioClient.putObject(io.minio.PutObjectArgs.builder().bucket(BUCKET).object(objectKey)
			.stream(new java.io.ByteArrayInputStream(wrong), wrong.length, -1).contentType("text/plain").build());
		long before = countObjects("private/");
		JsonNode rejected = readBody(post("/system/file/create", Map.of("ticket", sign.path("ticket").asText()), token));
		assertThat(rejected.path("code").asInt()).as("大小不符应被拒：" + rejected).isNotEqualTo(200);
		assertThatThrownBy(() -> minioClient.statObject(StatObjectArgs.builder().bucket(BUCKET).object(objectKey).build()))
			.as("回验不通过的暂存对象已删除").isInstanceOf(ErrorResponseException.class);
		assertThat(countObjects("private/")).as("校验在复制后的新 key 上做，不通过时新 key 也删掉").isEqualTo(before);

		// 3) 大小、类型都对但内容不是 PNG：魔数回验拒绝
		byte[] fake = "not a png at all".getBytes(StandardCharsets.UTF_8);
		JsonNode signPng = signPut(token, "fake.png", fake.length);
		String pngKey = objectKeyOf(signPng.path("uploadUrl").asText());
		minioClient.putObject(io.minio.PutObjectArgs.builder().bucket(BUCKET).object(pngKey)
			.stream(new java.io.ByteArrayInputStream(fake), fake.length, -1).contentType("image/png").build());
		JsonNode rejectedPng = readBody(post("/system/file/create", Map.of("ticket", signPng.path("ticket").asText()), token));
		assertThat(rejectedPng.path("code").asInt()).as("魔数不符应被拒：" + rejectedPng).isNotEqualTo(200);
		assertThatThrownBy(() -> minioClient.statObject(StatObjectArgs.builder().bucket(BUCKET).object(pngKey).build()))
			.as("魔数不符的对象已删除").isInstanceOf(ErrorResponseException.class);
		assertThat(countObjects("private/")).as("魔数不符时新 key 也删掉").isEqualTo(before);
	}

	/** 桶内 key 含某一段前缀（如 private/）的对象数；basePath 可能在前面 */
	private long countObjects(String segment) throws Exception {
		long n = 0;
		for (var r : minioClient.listObjects(io.minio.ListObjectsArgs.builder().bucket(BUCKET).recursive(true).build())) {
			String k = r.get().objectName();
			if (k.startsWith(segment) || k.contains("/" + segment)) {
				n++;
			}
		}
		return n;
	}

	private JsonNode signPut(String token, String filename, int size) {
		Map<String, Object> req = new HashMap<>();
		req.put("filename", filename);
		req.put("size", size);
		req.put("access", OssConstants.ACCESS_PRIVATE);
		JsonNode sign = readBody(post("/system/file/presigned-put", req, token));
		assertThat(sign.path("code").asInt()).as("签发：" + sign).isEqualTo(200);
		assertThat(sign.path("data").path("supported").asBoolean()).isTrue();
		return sign.path("data");
	}

	/** 预签名 URL 的路径形如 /bucket/key */
	private static String objectKeyOf(String uploadUrl) throws Exception {
		String path = new URL(uploadUrl).getPath();
		return java.net.URLDecoder.decode(path.substring(path.indexOf('/', 1) + 1), StandardCharsets.UTF_8);
	}

	private void putBytes(String uploadUrl, JsonNode headers, byte[] content) throws Exception {
		HttpURLConnection conn = (HttpURLConnection) new URL(uploadUrl).openConnection();
		conn.setDoOutput(true);
		conn.setRequestMethod("PUT");
		if (headers != null && headers.isObject()) {
			headers.fields().forEachRemaining(e -> conn.setRequestProperty(e.getKey(), e.getValue().asText()));
		}
		conn.setFixedLengthStreamingMode(content.length);
		conn.getOutputStream().write(content);
		assertThat(conn.getResponseCode()).as("直传 PUT 应成功").isEqualTo(200);
		conn.disconnect();
	}

	private byte[] getBytes(String url) throws Exception {
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		conn.setRequestMethod("GET");
		assertThat(conn.getResponseCode()).as("预签名 GET 应成功").isEqualTo(200);
		byte[] bytes = conn.getInputStream().readAllBytes();
		conn.disconnect();
		return bytes;
	}
}
