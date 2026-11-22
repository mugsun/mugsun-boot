package com.mugsun.boot.common.i18n;

import com.mugsun.core.i18n.message.I18n;

/**
 * 把几句给人看的话连成一段。中文用分号隔开；别的语言怎么连，看翻译文件里「{0}；{1}」这一句
 * （英文每句自带句号，中间只空一格，所以要连起来的句子英文都以句号结尾）。
 */
public final class Sentences {

	private Sentences() {
	}

	/** 句子要先按这次请求的语言翻译好再传进来；一句都没有时返回 null */
	public static String join(Iterable<String> sentences) {
		String joined = null;
		for (String one : sentences) {
			joined = joined == null ? one : I18n.get("{0}；{1}", joined, one);
		}
		return joined;
	}
}
