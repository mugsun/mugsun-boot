package com.mugsun.boot.track;

import com.mugsun.boot.common.i18n.EnglishRequest;
import com.mugsun.boot.common.module.TrackAskIntent;
import com.mugsun.core.i18n.message.I18n;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 没配大模型时靠关键词理解问题：中文、英文的问法都要认 */
class TrackAskRuleIntentParserTest {

	private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

	private final TrackAskRuleIntentParser parser = new TrackAskRuleIntentParser();

	@ParameterizedTest(name = "{0} → {1}，{2} 天")
	@CsvSource(delimiter = '|', value = {
		"最近 7 天概览 | overview | 7",
		"近30天访问量怎么样？ | overview | 30",
		"今天的趋势 | trend | 1",
		"热门页面 | pages | 7",
		"当前在线多少人 | online | 7",
		"各省份分布 | geo | 7",
		"页面加载性能 | vitals | 7",
		"最近 14 天报错 | errors | 14",
		"次日留存 | retention | 7",
		"事件统计 | events | 7",
		"overview of the last 7 days | overview | 7",
		"How were visits in the last 7 days? | overview | 7",
		"users online today | online | 1",
		"Trend for the past 30 days | trend | 30",
		"top pages this week | pages | 7",
		"Which regions do visitors come from? | geo | 7",
		"web vitals in the last 14 days | vitals | 14",
		"errors today | errors | 1",
		"retention over the last month | retention | 30",
		"custom events in the last 3 days | events | 3",
	})
	void intentAndDays(String question, String intent, int days) {
		TrackAskIntent parsed = parser.parse(question, null);

		assertThat(parsed.intent()).isEqualTo(intent);
		assertThat(parsed.params()).containsEntry("days", days);
	}

	@Test
	void daysPassedInWinOverTheQuestion() {
		assertThat(parser.parse("overview of the last 7 days", 30).params()).containsEntry("days", 30);
	}

	@Test
	void eventNameIsPickedUpInBothLanguages() {
		assertThat(parser.parse("事件 click_buy 最近 7 天", null).params()).containsEntry("eventName", "click_buy");
		assertThat(parser.parse("event checkout_done in the last 7 days", null).params())
			.containsEntry("eventName", "checkout_done");
		assertThat(parser.parse("show event named \"$pageview\"", null).params()).containsEntry("eventName", "$pageview");
	}

	/** event list、events in … 这种说法里，event 后面的词不是事件名 */
	@Test
	void wordsAfterEventThatAreNotANameAreIgnored() {
		TrackAskIntent list = parser.parse("event list", null);
		assertThat(list.intent()).isEqualTo("events");
		assertThat(list.params().get("eventName")).isNull();

		TrackAskIntent plural = parser.parse("events in the last 7 days", null);
		assertThat(plural.intent()).isEqualTo("events");
		assertThat(plural.params().get("eventName")).isNull();
	}

	@Test
	void funnelStepsAndWindow() {
		TrackAskIntent chinese = parser.parse("漏斗：view,submit,done 窗口 1 小时", null);
		assertThat(chinese.intent()).isEqualTo("funnel");
		assertThat(chinese.params()).containsEntry("steps", "view,submit,done").containsEntry("windowHours", 1L);

		TrackAskIntent english = parser.parse("funnel: view, submit, done in the last 7 days with a 24-hour window", null);
		assertThat(english.intent()).isEqualTo("funnel");
		assertThat(english.params()).containsEntry("steps", "view,submit,done").containsEntry("windowHours", 24L)
			.containsEntry("days", 7);
	}

	/** 事件名里的数字（view_24h、signup_3day）不是小时数和天数 */
	@Test
	void numbersInsideEventNamesAreNotDaysOrHours() {
		TrackAskIntent parsed = parser.parse("funnel: view_24h, signup_3day", null);

		assertThat(parsed.params()).containsEntry("steps", "view_24h,signup_3day").containsEntry("days", 7);
		assertThat(parsed.params().get("windowHours")).isNull();
	}

	@Test
	void whatCannotBeUnderstoodSaysSo() {
		TrackAskIntent parsed = parser.parse("tell me a joke", null);

		assertThat(parsed.unsupported()).isTrue();
		assertThat(parsed.warning()).startsWith("暂时只支持问概览");
		assertThat(parser.parse("  ", null).unsupported()).isTrue();
	}

	/** 提示语里给用户举的英文例句，自己得问得通：改译文时把例句改成解析不了的，这里拦下来 */
	@Test
	void englishExamplesInTheHintsAreUnderstood() {
		List<String> hints = List.of(
			"暂时只支持问概览、趋势、页面、事件、在线、地域、性能、错误、漏斗或留存。请换一种说法，例如「最近 7 天概览」",
			"暂时无法理解这个问题，请换个说法，例如「最近 7 天概览」或「今日在线人数」",
			"请说明想看的指标，例如「最近 7 天概览」",
			"漏斗需要至少 2 个步骤事件名。请写成「漏斗：view,submit,done」");
		try (EnglishRequest english = EnglishRequest.open()) {
			for (String hint : hints) {
				String translated = I18n.translate(hint);
				assertThat(translated).as("这句应该有英文").isNotEqualTo(hint);
				Matcher example = QUOTED.matcher(translated);
				assertThat(example.find()).as("英文里应该带例句：%s", translated).isTrue();
				do {
					TrackAskIntent parsed = parser.parse(example.group(1), null);
					assertThat(parsed.unsupported()).as("例句问不通：%s", example.group(1)).isFalse();
					if ("funnel".equals(parsed.intent())) {
						assertThat(parsed.params().get("steps")).as("漏斗例句要能读出步骤").isEqualTo("view,submit,done");
					}
				} while (example.find());
			}
		}
	}
}
