package com.mugsun.boot.gis.xyz;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 进程内 LRU 瓦片缓存（按总字节数限额 + TTL）。免密钥公共瓦片服务（OSM 等）要求客户端缓存、避免重复请求。
 * 缓存键是上游完整地址；瓦片是公开数据，不含租户信息。
 */
public class GisXyzTileCache {

	public record Entry(byte[] body, String contentType, long expireAt) {
	}

	private final long maxBytes;
	private final long ttlMillis;
	private final LinkedHashMap<String, Entry> map = new LinkedHashMap<>(256, 0.75f, true);
	private long bytes;

	public GisXyzTileCache(long maxBytes, long ttlSeconds) {
		this.maxBytes = Math.max(0, maxBytes);
		this.ttlMillis = Math.max(0, ttlSeconds) * 1000L;
	}

	public synchronized Entry get(String key, long now) {
		Entry e = map.get(key);
		if (e == null) {
			return null;
		}
		if (e.expireAt() < now) {
			map.remove(key);
			bytes -= e.body().length;
			return null;
		}
		return e;
	}

	public synchronized void put(String key, byte[] body, String contentType, long now) {
		if (maxBytes == 0 || body.length > maxBytes) {
			return;
		}
		Entry old = map.remove(key);
		if (old != null) {
			bytes -= old.body().length;
		}
		map.put(key, new Entry(body, contentType, now + ttlMillis));
		bytes += body.length;
		Iterator<Map.Entry<String, Entry>> it = map.entrySet().iterator();
		while (bytes > maxBytes && it.hasNext()) {
			Map.Entry<String, Entry> eldest = it.next();
			bytes -= eldest.getValue().body().length;
			it.remove();
		}
	}

	public synchronized long size() {
		return bytes;
	}
}
