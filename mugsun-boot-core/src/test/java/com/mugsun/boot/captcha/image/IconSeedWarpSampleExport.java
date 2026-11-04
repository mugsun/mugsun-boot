package com.mugsun.boot.captcha.image;

import com.mugsun.boot.captcha.CaptchaProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 导出 ICON_CLICK 样例给人测（默认不跑）。启用：
 * {@code -Dicon.seedwarp.export=true -Dicon.seedwarp.export.dir=/path}
 */
class IconSeedWarpSampleExport {

	@Test
	@EnabledIfSystemProperty(named = "icon.seedwarp.export", matches = "true")
	void exportSamples() throws Exception {
		String dir = System.getProperty("icon.seedwarp.export.dir", "target/icon-seedwarp-samples");
		Path out = Path.of(dir);
		Files.createDirectories(out);
		CaptchaProperties props = new CaptchaProperties();
		MultiTypeRenderer r = new MultiTypeRenderer(props, new BackgroundLibrary(props));
		int n = Integer.getInteger("icon.seedwarp.export.n", 12);
		for (int i = 0; i < n; i++) {
			MultiTypeRenderer.Click c = r.renderIconClick();
			String bgB64 = c.background().substring(c.background().indexOf(',') + 1);
			String piB64 = c.promptImage().substring(c.promptImage().indexOf(',') + 1);
			BufferedImage bg = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(bgB64)));
			BufferedImage pi = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(piB64)));
			ImageIO.write(bg, "png", out.resolve(String.format("challenge-%02d-bg.png", i + 1)).toFile());
			ImageIO.write(pi, "png", out.resolve(String.format("challenge-%02d-prompt.png", i + 1)).toFile());
			Files.writeString(out.resolve(String.format("challenge-%02d-prompt.txt", i + 1)),
				String.join(",", c.prompt()));
		}
		System.out.println("ICON-SEEDWARP-EXPORT dir=" + out.toAbsolutePath() + " n=" + n);
	}
}
