package com.mugsun.boot.common.constant;

/**
 * 对象存储（G89）常量：存储类别固定键、访问级别、直传凭证与 sys_param 键。
 */
public interface OssConstants {

	/** 存储类别：本地磁盘（LocalPlus） */
	String CATEGORY_LOCAL = "local";
	/** 存储类别：MinIO */
	String CATEGORY_MINIO = "minio";
	/** 存储类别：阿里云 OSS（配置类就绪，运行时未验证） */
	String CATEGORY_ALIYUN = "aliyun";

	/** 访问级别：公开直取 */
	String ACCESS_PUBLIC = "public";
	/** 访问级别：私有授权下载 */
	String ACCESS_PRIVATE = "private";

	/** 配置状态：启用（同租户互斥） */
	int STATUS_ENABLE = 1;
	/** 配置状态：禁用 */
	int STATUS_DISABLE = 0;

	/** 上传对象路径前缀：公开区 */
	String PATH_PUBLIC = "public/";
	/** 上传对象路径前缀：私有区 */
	String PATH_PRIVATE = "private/";

	/** FileInfo attr 扩展键：访问级别（recorder 登记时还原 sys_attach.access） */
	String ATTR_ACCESS = "access";

	/** FileInfo attr 扩展键：内部对象标记（非附件，AttachFileRecorder 跳过 sys_attach 登记/销户） */
	String ATTR_INTERNAL_OBJECT = "internalObject";
	/** 内部对象标记值：埋点会话回放块（G100；元数据自管于 track_replay，不进附件体系） */
	String INTERNAL_OBJECT_TRACK_REPLAY = "track-replay";
	/** 内部对象标记值：埋点 sourcemap 文件（G101；元数据自管于 track_sourcemap，不进附件体系） */
	String INTERNAL_OBJECT_TRACK_SOURCEMAP = "track-sourcemap";
	/** 内部对象标记值：埋点接口响应体（G102；无元数据表，键按事件纯推导，不进附件体系） */
	String INTERNAL_OBJECT_TRACK_API_BODY = "track-api-body";

	/** sys_param 键：私有附件预签名下载 URL 有效期（秒） */
	String PARAM_PRESIGNED_URL_EXPIRE = "oss.presigned-url-expire-seconds";
	/** 兜底默认：预签名下载 URL 有效期 300 秒 */
	long DEFAULT_PRESIGNED_URL_EXPIRE_SECONDS = 300L;

	/** 直传一次性凭证 Redis 键前缀（ticket 为随机串，GETDEL 消费） */
	String PRESIGNED_TICKET_PREFIX = "mugsun:oss:presigned:";
	/** 直传凭证有效期（秒）：须覆盖大文件直传耗时，逾期须重新签发 */
	long PRESIGNED_TICKET_SECONDS = 3600L;

	/** 直传文件名长度上限 */
	int PRESIGNED_FILENAME_MAX_LEN = 128;
}
