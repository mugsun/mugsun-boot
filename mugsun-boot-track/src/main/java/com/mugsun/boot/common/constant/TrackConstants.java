package com.mugsun.boot.common.constant;

/**
 * 埋点（G99）常量：独立数据源 / 权限码 / sys_param 键 / Redis 键 / 截断长度 / 摄入与调度参数。
 * <p>埋点库锁定 PostgreSQL（未来 ClickHouse），与业务库物理隔离、多方言解耦；
 * 全部埋点读写经 {@code @TrackDS} 切面路由到 {@link #DS_KEY} 数据源。
 */
public interface TrackConstants {

	/** 埋点独立数据源 key（mybatis-flex.datasource 静态键，租户无关） */
	String DS_KEY = "track";
	/** track 库独立 Flyway 迁移目录（版本序列独立于主库） */
	String FLYWAY_LOCATIONS = "classpath:db/track/migration";
	/** track 库 Flyway 迁移文件前缀（T1/T2/...，与主库 V 序列区分） */
	String FLYWAY_SQL_PREFIX = "T";

	/** 权限码：埋点概览查询 */
	String PERM_OVERVIEW_LIST = "sys:track-overview:list";
	/** 权限码：事件分析查询 */
	String PERM_EVENT_LIST = "sys:track-event:list";
	/** 权限码：性能分析查询 */
	String PERM_PERF_LIST = "sys:track-perf:list";
	/** 权限码：错误监控查询 */
	String PERM_ERROR_LIST = "sys:track-error:list";
	/** 权限码：埋点接入应用查询 */
	String PERM_APP_LIST = "sys:track-app:list";
	/** 权限码：埋点接入应用新增 */
	String PERM_APP_ADD = "sys:track-app:add";
	/** 权限码：埋点接入应用编辑 */
	String PERM_APP_EDIT = "sys:track-app:edit";
	/** 权限码：回放会话列表查询（G100） */
	String PERM_REPLAY_LIST = "sys:track-replay:list";
	/** 权限码：回放查看（G100；最高敏感，查看必留痕审计） */
	String PERM_REPLAY_VIEW = "sys:track-replay:view";

	/** sys_param 键：collect 端点单 IP 滑窗限流（次/分） */
	String PARAM_RATE_LIMIT = "track.collect.rate-limit";
	/** sys_param 键：单批最大事件数（超过截断并计数） */
	String PARAM_BATCH_MAX = "track.collect.batch-max";
	/** sys_param 键：明细保留天数（新应用默认值；分区清理依据） */
	String PARAM_RETENTION_DAYS = "track.retention-days";
	/** 兜底默认：限流 600 次/分/IP */
	int DEFAULT_RATE_LIMIT = 600;
	/** 兜底默认：单批 100 事件 */
	int DEFAULT_BATCH_MAX = 100;
	/** 兜底默认：明细保留 90 天 */
	int DEFAULT_RETENTION_DAYS = 90;

	/** Redis 键前缀（埋点域全部键共用，便于按前缀统计/清理） */
	String REDIS_PREFIX = "mugsun:track:";
	/** 幂等键前缀：SETNX mugsun:track:evt:{event_id}，命中即丢并计数 */
	String IDEMPOTENT_KEY_PREFIX = REDIS_PREFIX + "evt:";
	/** 幂等键 TTL（秒）：25h，覆盖离线补发窗口 */
	long IDEMPOTENT_TTL_SECONDS = 90000L;
	/** 实时流前缀：Redis Stream mugsun:track:stream:{app_key}（XADD + MAXLEN 近似裁剪） */
	String STREAM_KEY_PREFIX = REDIS_PREFIX + "stream:";
	/** 实时流近似裁剪长度 */
	int STREAM_MAX_LEN = 1000;
	/** 在线人数前缀：ZSET mugsun:track:online:{app_key}（score=上报毫秒时间戳） */
	String ONLINE_KEY_PREFIX = REDIS_PREFIX + "online:";
	/** 在线判定窗口（毫秒）：5 分钟 */
	long ONLINE_WINDOW_MS = 300000L;

	/** props 键截断长度 */
	int PROPS_KEY_MAX_LEN = 64;
	/** props 值截断长度 */
	int PROPS_VALUE_MAX_LEN = 1024;
	/** props 单事件总量上限（字节，16KB） */
	int PROPS_TOTAL_MAX_BYTES = 16384;
	/** props 嵌套深度上限 */
	int PROPS_MAX_DEPTH = 3;
	/** 事件名长度上限 */
	int EVENT_NAME_MAX_LEN = 64;
	/** appKey 长度上限 */
	int APP_KEY_MAX_LEN = 32;
	/** URL 路径长度上限 */
	int URL_MAX_LEN = 512;
	/** 页面标题长度上限 */
	int PAGE_TITLE_MAX_LEN = 255;

	/** collect 单批解压后体量上限（字节，2MB） */
	int COLLECT_PAYLOAD_MAX_BYTES = 2097152;
	/** 异步消费批量聚合条数（条/窗口孰先触发） */
	int CONSUME_BATCH_SIZE = 200;
	/** 异步消费批量聚合窗口（毫秒） */
	long CONSUME_BATCH_WINDOW_MS = 500L;
	/** 消费队列上限（单副本进程内；满则丢新 + 计数告警，宁可丢事件不拖垮 DB） */
	int CONSUME_QUEUE_CAPACITY = 10000;
	/** 批次失败重回队列最大次数（指数退避，超过丢弃 + 计数） */
	int CONSUME_MAX_RETRY = 5;
	/** 消费虚拟线程数（2–4 小并发档；独立执行器，不挂 TenantTaskDecorator） */
	int CONSUME_THREAD_COUNT = 2;
	/** 批次重回队列的指数退避基数（毫秒），实际等待 = 基数 × 2^(次数-1)，封顶 {@link #CONSUME_RETRY_BACKOFF_MAX_MS} */
	long CONSUME_RETRY_BACKOFF_BASE_MS = 1000L;
	/** 批次重回队列的退避上限（毫秒） */
	long CONSUME_RETRY_BACKOFF_MAX_MS = 30000L;

	/** 限流键前缀：INCR mugsun:track:rl:{ip}:{appKey}:{yyyyMMddHHmm}，首置 EXPIRE 70s（分钟窗滑窗） */
	String RATE_LIMIT_KEY_PREFIX = REDIS_PREFIX + "rl:";
	/** 限流键过期（秒）：略大于 60s 窗口，防边界计数残留 */
	long RATE_LIMIT_EXPIRE_SECONDS = 70L;
	/** 限流键分钟窗时间格式 */
	String RATE_LIMIT_MINUTE_PATTERN = "yyyyMMddHHmm";
	/** track_app 本地缓存 TTL（毫秒）：appKey 校验/配置下发共用，多副本各自缓存、无广播，生效最坏延迟 = TTL */
	long APP_CACHE_TTL_MS = 30000L;

	/** 校时阈值（毫秒）：|client_ts − received_at| 超 24h 则 ts := received_at 且 clock_skewed=1（正常偏差不拒收） */
	long CLOCK_SKEW_THRESHOLD_MS = 86400000L;
	/** client_ts 允许的最大未来偏移（毫秒）：晚于 received_at + 7 天视为荒谬时间，丢弃该条并计数 */
	long CLIENT_TS_MAX_FUTURE_MS = 604800000L;
	/** client_ts 最早合法值（2020-01-01T00:00:00Z  epoch 毫秒）：早于此视为荒谬时间，丢弃并计数 */
	long CLIENT_TS_MIN_EPOCH_MS = 1577836800000L;

	/** distinct_id 长度上限（对应 track_event.distinct_id VARCHAR(64)） */
	int DISTINCT_ID_MAX_LEN = 64;
	/** session_id 长度上限（对应 VARCHAR(36)） */
	int SESSION_ID_MAX_LEN = 36;
	/** event_id 长度上限（对应 VARCHAR(36)） */
	int EVENT_ID_MAX_LEN = 36;
	/** 路由模板/来源域名/UTM 等维度列通用长度上限（对应 VARCHAR(255)） */
	int DIM_MAX_LEN = 255;
	/** 设备类型长度上限（对应 VARCHAR(16)） */
	int DEVICE_MAX_LEN = 16;
	/** 浏览器/操作系统列长度上限（对应 VARCHAR(32)） */
	int BROWSER_OS_MAX_LEN = 32;
	/** 错误指纹长度上限（对应 VARCHAR(64)） */
	int ERROR_FINGERPRINT_MAX_LEN = 64;

	/** 内置事件：页面浏览 */
	String EVENT_PAGEVIEW = "$pageview";
	/** 内置事件：错误 */
	String EVENT_ERROR = "$error";
	/** 内置事件：会话结束（到达即 settled=1 定稿） */
	String EVENT_SESSION_END = "$session_end";
	/** 内置事件：身份绑定 */
	String EVENT_IDENTIFY = "$identify";

	/** 内置（$ 前缀）事件白名单：不在清单内的 $ 事件一律拒收（$ 为保留字，防伪造保留事件污染统计） */
	java.util.Set<String> PREDEFINED_EVENTS = java.util.Set.of(
		EVENT_PAGEVIEW, "$pageleave", "$click", "$exposure", "$web_vitals",
		EVENT_ERROR, "$session_start", EVENT_SESSION_END, EVENT_IDENTIFY);
	/** 自定义事件名正则：字母开头，字母/数字/下划线，总长 ≤64 */
	java.util.regex.Pattern CUSTOM_EVENT_NAME = java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

	/** SDK 平台：web（UA 解析仅对 web 执行） */
	String PLATFORM_WEB = "web";
	/** 设备类型：桌面 */
	String DEVICE_DESKTOP = "desktop";
	/** 设备类型：手机 */
	String DEVICE_MOBILE = "mobile";
	/** 设备类型：平板 */
	String DEVICE_TABLET = "tablet";

	/** 协议字段：接入标识 */
	String FIELD_APP_KEY = "app_key";
	/** 协议字段：事件数组 */
	String FIELD_EVENTS = "events";
	/** 协议字段：事件幂等 ID */
	String FIELD_EVENT_ID = "event_id";
	/** 协议字段：事件名 */
	String FIELD_EVENT_NAME = "event";
	/** 协议字段：客户端原始时间（epoch 毫秒） */
	String FIELD_TS = "ts";
	/** 协议字段：匿名 ID */
	String FIELD_DISTINCT_ID = "distinct_id";
	/** 协议字段：会话 ID */
	String FIELD_SESSION_ID = "session_id";
	/** 协议字段：自定义属性包 */
	String FIELD_PROPS = "props";
	/** 协议字段：SDK 信息 */
	String FIELD_SDK = "sdk";
	/** 协议字段：SDK 平台（web/android/ios） */
	String FIELD_PLATFORM = "platform";
	/** 协议字段：客户端上报的登录用户（不可信，仅 $identify 一致性核对用） */
	String FIELD_USER_ID = "user_id";
	/** props 热点键：原始路径（提升为 url_path 列） */
	String PROP_URL_PATH = "url_path";
	/** props 热点键：路由模板（提升为 route_path 列，page 维度聚合防高基数） */
	String PROP_ROUTE_PATH = "route_path";
	/** props 热点键：页面标题 */
	String PROP_PAGE_TITLE = "page_title";
	/** props 热点键：来源域名 */
	String PROP_REFERRER_DOMAIN = "referrer_domain";
	/** props 热点键：UTM 来源 */
	String PROP_UTM_SOURCE = "utm_source";
	/** props 热点键：UTM 媒介 */
	String PROP_UTM_MEDIUM = "utm_medium";
	/** props 热点键：UTM 活动 */
	String PROP_UTM_CAMPAIGN = "utm_campaign";
	/** props 热点键：时长（$pageleave/计时事件，毫秒） */
	String PROP_DURATION_MS = "duration_ms";
	/** props 热点键：错误指纹（SDK 可算好上报；缺省服务端按 message+堆栈首行兜底计算） */
	String PROP_ERROR_FINGERPRINT = "error_fingerprint";
	/** props 热点键：自报设备类型（缺省服务端 UA 解析兜底） */
	String PROP_DEVICE = "device";
	/** props 热点键：错误消息（$error 指纹兜底计算输入） */
	String PROP_ERROR_MESSAGE = "message";
	/** props 热点键：错误堆栈（$error 指纹兜底计算输入，取首行） */
	String PROP_ERROR_STACK = "stack";

	/** 指标：摄入接收事件数 */
	String METRIC_RECEIVED = "track.ingest.received";
	/** 指标：丢弃事件/回放块/响应体数（tag reason：batch_truncated/invalid_event/bad_name/ts_absurd/queue_full/retry_exhausted/persist_failed
	 *  / replay_session_oversize / replay_banned / replay_queue_full / replay_retry_exhausted / replay_persist_failed
	 *  / api_body_oversize / api_body_store_failed / event_disabled（G105：事件定义停用拒收）） */
	String METRIC_DROPPED = "track.ingest.dropped";
	/** 指标：限流拒收批次数 */
	String METRIC_RATELIMITED = "track.ingest.ratelimited";
	/** 指标：Redis 幂等命中丢弃事件数 */
	String METRIC_DUPLICATED = "track.ingest.duplicated";
	/** 指标：发生校时修正事件数 */
	String METRIC_CLOCK_SKEWED = "track.ingest.clock_skewed";
	/** 指标：身份裁定拒绝数（tag reason：identify_no_token/identify_user_mismatch/token_tenant_mismatch） */
	String METRIC_IDENTITY_REJECTED = "track.ingest.identity_rejected";
	/** 指标：落库延迟（received_at 与落库时刻差） */
	String METRIC_LAG = "track.ingest.lag";

	/** 5 分钟 rollup 调度 tick（毫秒）：读游标补扫至当前窗口，窗口全量重算覆盖（幂等可重入） */
	long ROLLUP_TICK_MS = 300000L;
	/** 会话结算调度 tick（毫秒）：扫 idx_session_settle 部分索引，30min 静默会话定稿落账 */
	long SESSION_SETTLE_TICK_MS = 60000L;
	/** 会话静默判定（毫秒）：end_time 早于 now-30min 视为待结算 */
	long SESSION_SETTLE_SILENCE_MS = 1800000L;
	/** 分区维护调度 tick（毫秒）：固定周期触发，实际按月节流（每月 25 日预建次月分区） */
	long PARTITION_TICK_MS = 3600000L;

	/* ==================== B3：rollup 流水线 / 维护任务 / 分析 API ==================== */

	/** 游标任务键：5 分钟 rollup */
	String CURSOR_JOB_STATS_5M = "stats_5m";
	/** 游标任务键：天级 rollup */
	String CURSOR_JOB_STATS_DAY = "stats_day";
	/** 游标任务键：Web Vitals 直方图 rollup（日粒度窗口） */
	String CURSOR_JOB_STATS_VITALS = "stats_vitals";

	/** Redis 调度锁键：5 分钟 rollup */
	String LOCK_STATS_5M = REDIS_PREFIX + "lock:stats-5m";
	/** Redis 调度锁键：天级 rollup */
	String LOCK_STATS_DAY = REDIS_PREFIX + "lock:stats-day";
	/** Redis 调度锁键：vitals rollup */
	String LOCK_STATS_VITALS = REDIS_PREFIX + "lock:stats-vitals";
	/** Redis 调度锁键：会话结算 */
	String LOCK_SESSION_SETTLE = REDIS_PREFIX + "lock:session-settle";
	/** Redis 调度锁键：分区维护 */
	String LOCK_PARTITION = REDIS_PREFIX + "lock:partition";
	/** 调度锁 TTL（秒）：防持锁节点宕机死锁；正常一轮执行远短于此 */
	long JOB_LOCK_SECONDS = 600L;

	/** rollup 调度 tick（毫秒）：1 分钟探一次；内存节流（{@link #ROLLUP_TICK_MS}）控制实际执行频率 */
	long ROLLUP_SCHED_TICK_MS = 60000L;
	/** 天级 rollup 调度 tick（毫秒）：1 小时探一次（当日已追平则跳过，凌晨生效） */
	long STATS_DAY_TICK_MS = 3600000L;
	/** 5m 任务单次补扫窗口上限：一次最多追 288 窗（=1 天），防长时间宕机后单轮爆量，剩余逐轮追平 */
	int ROLLUP_5M_MAX_WINDOWS = 288;
	/** 日级任务单次补扫窗口上限：一次最多追 92 天（一个季度；day/vitals 共用），剩余逐轮追平 */
	int ROLLUP_DAY_MAX_WINDOWS = 92;

	/** 维度类型：事件（dim_key=事件名） */
	String DIM_EVENT = "event";
	/** 维度类型：页面（dim_key=路由模板，空回退 url_path） */
	String DIM_PAGE = "page";
	/** 维度类型：来源域名 */
	String DIM_REFERRER = "referrer";
	/** 维度类型：设备类型 */
	String DIM_DEVICE = "device";
	/** 维度类型：全站总览（仅 day 表；dim_key 固定 {@link #DIM_KEY_ALL}） */
	String DIM_OVERVIEW = "overview";
	/** 总览维度键（day 表 overview 行固定值） */
	String DIM_KEY_ALL = "ALL";

	/** vitals 指标白名单（props.metric 值域） */
	java.util.Set<String> VITALS_METRICS = java.util.Set.of("lcp", "inp", "cls", "fcp", "ttfb");
	/** vitals 指标名：CLS（唯一直方图桶界不同的指标，千分制） */
	String VITALS_METRIC_CLS = "cls";
	/** vitals 毫秒类指标（lcp/inp/fcp/ttfb）对数桶界（毫秒，升序）：桶序号 = 小于边界的个数，共 9 桶（末桶为溢出桶） */
	long[] VITALS_MS_BUCKET_BOUNDS = {100L, 250L, 500L, 1000L, 2500L, 5000L, 10000L, 30000L};
	/** vitals CLS 桶界（千分制，升序）：共 8 桶（末桶为溢出桶）；SDK 上报 CLS×1000 的数值 */
	long[] VITALS_CLS_BUCKET_BOUNDS = {10L, 25L, 50L, 100L, 250L, 500L, 1000L};
	/** props 热点键：vitals 指标名 */
	String PROP_VITALS_METRIC = "metric";
	/** props 热点键：vitals 指标值（毫秒或 CLS 千分制） */
	String PROP_VITALS_VALUE = "value";
	/** vitals 直方图 url_path 空值占位（唯一索引含 url_path，空串代替 NULL 防 ON CONFLICT 失配） */
	String VITALS_DIM_UNKNOWN = "";

	/** 分析查询 days 上限（天） */
	int ANALYSIS_DAYS_MAX = 90;
	/** 分析/管理分页 pageSize 上限（钳制防全表拉取） */
	int QUERY_PAGE_SIZE_MAX = 500;
	/** 分析 Top 列表默认条数（页面/来源/浏览器分布） */
	int ANALYSIS_TOP_LIMIT = 10;
	/** 看板查询缓存（秒）：JetCache LOCAL，tenantKeyConvertor 租户前缀天然隔离 */
	int ANALYSIS_CACHE_SECONDS = 45;
	/** 分布统计中来源域名空值标签（直访） */
	String DIM_REFERRER_DIRECT = "direct";
	/** 分布统计中设备/浏览器空值标签 */
	String DIM_UNKNOWN = "unknown";

	/** app_key 生成前缀（新增应用时服务端生成，客户端不可指定/篡改） */
	String APP_KEY_PREFIX = "ak_";
	/** app_key 随机段长度（hex；总长 = 前缀 + 24 ≤ 32） */
	int APP_KEY_RANDOM_LEN = 24;

	/** 分区预建起始日：每月 25 日（含）起预建次月分区（实现按日幂等确保当月+次月存在） */
	int PARTITION_PREBUILD_DAY_OF_MONTH = 25;

	/** 指标：兜底默认分区残留行数告警（非空即分区预建失败/迟到数据越界） */
	String METRIC_DEFAULT_PARTITION_ROWS = "track.partition.default.rows";

	/* ==================== G100：会话回放（rrweb 块摄入/存储/读取/保留期） ==================== */

	/** 回放限流阈值 = collect 限流（{@link #PARAM_RATE_LIMIT}）× 此倍数（回放块大频次低，放宽但独立键隔离） */
	int REPLAY_RATE_LIMIT_FACTOR = 2;
	/** 回放限流键前缀：INCR mugsun:track:rlr:{ip}:{appKey}:{yyyyMMddHHmm}，首置 EXPIRE 70s（与 collect 同窗口语义） */
	String REPLAY_RATE_LIMIT_KEY_PREFIX = REDIS_PREFIX + "rlr:";

	/** 回放请求信封上限（字节，1.5MB）：base64 块文本 + 协议字段（XssFilter 的 2MB 硬顶之前先拦） */
	int REPLAY_ENVELOPE_MAX_BYTES = 1572864;
	/** 回放单块解压后上限（字节，4MB；超限 413）。首块含 rrweb 全量快照（整页 DOM + 内联样式，管理台页面
	 *  实测 1~3MB），1MB 会误杀首块致播放器无基座空白；gzip 块 4MB 明文 ≈ 数百 KB 压缩字节，远低 payload 上限 */
	int REPLAY_BLOCK_MAX_BYTES = 4194304;
	/** base64 块文本长度上限（≈1.41MB）：gzip 块压缩字节远低此界（块上限以解压后口径判定，见
	 *  {@link #REPLAY_BLOCK_MAX_BYTES}）；此界实际钳制 gzip=false 明文块（解码后 ≈1.07MB 封顶，pagehide 收尾块为增量小量，足够） */
	int REPLAY_PAYLOAD_B64_MAX_LEN = 1442802;
	/** 单会话回放累计上限（解压后字节，默认 20MB；sys_param {@link #PARAM_REPLAY_SESSION_MAX} 可调，超 413 + 会话封禁） */
	long DEFAULT_REPLAY_SESSION_MAX_BYTES = 20971520L;
	/** sys_param 键：单会话回放累计上限（解压后字节） */
	String PARAM_REPLAY_SESSION_MAX = "track.replay.session-max-bytes";
	/** 会话累计体积计数器键前缀：INCRBY mugsun:track:replay-size:{session_id}（解压后字节），首置 EXPIRE 25h */
	String REPLAY_SIZE_KEY_PREFIX = REDIS_PREFIX + "replay-size:";
	/** 会话超限封禁键前缀：mugsun:track:replay-ban:{session_id} TTL 25h，存在即拒收该会话后续块（413） */
	String REPLAY_BAN_KEY_PREFIX = REDIS_PREFIX + "replay-ban:";
	/** 块幂等键前缀：SETNX mugsun:track:replay-seq:{session_id}:{seq} TTL 25h，命中 = 重复块丢弃（200 duplicated） */
	String REPLAY_SEQ_KEY_PREFIX = REDIS_PREFIX + "replay-seq:";
	/** 回放域 Redis 键统一 TTL（秒）：25h，覆盖会话最长生命 + 离线补发窗口（与事件幂等同口径） */
	long REPLAY_KEY_TTL_SECONDS = 90000L;

	/** 回放消费队列上限（单副本进程内；块均 ~100KB gz，满 = 丢新 + 计数 + 503 由 SDK 重试） */
	int REPLAY_QUEUE_CAPACITY = 256;
	/** 回放消费线程数：1（回放量级远低于事件流；单线程保会话内块按到达序落储） */
	int REPLAY_CONSUME_THREAD_COUNT = 1;

	/** 回放对象路径前缀：对象键 = replay/{app_key}/{yyyyMM}/{session_id}/{seq}.gz（私有桶；键清单按 seq 推导） */
	String REPLAY_PATH_PREFIX = "replay/";
	/** 对象键 yyyyMM 段格式（首块到达时刻，UTC；同会话所有块共用首块月份目录） */
	String REPLAY_PATH_MONTH_PATTERN = "yyyyMM";
	/** 回放块文件名后缀（内容即客户端原样 gzip 字节） */
	String REPLAY_BLOCK_SUFFIX = ".gz";
	/** 回放块 ContentType */
	String REPLAY_BLOCK_CONTENT_TYPE = "application/gzip";
	/** 块序号上限（5s/块连续 24h 约 1.7 万块，10 万足够余量；超界 400 防异常序号撑爆键清单） */
	int REPLAY_SEQ_MAX = 100000;
	/** app_key/session_id 对象键路径安全字符集（防路径穿越注入对象键） */
	java.util.regex.Pattern REPLAY_PATH_SAFE = java.util.regex.Pattern.compile("^[A-Za-z0-9_-]+$");

	/** Redis 调度锁键：回放保留期清理 */
	String LOCK_REPLAY_CLEAN = REDIS_PREFIX + "lock:replay-clean";
	/** 回放清理调度 tick（毫秒）：每小时探一次，内存节流每日一轮（同分区维护范式） */
	long REPLAY_CLEAN_TICK_MS = 3600000L;
	/** 回放清理单轮处理上限（行）；超出逐轮消化 */
	int REPLAY_CLEAN_BATCH_SIZE = 500;
	/** 回放保留天数兜底默认（应用已删/行缺省时；同 track_app.replay_retention_days 列默认） */
	int REPLAY_DEFAULT_RETENTION_DAYS = 14;

	/** 指标：回放块接收数（通过全部校验、已入消费队列） */
	String METRIC_REPLAY_RECEIVED = "track.replay.received";
	/** 指标：回放块幂等命中丢弃数（同 session+seq 重发） */
	String METRIC_REPLAY_DUPLICATED = "track.replay.duplicated";

	/* ==================== G101：错误监控增强（sourcemap 堆栈还原支撑 + 错误告警） ==================== */

	/** sourcemap 对象路径前缀：对象键 = sourcemap/{app_key}/{release}/{filename}（私有存储，元数据自管于 track_sourcemap） */
	String SOURCEMAP_PATH_PREFIX = "sourcemap/";
	/** sourcemap 文件大小上限（字节，20MB；超限 400） */
	long SOURCEMAP_MAX_BYTES = 20971520L;
	/** sourcemap 文件名后缀（仅收 .map，小写比对） */
	String SOURCEMAP_SUFFIX = ".map";
	/** sourcemap ContentType（上传登记与 raw 端点下发同口径） */
	String SOURCEMAP_CONTENT_TYPE = "application/json";
	/** release 版本号长度上限（对应 VARCHAR(128)） */
	int SOURCEMAP_RELEASE_MAX_LEN = 128;
	/** sourcemap 文件名长度上限（对应 VARCHAR(255)） */
	int SOURCEMAP_FILENAME_MAX_LEN = 255;
	/** release/filename 对象键路径安全字符集（防路径穿越注入对象键；比 REPLAY_PATH_SAFE 多放行 . ） */
	java.util.regex.Pattern SOURCEMAP_PATH_SAFE = java.util.regex.Pattern.compile("^[A-Za-z0-9._-]+$");

	/** 错误告警默认同指纹频次阈值（次/10 分钟窗；track_app.alert_threshold 列缺省） */
	int DEFAULT_ALERT_THRESHOLD = 10;
	/** 告警频次阈值上限（防误配 0=永不触发 / 超大=形同关闭） */
	int ALERT_THRESHOLD_MAX = 1000;
	/** 规则 A 新指纹去重键前缀：SETNX mugsun:track:alert-new:{app_key}:{fingerprint}，命中即本周期内已首告 */
	String ALERT_NEW_KEY_PREFIX = REDIS_PREFIX + "alert-new:";
	/** 规则 A 新指纹去重 TTL（秒）：7 天 */
	long ALERT_NEW_TTL_SECONDS = 604800L;
	/** 规则 B 频次窗计数键前缀：INCR mugsun:track:alert-freq:{app_key}:{fingerprint}，首置 EXPIRE 窗长 */
	String ALERT_FREQ_KEY_PREFIX = REDIS_PREFIX + "alert-freq:";
	/** 规则 B 频次窗长（秒）：10 分钟 */
	long ALERT_FREQ_WINDOW_SECONDS = 600L;
	/** 规则 B 窗级告警抑制键前缀：SETNX mugsun:track:alert-sent:{app_key}:{fingerprint}（TTL=频次窗剩余），存在即本窗已告过 */
	String ALERT_FREQ_SENT_KEY_PREFIX = REDIS_PREFIX + "alert-sent:";
	/** 告警站内信标题 */
	String ALERT_MESSAGE_TITLE = "埋点错误告警";
	/** 告警查看链接（前端错误监控页路由 hash） */
	String ALERT_ERROR_LINK = "#/track/error";
	/** 指标：告警站内信发送数（tag reason：new-fingerprint/threshold） */
	String METRIC_ALERT_SENT = "track.alert.sent";

	/* ==================== G102：用户细查（行为时间线）+ 接口监控 + 响应体采集 ==================== */

	/** 权限码：用户细查时间线查询 */
	String PERM_USER_LIST = "sys:track-user:list";
	/** 权限码：接口响应体查看（G102；最高敏感，查看必留痕审计） */
	String PERM_USER_VIEW_BODY = "sys:track-user:view-body";

	/** sys_param 键：单个接口响应体采集上限（字节，解压后口径） */
	String PARAM_API_BODY_MAX_BYTES = "track.api-body.max-bytes";
	/** 兜底默认：响应体上限 1MB（安全阀，防大导出响应打爆存储；非业务截断，超限不采） */
	long DEFAULT_API_BODY_MAX_BYTES = 1048576L;

	/** api-body 限流键前缀：INCR mugsun:track:rla:{ip}:{appKey}:{yyyyMMddHHmm}，首置 EXPIRE 70s
	 * （阈值 = collect 同级，独立键隔离互不挤占） */
	String API_BODY_RATE_LIMIT_KEY_PREFIX = REDIS_PREFIX + "rla:";
	/** api-body 幂等键前缀：SETNX mugsun:track:api-body:{event_id} TTL 25h，命中 = 重复上传丢弃（200 duplicated） */
	String API_BODY_IDEMPOTENT_KEY_PREFIX = REDIS_PREFIX + "api-body:";
	/** api-body 域 Redis 键统一 TTL（秒）：25h（与事件幂等同口径，覆盖重发窗口） */
	long API_BODY_KEY_TTL_SECONDS = 90000L;

	/** api-body 请求信封上限（字节，1.5MB）：base64 体文本 + 协议字段（XssFilter 的 2MB 硬顶之前先拦） */
	int API_BODY_ENVELOPE_MAX_BYTES = 1572864;
	/** base64 体文本长度上限（≈1.41MB）：gzip 体压缩字节远低此界（上限以解压后口径判定，见 sys_param
	 *  {@link #PARAM_API_BODY_MAX_BYTES}）；此界实际钳制 gzip=false 明文体（解码后 ≈1.07MB 封顶，JSON 响应足够） */
	int API_BODY_PAYLOAD_B64_MAX_LEN = 1442802;

	/** api-body 对象路径前缀：对象键 = api-body/{app_key}/{yyyyMM}/{event_id}.json.gz（私有桶；
	 *  键按 track_event.props->>'body_ref' + 事件 received_at 纯推导，无元数据表） */
	String API_BODY_PATH_PREFIX = "api-body/";
	/** api-body 对象文件名后缀（落储恒 gzip：明文体服务端补压，存储/读取单一口径，键名 .gz 不自欺） */
	String API_BODY_FILE_SUFFIX = ".json.gz";
	/** api-body 对象 ContentType */
	String API_BODY_CONTENT_TYPE = "application/gzip";
	/** 对象键 yyyyMM 段格式（UTC）：写入取上传到达时刻，读取/清理按事件 received_at 同格式推导——
	 *  月末边界事件与其 body 分跨两月的极端错位按「body 未采集」诚实口径兜底（at-most-once 域，可接受） */
	String API_BODY_PATH_MONTH_PATTERN = "yyyyMM";

	/** props 热点键：响应体关联键（api_request 事件；值 = 该事件自身 event_id，即对象键文件名段） */
	String PROP_BODY_REF = "body_ref";

	/** 应用编辑校验：响应体保留天数上限（天；body 体量远大于事件流，钳短上限防误配长保留撑爆对象存储） */
	int API_BODY_MAX_RETENTION_DAYS = 30;
	/** 响应体保留天数兜底默认（应用已删/行缺省时；同 track_app.api_body_retention_days 列默认） */
	int API_BODY_DEFAULT_RETENTION_DAYS = 7;

	/** 时间线查询范围硬限（毫秒，7 天；超界 400，防全分区扫描） */
	long TIMELINE_RANGE_MAX_MS = 604800000L;
	/** 时间线分页默认条数 */
	int TIMELINE_DEFAULT_PAGE_SIZE = 20;
	/** 时间线分页条数上限（行含 props 原文，钳制严于通用分页上限） */
	int TIMELINE_PAGE_SIZE_MAX = 100;

	/** Redis 调度锁键：响应体保留期清理 */
	String LOCK_API_BODY_CLEAN = REDIS_PREFIX + "lock:api-body-clean";
	/** 响应体清理调度 tick（毫秒）：每小时探一次，内存节流每日一轮（同回放清理范式） */
	long API_BODY_CLEAN_TICK_MS = 3600000L;
	/** 响应体清理单轮处理上限（行）；超出逐轮消化 */
	int API_BODY_CLEAN_BATCH_SIZE = 500;

	/** 指标：响应体接收数（通过全部校验、已落对象存储） */
	String METRIC_API_BODY_RECEIVED = "track.api-body.received";
	/** 指标：响应体幂等命中丢弃数（同 event_id 重发） */
	String METRIC_API_BODY_DUPLICATED = "track.api-body.duplicated";

	/* ==================== G103：漏斗分析 + 留存分析 ==================== */

	/** 权限码：漏斗分析查询 */
	String PERM_FUNNEL_LIST = "sys:track-funnel:list";
	/** 权限码：留存分析查询 */
	String PERM_RETENTION_LIST = "sys:track-retention:list";

	/** 漏斗查询 days 上限（天；漏斗走明细即席查询，必须限窗——§2「明细下钻限时分区」） */
	int FUNNEL_DAYS_MAX = 30;
	/** 漏斗步数上限（步；去重后 <2 即 400） */
	int FUNNEL_STEPS_MAX = 5;
	/** 漏斗转化窗口可选值（小时）：1h / 24h / 7d */
	java.util.Set<Long> FUNNEL_WINDOW_OPTIONS_HOURS = java.util.Set.of(1L, 24L, 168L);
	/** 漏斗转化窗口默认（小时） */
	long FUNNEL_WINDOW_DEFAULT_HOURS = 24L;

	/** 留存查询 days 上限（天；cohort 窗 = 留存窗同长） */
	int RETENTION_DAYS_MAX = 30;
	/** 留存新客回看窗（天）：actor 首活跃日落在回看窗首日 = 窗口截断无法判定新老，保守排除（宁漏不假新客） */
	int RETENTION_LOOKBACK_DAYS = 30;

	/* ==================== G104：圈选式可视化埋点 ==================== */

	/** 权限码：圈选规则查询 */
	String PERM_VISUAL_LIST = "sys:track-visual:list";
	/** 权限码：圈选规则编辑（含令牌签发 / 草稿确认 / 规则增改删） */
	String PERM_VISUAL_EDIT = "sys:track-visual:edit";

	/** 圈选令牌 Redis 键前缀：mugsun:track:visual-token:{token} → HASH {appKey,tenantId,userId} */
	String VISUAL_TOKEN_KEY_PREFIX = REDIS_PREFIX + "visual-token:";
	/** 圈选令牌 TTL（秒）：30 分钟（草稿列表键随其续期） */
	long VISUAL_TOKEN_TTL_SECONDS = 1800L;
	/** 圈选令牌随机段长度（hex 字符，192bit 熵） */
	int VISUAL_TOKEN_RANDOM_LEN = 48;

	/** 圈选草稿 Redis 列表键前缀：LIST mugsun:track:visual-draft:{token}（元素=草稿 JSON，右推） */
	String VISUAL_DRAFT_KEY_PREFIX = REDIS_PREFIX + "visual-draft:";
	/** 单令牌草稿上限（条；防单令牌刷爆内存，超出 400 提示先确认） */
	int VISUAL_DRAFT_MAX_PER_TOKEN = 50;
	/** 圈选草稿限流阈值（次/分/token+IP 双段键） */
	int VISUAL_DRAFT_RATE_LIMIT = 60;
	/** 圈选草稿限流键前缀：INCR mugsun:track:rlv:{token前8段}:{ip}:{yyyyMMddHHmm}，首置 EXPIRE 70s */
	String VISUAL_RATE_LIMIT_KEY_PREFIX = REDIS_PREFIX + "rlv:";

	/** 圈选规则下发上限（条，/track/config visualRules；按 update_time 倒序截断） */
	int VISUAL_RULES_MAX = 200;
	/** 圈选 selector 长度上限（对应 track_visual_rule.selector VARCHAR(512)） */
	int VISUAL_SELECTOR_MAX_LEN = 512;
	/** 圈选元素匹配文本长度上限（对应 VARCHAR(128)） */
	int VISUAL_MATCH_TEXT_MAX_LEN = 128;
	/** 圈选规则来源标记（track_visual_rule.source 列默认/唯一值；留 source 列防未来手工规则混入无法区分） */
	String VISUAL_RULE_SOURCE = "visual";
	/** inspect 圈选激活 URL 参数名（令牌签发拼 targetUrl 用；SDK 端 location.search 同名参数激活，协议对齐） */
	String VISUAL_INSPECT_PARAM = "__mst_inspect";

	/* ==================== G105：埋点遗留收口 ==================== */

	/** 事件定义本地缓存 TTL（毫秒）：摄入侧停用拒收判定用；多副本各自缓存无广播，管理端变更经 evict 即时生效（同 TrackAppService 口径） */
	long EVENT_DEF_CACHE_TTL_MS = 30000L;

	/** Redis 调度锁键：事件明细保留清理 */
	String LOCK_EVENT_CLEAN = REDIS_PREFIX + "lock:event-clean";
	/** 事件明细清理调度 tick（毫秒）：每小时探一次，内存节流每日一轮（同回放/响应体清理范式） */
	long EVENT_CLEAN_TICK_MS = 3600000L;
	/** 事件明细清理单批删除行数（ctid 分批；超出逐轮消化） */
	int EVENT_CLEAN_BATCH_SIZE = 500;
	/** 事件明细清理单轮批数上限（单轮最多 5 万行 × 2 表，防长窗口单轮爆量） */
	int EVENT_CLEAN_MAX_BATCHES = 100;

	/** 回放会话事件条数上限（打点用；按 ts 升序截断，防巨会话拉爆响应） */
	int REPLAY_SESSION_EVENTS_MAX = 500;

	/* ==================== G106：埋点地理 ==================== */

	/** 精确经度（WGS84）props 键；服务端圆整后提升为列 */
	String PROP_GEO_LON = "geo_lon";
	/** 精确纬度（WGS84）props 键 */
	String PROP_GEO_LAT = "geo_lat";
	/** SDK 超属性别名（$ 前缀不进事件名白名单，仅 props） */
	String PROP_GEO_LON_ALT = "$geo_lon";
	String PROP_GEO_LAT_ALT = "$geo_lat";
	/** 定位精度（米，仅 props，不提升为列） */
	String PROP_GEO_ACC = "geo_acc";
	/** 坐标小数位（4 位 ≈ 11m，隐私圆整） */
	int GEO_SCALE = 4;
	/** 热力点查询上限（明细下钻，不进 rollup） */
	int GEO_POINTS_MAX = 2000;
	/** 地域聚合行上限 */
	int GEO_REGIONS_MAX = 50;
	/** 属地无法解析时的展示桶 */
	String GEO_REGION_UNKNOWN = "未知";
	/** 内网 IP 展示桶 */
	String GEO_REGION_INTRANET = "内网";
}
