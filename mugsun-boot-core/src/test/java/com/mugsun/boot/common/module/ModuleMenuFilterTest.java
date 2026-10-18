package com.mugsun.boot.common.module;

import com.mugsun.boot.system.entity.SysMenu;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class ModuleMenuFilterTest {

	public static SysMenu menu(long id, long parentId, String path, String type) {
		SysMenu m = new SysMenu();
		m.setId(id);
		m.setParentId(parentId);
		m.setPath(path);
		m.setMenuType(type);
		return m;
	}

	/** 模拟菜单：系统 / GIS（含子菜单与按钮）/ 埋点 / AI / 路径形似但不属于模块的 /gist */
	public static List<SysMenu> sample() {
		return List.of(
			menu(1, 0, "/system", "M"),
			menu(2, 1, "/system/user", "C"),
			menu(3, 2, null, "F"),
			menu(10, 0, "/gis", "M"),
			menu(11, 10, "/gis/layer", "C"),
			menu(12, 11, null, "F"),
			menu(20, 0, "/track", "M"),
			menu(21, 20, "/track/overview", "C"),
			menu(30, 0, "/ai", "M"),
			menu(31, 30, "/ai/chat", "C"),
			menu(32, 31, null, "F"),
			menu(40, 0, "/gist", "C"));
	}

	public static ModuleMenuFilter filter(boolean gis, boolean track, boolean ai) {
		return new ModuleMenuFilter(
			new GisModuleStatus() {
				public boolean present() { return gis; }
				public boolean enabled() { return gis; }
			},
			new TrackModuleStatus() {
				public boolean present() { return track; }
				public boolean enabled() { return track; }
			},
			new AiModuleStatus() {
				public boolean present() { return ai; }
				public boolean enabled() { return ai; }
			});
	}

	@Test
	@DisplayName("模块全开：不剔除任何菜单")
	void allEnabled() {
		assertThat(filter(true, true, true).hiddenMenuIds(sample())).isEmpty();
		assertThat(filter(true, true, true).filter(sample())).hasSize(12);
	}

	@Test
	@DisplayName("AI 关闭：剔除 /ai 目录、子菜单与无 path 的按钮，其他模块不受影响")
	void aiDisabledHidesSubtreeIncludingButtons() {
		Set<Long> hidden = filter(true, true, false).hiddenMenuIds(sample());
		assertThat(hidden).containsExactlyInAnyOrder(30L, 31L, 32L);
	}

	@Test
	@DisplayName("三个模块都缺席：只剩核心菜单；/gist 这类前缀相近的路径不误伤")
	void allAbsent() {
		List<SysMenu> kept = filter(false, false, false).filter(sample());
		assertThat(kept).extracting(SysMenu::getId).containsExactlyInAnyOrder(1L, 2L, 3L, 40L);
	}

	@Test
	@DisplayName("只给部分菜单（如按名称过滤后）时，仍按 path 识别模块菜单")
	void partialInput() {
		List<SysMenu> onlyButtonsAndLeaf = List.of(menu(11, 10, "/gis/layer", "C"), menu(12, 11, null, "F"));
		assertThat(filter(false, true, true).hiddenMenuIds(onlyButtonsAndLeaf)).containsExactlyInAnyOrder(11L, 12L);
	}
}
