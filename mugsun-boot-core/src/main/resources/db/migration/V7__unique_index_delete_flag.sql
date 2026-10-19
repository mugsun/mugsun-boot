-- 0.1.1：唯一索引只约束未删除的行。openGauss / 达梦的 V7 改了 6 个唯一索引并把手机号索引改成唯一。
-- PostgreSQL（及金仓）在 V1 里已经是 WHERE is_deleted = 0 的部分唯一索引，手机号索引也已是
-- WHERE is_deleted = 0 AND phone IS NOT NULL AND phone <> '' 的部分唯一，本版本号在 PG 上无需改动。
-- 保留这个文件是为了三套方言同号（见 README）。
SELECT 1;
