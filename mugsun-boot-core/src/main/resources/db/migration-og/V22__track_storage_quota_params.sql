-- 0.1.1：埋点回放和接口响应体的存储容量参数（openGauss-lite 5.1）。与 PG V22 同号同义。幂等。
-- 参数含义见 PG V22；已有同名参数不覆盖。
INSERT INTO sys_param (id, param_name, param_key, param_value, remark, create_time, is_deleted) VALUES
(900023, '回放和响应体全平台每日落盘上限(字节)', 'track.storage.daily-max-bytes', '10737418240', '所有应用合计，按落盘的 gzip 字节、UTC 自然日计；超出后当天拒收（429）', now(), 0),
(900024, '回放和响应体单应用每日落盘上限(字节)', 'track.storage.app-daily-max-bytes', '2147483648', '每个应用单独计，按落盘的 gzip 字节、UTC 自然日计；超出后该应用当天拒收（429），不影响其它应用', now(), 0),
(900025, '埋点存储盘最低剩余空间(字节)', 'track.storage.min-free-bytes', '5368709120', '对象存储落本机磁盘时生效：存储目录所在分区剩余空间低于它就暂停接收回放和响应体（503）', now(), 0)
ON DUPLICATE KEY UPDATE NOTHING;
