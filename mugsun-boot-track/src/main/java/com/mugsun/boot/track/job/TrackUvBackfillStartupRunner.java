package com.mugsun.boot.track.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 升级用开关：{@code mugsun.track.uv-backfill.on-startup=true} 时，启动后在后台线程跑一遍 UV 回填（不阻塞启动）。
 * 回填幂等、默认只在运行时段内执行，开着重启也只是空跑；回填完成后建议关掉。高峰期不要开。没有 PowerJob 的部署用这个入口。
 */
@Component
@ConditionalOnProperty(prefix = "mugsun.track.uv-backfill", name = "on-startup", havingValue = "true")
public class TrackUvBackfillStartupRunner implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TrackUvBackfillStartupRunner.class);

	private final TrackUvBackfillJob job;

	public TrackUvBackfillStartupRunner(TrackUvBackfillJob job) {
		this.job = job;
	}

	@Override
	public void run(ApplicationArguments args) {
		Thread t = new Thread(() -> {
			try {
				job.backfill();
			} catch (Exception e) {
				log.warn("启动时 UV 回填失败（可再次执行，已改动的行均有备份）：{}", e.getMessage());
			}
		}, "track-uv-backfill");
		t.setDaemon(true);
		t.start();
	}
}
