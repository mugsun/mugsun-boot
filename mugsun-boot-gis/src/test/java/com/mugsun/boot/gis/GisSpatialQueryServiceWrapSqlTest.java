package com.mugsun.boot.gis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** #76：wrapAwareWhere / WIDE 走 wraps_antimeridian 标记与 ShiftLongitude 部分索引语义 */
class GisSpatialQueryServiceWrapSqlTest {

	@Test
	@DisplayName("WIDE 只认 wraps_antimeridian 列")
	void wideUsesColumn() {
		assertThat(GisSpatialQueryService.WIDE).isEqualTo(" AND wraps_antimeridian");
		assertThat(GisSpatialQueryService.WIDE).doesNotContain("ST_XMax");
	}

	@Test
	@DisplayName("wrapAwareWhere：非跨支排除 wraps，跨支走 ShiftLongitude &&")
	void wrapAwareWhereUsesFlagAndShift() {
		String sql = GisSpatialQueryService.wrapAwareWhere("ST_Intersects", "ENV");
		assertThat(sql).contains("NOT wraps_antimeridian");
		assertThat(sql).contains("wraps_antimeridian");
		assertThat(sql).contains("ST_ShiftLongitude(geom) && ENV");
		assertThat(sql).contains("ST_Translate(ENV, 360, 0)");
		assertThat(sql).doesNotContain("ST_MakeEnvelope(-180");
		assertThat(sql).doesNotContain("ST_XMax(geom)");
		assertThat(GisSpatialQueryService.WRAP_AWARE_G_COUNT).isEqualTo(6);
		assertThat(countOccurrences(sql, "ENV")).isEqualTo(6);
	}

	private static int countOccurrences(String haystack, String needle) {
		int n = 0;
		for (int i = 0; (i = haystack.indexOf(needle, i)) >= 0; i += needle.length()) {
			n++;
		}
		return n;
	}
}
