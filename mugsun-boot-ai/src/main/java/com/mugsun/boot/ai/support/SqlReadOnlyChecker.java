package com.mugsun.boot.ai.support;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.core.i18n.message.I18n;
import com.mugsun.core.tool.exception.ServiceException;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只读查询检查器。问数（提问、重跑）和 AI 应用的数据库节点在执行前都要过这里。
 * <p>先切词，再按 SELECT 的语法逐段走一遍，只放行认得的写法，认不出的一律拒绝：
 * <ul>
 *   <li>只能是一条 SELECT，可以带 WITH、UNION、子查询；</li>
 *   <li>FROM 里的每一项都解析出来再核对表白名单：逗号连接、JOIN、子查询、带括号的连接都算；
 *       函数不能当表用，generate_series 这类只生成数据、不读表的除外；</li>
 *   <li>函数只放行清单里的聚合、窗口、数学、字符串、日期和类型转换函数，
 *       清单之外的可以用配置项 mugsun.ai.sql.extra-functions 追加；</li>
 *   <li>注释在切词时丢掉，交给数据库执行的是按词重新拼出来的文本，所以检查的和执行的是同一份；</li>
 *   <li>反斜杠、美元符、方括号、双斜杠这类各家数据库切词规则不一样的写法直接拒绝；</li>
 *   <li>紧贴在字符串前面的词可能被某些数据库当成字符串前缀（Oracle 一类的 q'[…]'），引号在哪里结束就和这里切的不一样：
 *       只保留 N、E、B、X 和 _字符集 这几种，其余一两个字母的拒绝，更长的词拼回去时和引号隔开。</li>
 * </ul>
 * <p>模板模式（数据库节点）下，{@code ${名字}} 和 {@code {{名字}}} 只能出现在值的位置，编译成 {@code ?}
 * 由 PreparedStatement 绑定；写在引号里的占位符连同引号里的其他文字一起算一个字符串参数。
 */
public final class SqlReadOnlyChecker {

	public static final String MSG_VAR_POSITION = "变量只能用在值的位置（比如 where 条件里），不能用来拼表名、列名或整段查询";

	private static final int MAX_LENGTH = 20_000;
	private static final int MAX_DEPTH = 32;
	private static final int MAX_GROUPS = 64;
	private static final int MAX_LIST = 200;

	private static final String[] OPERATORS = {
		"!~*", "->>", "::", "||", "<=", ">=", "<>", "!=", "!~", "~*", "->",
		"+", "-", "*", "/", "%", "=", "<", ">", "^", "~"
	};

	private static final Set<String> SET_OPS = words("union intersect except minus");
	private static final Set<String> TAIL_WORDS = words("order limit offset fetch");

	/** 括号外遇到这些词，当前子句到此为止 */
	private static final Set<String> CLAUSE_WORDS = words(
		"from where group having window order limit offset fetch union intersect except minus for into");

	/** 不能当表名，也不能当省略 AS 的别名 */
	private static final Set<String> RESERVED = words(
		"select from where group having window order limit offset fetch union intersect except minus for into "
			+ "on using join inner left right full outer cross natural lateral straight_join apply asof global "
			+ "semi anti as with and or not table values only");

	/** ON 条件里遇到这些词，说明后面是下一段连接，交回 FROM 的解析 */
	private static final Set<String> JOIN_WORDS = words(
		"join inner cross natural full outer straight_join apply asof global semi anti lateral");

	/** ON 条件里可以跟在一个值后面的词 */
	private static final Set<String> ON_KEYWORDS = words(
		"and or not is null true false unknown in like ilike similar to between symmetric escape overlaps distinct "
			+ "at time zone collate div mod xor regexp rlike isnull notnull case when then else end exists any all some "
			+ "year quarter month week day hour minute second");

	/** ON 条件里，这些词后面可以开始一个新的值 */
	private static final Set<String> ON_BEFORE_VALUE = words(
		"on and or not like ilike to between symmetric escape overlaps from zone collate div mod xor regexp rlike "
			+ "case when then else interval");

	/** 整条查询里都不该单独出现的词 */
	private static final Set<String> BANNED_WORDS = words(
		"select table values into insert update delete merge drop alter create grant revoke call exec execute "
			+ "copy outfile dumpfile lock procedure recursive join straight_join apply lateral "
			+ "current_user session_user system_user current_role current_catalog current_schema");

	/** 命中这些词时按「不是只读查询」报错 */
	private static final Set<String> UNSAFE_WORDS = words(
		"insert update delete merge drop alter create grant revoke call exec execute copy truncate replace into "
			+ "outfile dumpfile lock for");

	/** 后面可以直接跟左括号、又不是函数的词 */
	private static final Set<String> PAREN_WORDS = words(
		"in not and or xor exists any all some over filter on when then else case as select distinct where having "
			+ "between like ilike regexp rlike by group sets rollup cube from for limit offset array to zone");

	private static final Set<String> FROM_OPENERS = words("extract substring trim overlay");
	private static final Set<String> FOR_OPENERS = words("substring overlay");
	private static final Set<String> WITH_FOLLOWERS = words("time ties rollup cube local");
	private static final Set<String> TYPED_LITERALS = words("date time timestamp timestamptz timetz interval");

	/** 可以紧贴在字符串前面的前缀：N'…'、E'…'、B'…'、X'…'。它们不改变字符串在哪里结束 */
	private static final Set<String> STRING_PREFIXES = words("n e b x");

	/** 一两个字母、后面可以直接跟字符串的关键字，没留空格也放行 */
	private static final Set<String> SHORT_KEYWORDS = words("as or in on by to");

	/** 可以写在 FROM 里当表用的函数：只生成数据，不读任何表 */
	private static final Set<String> TABLE_FUNCTIONS = words(
		"generate_series generate_subscripts unnest regexp_matches regexp_split_to_table string_to_table "
			+ "json_array_elements jsonb_array_elements json_array_elements_text jsonb_array_elements_text "
			+ "json_each jsonb_each json_each_text jsonb_each_text json_object_keys jsonb_object_keys");

	/**
	 * 放行的函数。能执行别的查询（query_to_xml、dblink）、读写文件、改配置、睡眠、取序列值、
	 * 以及数据库自带的 pg_ 系列管理函数都不在这里，新增时也不要加。
	 */
	private static final Set<String> FUNCTIONS = words(
		"count sum avg min max stddev stddev_pop stddev_samp std variance var_pop var_samp string_agg array_agg "
			+ "group_concat bool_and bool_or every bit_and bit_or json_agg jsonb_agg json_object_agg jsonb_object_agg "
			+ "json_arrayagg json_objectagg percentile_cont percentile_disc percentile_approx mode median corr "
			+ "covar_pop covar_samp regr_slope regr_intercept regr_r2 regr_count regr_avgx regr_avgy regr_sxx regr_syy "
			+ "regr_sxy listagg wm_concat approx_count_distinct ndv any_value count_if max_by min_by grouping "
			+ "row_number rank dense_rank percent_rank cume_dist ntile lag lead first_value last_value nth_value "
			+ "coalesce nullif greatest least ifnull nvl nvl2 if iif decode isnull "
			+ "abs ceil ceiling floor round trunc truncate mod power pow sqrt cbrt exp ln log log10 log2 sign pi "
			+ "degrees radians sin cos tan asin acos atan atan2 cot div width_bucket random rand "
			+ "length char_length character_length octet_length bit_length lengthb lower upper lcase ucase initcap "
			+ "trim ltrim rtrim btrim lpad rpad left right mid substr substring substring_index position strpos instr "
			+ "locate find_in_set field replace regexp_replace regexp_substr regexp_like regexp_instr regexp_count "
			+ "regexp_extract concat concat_ws split_part reverse translate ascii chr char md5 to_char to_number "
			+ "to_date to_timestamp format starts_with ends_with strcmp overlay string_to_array array_to_string "
			+ "array_length cardinality "
			+ "now current_date current_time current_timestamp localtime localtimestamp curdate curtime sysdate "
			+ "utc_date utc_time utc_timestamp date time timestamp year month day hour minute second quarter week "
			+ "weekday dayofweek dayofmonth dayofyear weekofyear yearweek monthname dayname last_day date_trunc "
			+ "date_part extract age date_add date_sub datediff timestampdiff timestampadd adddate subdate addtime "
			+ "subtime period_diff date_format time_format str_to_date from_unixtime unix_timestamp to_days from_days "
			+ "sec_to_time time_to_sec makedate maketime make_date make_time make_timestamp make_interval "
			+ "justify_days justify_hours justify_interval isfinite months_between add_months next_day "
			+ "days_add days_sub months_add months_sub years_add years_sub weeks_add weeks_sub hours_add hours_sub "
			+ "minutes_add minutes_sub seconds_add seconds_sub days_diff months_diff years_diff weeks_diff hours_diff "
			+ "minutes_diff seconds_diff date_diff time_slice date_slice str2date convert_tz timezone "
			+ "cast convert try_cast numeric decimal dec varchar character varying nchar nvarchar varchar2 number "
			+ "float double timestamptz interval datetime text int integer bigint smallint tinyint "
			+ "json_extract json_unquote json_extract_path json_extract_path_text jsonb_extract_path "
			+ "jsonb_extract_path_text json_value json_array_length jsonb_array_length json_typeof jsonb_typeof "
			+ "json_length json_contains json_build_object jsonb_build_object json_object json_array to_json to_jsonb "
			+ "row_to_json row "
			+ "generate_series generate_subscripts unnest regexp_match regexp_matches regexp_split_to_array "
			+ "regexp_split_to_table string_to_table json_array_elements jsonb_array_elements "
			+ "json_array_elements_text jsonb_array_elements_text json_each jsonb_each json_each_text jsonb_each_text "
			+ "json_object_keys jsonb_object_keys json_keys date_bin encode elt timediff microsecond period_add ord "
			+ "numtodsinterval numtoyminterval int2 int4 int8 float4 float8 bool");

	/**
	 * 配置里写了也不放行的函数：会执行别的查询、读写文件、改配置、睡眠、取序列值。
	 * 只是防手误的兜底，没列在这里的不代表安全，追加前要确认它不读表、不执行 SQL。
	 */
	private static final Set<String> NEVER_FUNCTIONS = words(
		"query_to_xml query_to_xmlschema query_to_xml_and_xmlschema table_to_xml table_to_xmlschema "
			+ "table_to_xml_and_xmlschema cursor_to_xml cursor_to_xmlschema schema_to_xml schema_to_xmlschema "
			+ "schema_to_xml_and_xmlschema database_to_xml database_to_xmlschema database_to_xml_and_xmlschema "
			+ "ts_stat ts_rewrite crosstab xpath_table set_config current_setting nextval setval currval "
			+ "load_file sleep benchmark get_lock release_lock sys_exec sys_eval extractvalue updatexml");
	private static final String[] NEVER_PREFIXES = {"pg_", "lo_", "dblink"};

	private static volatile Set<String> extraFunctions = Set.of();

	private SqlReadOnlyChecker() {
	}

	/**
	 * 在内置清单之外再放行一批函数（配置项 mugsun.ai.sql.extra-functions），整批替换上一次的设置。
	 *
	 * @return 没有生效的名字：不是函数名，或者属于不能放行的函数
	 */
	public static List<String> setExtraFunctions(Collection<String> names) {
		Set<String> accepted = new HashSet<>();
		List<String> refused = new ArrayList<>();
		if (names != null) {
			for (String raw : names) {
				if (raw == null || raw.isBlank()) {
					continue;
				}
				String name = asciiLower(raw.trim());
				if (isPlainName(name) && !neverAllowed(name)) {
					accepted.add(name);
				} else {
					refused.add(raw.trim());
				}
			}
		}
		extraFunctions = Set.copyOf(accepted);
		return refused;
	}

	private static boolean isPlainName(String name) {
		if (name.length() > 64 || !isWordStart(name.charAt(0))) {
			return false;
		}
		for (int i = 1; i < name.length(); i++) {
			if (!isWordPart(name.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static boolean neverAllowed(String name) {
		if (NEVER_FUNCTIONS.contains(name) || BANNED_WORDS.contains(name) || UNSAFE_WORDS.contains(name)
			|| RESERVED.contains(name)) {
			return true;
		}
		for (String prefix : NEVER_PREFIXES) {
			if (name.startsWith(prefix)) {
				return true;
			}
		}
		return false;
	}

	/** 检查一条完整的查询，不认占位符。whitelist 为空表示不限表，其余限制照旧。 */
	public static Plan check(String sql, Set<String> whitelist) {
		return compile(sql, whitelist, false);
	}

	/** 检查带占位符的查询模板，结果要先 {@link Plan#bind(Map)} 才能执行。 */
	public static Plan checkTemplate(String template, Set<String> whitelist) {
		return compile(template, whitelist, true);
	}

	private static Plan compile(String raw, Set<String> whitelist, boolean template) {
		if (raw == null || raw.isBlank()) {
			throw unsafe();
		}
		String s = raw.trim();
		if (s.endsWith(";")) {
			s = s.substring(0, s.length() - 1).trim();
		}
		if (s.isEmpty()) {
			throw unsafe();
		}
		if (s.length() > MAX_LENGTH) {
			throw new ServiceException(I18n.get("查询太长了，请控制在 {0} 个字符以内", MAX_LENGTH));
		}
		Set<String> allowed = new HashSet<>();
		if (whitelist != null) {
			for (String name : whitelist) {
				if (name != null && !name.isBlank()) {
					allowed.add(asciiLower(name.trim()));
				}
			}
		}
		List<Token> tokens = lex(s, template);
		Parser parser = new Parser(tokens, allowed);
		parser.parseStatement();
		return new Plan(render(tokens), Collections.unmodifiableSet(parser.tables));
	}

	// ---------------------------------------------------------------- 结果

	/** 一个要绑定的值。quoted 表示模板里它写在引号内，一定按文字处理。 */
	public record Param(Object value, boolean quoted) {
	}

	/** 可以直接交给 JDBC 的查询：sql 里只有 ? 占位，值在 params 里。 */
	public record Bound(String sql, List<Param> params) {
	}

	public static final class Plan {

		private final List<Object> pieces;
		private final Set<String> tables;

		private Plan(List<Object> pieces, Set<String> tables) {
			this.pieces = pieces;
			this.tables = tables;
		}

		/** 查询里引用到的真实表（小写；WITH 里起的名字不算） */
		public Set<String> tables() {
			return tables;
		}

		public boolean hasSlots() {
			for (Object piece : pieces) {
				if (piece instanceof Token) {
					return true;
				}
			}
			return false;
		}

		/** 模板里用到的变量名，按出现顺序，不重复 */
		public List<String> slotNames() {
			Set<String> names = new LinkedHashSet<>();
			for (Object piece : pieces) {
				if (!(piece instanceof Token slot)) {
					continue;
				}
				if (slot.kind == Kind.SLOT) {
					names.add(slot.text);
				} else {
					for (Object part : slot.parts) {
						if (part instanceof Var v) {
							names.add(v.name());
						}
					}
				}
			}
			return new ArrayList<>(names);
		}

		/** 没有变量的查询：检查后重新拼出来、可以直接执行的文本 */
		public String sql() {
			if (hasSlots()) {
				throw new IllegalStateException("带变量的查询要先 bind");
			}
			StringBuilder sb = new StringBuilder();
			for (Object piece : pieces) {
				sb.append((String) piece);
			}
			return sb.toString();
		}

		/**
		 * 把变量换成 ? 并给出要绑定的值。集合类型的值展开成多个 ?（用在 in (...) 里）。
		 *
		 * @param values 变量名 → 值；值只能是文字、数字、布尔、null，或者由它们组成的集合
		 */
		public Bound bind(Map<String, ?> values) {
			StringBuilder sql = new StringBuilder();
			List<Param> params = new ArrayList<>();
			for (Object piece : pieces) {
				if (piece instanceof String text) {
					sql.append(text);
					continue;
				}
				Token slot = (Token) piece;
				if (slot.kind == Kind.STRSLOT) {
					sql.append('?');
					params.add(new Param(literal(slot, values), true));
					continue;
				}
				Object value = values.get(slot.text);
				List<Object> list = asList(value);
				if (list == null) {
					sql.append('?');
					params.add(new Param(scalar(slot.text, value), false));
					continue;
				}
				if (list.size() > MAX_LIST) {
					throw new ServiceException(I18n.get("变量「{0}」里的值超过 {1} 个，请减少后再试", slot.text, MAX_LIST));
				}
				if (list.isEmpty()) {
					sql.append("NULL");
					continue;
				}
				for (int i = 0; i < list.size(); i++) {
					sql.append(i == 0 ? "?" : ", ?");
					params.add(new Param(scalar(slot.text, list.get(i)), false));
				}
			}
			return new Bound(sql.toString(), Collections.unmodifiableList(params));
		}

		private static Object literal(Token slot, Map<String, ?> values) {
			List<Object> parts = slot.parts;
			if (parts.size() == 1 && values.get(((Var) parts.get(0)).name()) == null) {
				return null;
			}
			StringBuilder sb = new StringBuilder();
			for (Object part : parts) {
				if (part instanceof String text) {
					sb.append(text);
					continue;
				}
				String name = ((Var) part).name();
				Object value = values.get(name);
				if (asList(value) != null) {
					throw new ServiceException(I18n.get("变量「{0}」是一组值，不能写在引号里。请去掉引号，写成 in ({1})", name, "${" + name + "}"));
				}
				Object plain = scalar(name, value);
				if (plain != null) {
					sb.append(plain instanceof BigDecimal number ? number.toPlainString() : String.valueOf(plain));
				}
			}
			return sb.toString();
		}

		private static List<Object> asList(Object value) {
			if (value instanceof Collection<?> collection) {
				return new ArrayList<>(collection);
			}
			if (value instanceof Object[] array) {
				return Arrays.asList(array);
			}
			return null;
		}

		private static Object scalar(String name, Object value) {
			if (value == null || value instanceof Boolean) {
				return value;
			}
			if (value instanceof CharSequence || value instanceof Character) {
				return value.toString();
			}
			if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
				return ((Number) value).longValue();
			}
			if (value instanceof BigInteger big) {
				return big.bitLength() < 64 ? (Object) big.longValue() : new BigDecimal(big);
			}
			if (value instanceof BigDecimal) {
				return value;
			}
			if (value instanceof Number number) {
				double d = number.doubleValue();
				if (!Double.isNaN(d) && !Double.isInfinite(d)) {
					return BigDecimal.valueOf(d);
				}
			}
			throw new ServiceException(I18n.get("变量「{0}」不是文字或数字，不能用在查询里", name));
		}
	}

	// ---------------------------------------------------------------- 切词

	private enum Kind {
		WORD, QIDENT, STRING, NUMBER, OP, LPAREN, RPAREN, COMMA, DOT, SLOT, STRSLOT, EOF
	}

	private record Var(String name) {
	}

	private record Placeholder(String name, int end) {
	}

	private static final class Token {

		final Kind kind;
		/** 原文；SLOT 是变量名 */
		final String text;
		/** WORD：ASCII 小写；QIDENT：去掉引号后的 ASCII 小写；其他同 text */
		final String lower;
		final boolean spaceBefore;
		/** STRSLOT：依次是 String（文字）或 Var（变量） */
		final List<Object> parts;

		Token(Kind kind, String text, String lower, boolean spaceBefore, List<Object> parts) {
			this.kind = kind;
			this.text = text;
			this.lower = lower;
			this.spaceBefore = spaceBefore;
			this.parts = parts;
		}
	}

	private static List<Token> lex(String s, boolean template) {
		List<Token> out = new ArrayList<>();
		int n = s.length();
		int i = 0;
		boolean space = false;
		while (i < n) {
			char c = s.charAt(i);
			if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
				space = true;
				i++;
				continue;
			}
			if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
				i += 2;
				while (i < n && s.charAt(i) != '\n' && s.charAt(i) != '\r') {
					i++;
				}
				space = true;
				continue;
			}
			if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
				int close = s.indexOf("*/", i + 2);
				if (close < 0) {
					throw unsafe();
				}
				i = close + 2;
				space = true;
				continue;
			}
			if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
				// 达梦的手册把 // 当单行注释用：按注释读的话，它后面的引号在那边不算数，这里切出来的字符串就对不上了。
				// 正常的查询用不到 //，直接拒绝
				throw unsupported("//");
			}
			if (c == '\'') {
				i = lexString(s, i, spaceBeforeString(out, space), template, out);
			} else if (c == '"' || c == '`') {
				i = lexQuoted(s, i, space, template, out);
			} else if (isDigit(c)) {
				i = lexNumber(s, i, space, out);
			} else if (isWordStart(c)) {
				int j = i + 1;
				while (j < n && isWordPart(s.charAt(j))) {
					j++;
				}
				String text = s.substring(i, j);
				out.add(new Token(Kind.WORD, text, asciiLower(text), space, null));
				i = j;
			} else if (c == '(') {
				out.add(new Token(Kind.LPAREN, "(", "(", space, null));
				i++;
			} else if (c == ')') {
				out.add(new Token(Kind.RPAREN, ")", ")", space, null));
				i++;
			} else if (c == ',') {
				out.add(new Token(Kind.COMMA, ",", ",", space, null));
				i++;
			} else if (c == '.') {
				out.add(new Token(Kind.DOT, ".", ".", space, null));
				i++;
			} else if (c == ';') {
				throw unsafe();
			} else {
				Placeholder p = template ? placeholderAt(s, i) : null;
				if (p != null) {
					out.add(new Token(Kind.SLOT, p.name(), p.name(), space, null));
					i = p.end();
				} else {
					String op = operatorAt(s, i);
					if (op == null) {
						throw unsupported(show(c));
					}
					out.add(new Token(Kind.OP, op, op, space, null));
					i += op.length();
				}
			}
			space = false;
		}
		out.add(new Token(Kind.EOF, "", "", space, null));
		return out;
	}

	/**
	 * 引号前面紧贴着一个词时，这个词可能是字符串前缀。Oracle 一类的数据库（金仓的 Oracle 兼容写法）有
	 * q'[…]'、nq'[…]'：字符串到定界符后面的引号才结束，中间的单引号不算数。这里是按引号切的，
	 * 切出来当成字符串的一段，到了那边是会被执行的 SQL。所以：
	 * <ul>
	 *   <li>N、E、B、X 和 MySQL 的 _字符集 不改变字符串在哪里结束，照原样紧贴着；</li>
	 *   <li>其余一两个字母的词直接拒绝（已知会改变结束位置的前缀都在这个范围里），少数几个关键字除外；</li>
	 *   <li>更长的词（关键字、类型名）按中间有空格处理：拼回去的语句里它和引号是分开的，数据库没法把它当前缀。</li>
	 * </ul>
	 *
	 * @return 这个字符串前面是否按有空格处理
	 */
	private static boolean spaceBeforeString(List<Token> out, boolean space) {
		if (space || out.isEmpty()) {
			return space;
		}
		Token prev = out.get(out.size() - 1);
		if (prev.kind != Kind.WORD) {
			return false;
		}
		String word = prev.lower;
		if (word.length() > 2) {
			return !word.startsWith("_");
		}
		if (STRING_PREFIXES.contains(word)) {
			return false;
		}
		if (SHORT_KEYWORDS.contains(word)) {
			return true;
		}
		throw unsupported(prev.text + "'…'");
	}

	private static int lexString(String s, int start, boolean space, boolean template, List<Token> out) {
		int n = s.length();
		StringBuilder body = new StringBuilder();
		int j = start + 1;
		boolean closed = false;
		while (j < n) {
			char d = s.charAt(j);
			if (d == '\'') {
				if (j + 1 < n && s.charAt(j + 1) == '\'') {
					body.append('\'');
					j += 2;
					continue;
				}
				closed = true;
				j++;
				break;
			}
			if (d == '\\' || d == 0x7f || (d < 0x20 && d != '\n' && d != '\r' && d != '\t')) {
				throw unsupported(show(d));
			}
			body.append(d);
			j++;
		}
		if (!closed) {
			throw unsafe();
		}
		String text = s.substring(start, j);
		List<Object> parts = template ? splitParts(body.toString()) : null;
		out.add(new Token(parts == null ? Kind.STRING : Kind.STRSLOT, text, text, space, parts));
		return j;
	}

	private static int lexQuoted(String s, int start, boolean space, boolean template, List<Token> out) {
		char quote = s.charAt(start);
		int close = s.indexOf(quote, start + 1);
		if (close < 0) {
			throw unsafe();
		}
		String text = s.substring(start, close + 1);
		String inner = s.substring(start + 1, close);
		if (inner.isEmpty() || (close + 1 < s.length() && s.charAt(close + 1) == quote)) {
			throw unsupported(text);
		}
		for (int k = 0; k < inner.length(); k++) {
			char d = inner.charAt(k);
			if (d == '\'' || d == '"' || d == '`' || d == '\\' || d < 0x20 || d == 0x7f) {
				throw unsupported(text);
			}
			// 反引号只有 MySQL 一类的数据库当引号用，别的数据库会把里面的内容当成普通 SQL 来读，
			// 所以里面只放行一个名字，藏不进别的词
			if (quote == '`' && !isWordPart(d)) {
				throw unsupported(text);
			}
		}
		if (template && (inner.contains("${") || inner.contains("{{"))) {
			throw new ServiceException(MSG_VAR_POSITION);
		}
		out.add(new Token(Kind.QIDENT, text, asciiLower(inner), space, null));
		return close + 1;
	}

	private static int lexNumber(String s, int start, boolean space, List<Token> out) {
		int n = s.length();
		int j = start;
		while (j < n && isDigit(s.charAt(j))) {
			j++;
		}
		if (j + 1 < n && s.charAt(j) == '.' && isDigit(s.charAt(j + 1))) {
			j++;
			while (j < n && isDigit(s.charAt(j))) {
				j++;
			}
		}
		if (j < n && (s.charAt(j) == 'e' || s.charAt(j) == 'E')) {
			int k = j + 1;
			if (k < n && (s.charAt(k) == '+' || s.charAt(k) == '-')) {
				k++;
			}
			if (k < n && isDigit(s.charAt(k))) {
				while (k < n && isDigit(s.charAt(k))) {
					k++;
				}
				j = k;
			}
		}
		if (j < n && isWordPart(s.charAt(j))) {
			throw unsupported(s.substring(start, j + 1));
		}
		String text = s.substring(start, j);
		out.add(new Token(Kind.NUMBER, text, text, space, null));
		return j;
	}

	/** 引号里的文字按占位符切开；一个占位符都没有时返回 null */
	private static List<Object> splitParts(String body) {
		List<Object> parts = new ArrayList<>();
		StringBuilder text = new StringBuilder();
		boolean any = false;
		int i = 0;
		while (i < body.length()) {
			Placeholder p = placeholderAt(body, i);
			if (p == null) {
				text.append(body.charAt(i));
				i++;
				continue;
			}
			if (text.length() > 0) {
				parts.add(text.toString());
				text.setLength(0);
			}
			parts.add(new Var(p.name()));
			any = true;
			i = p.end();
		}
		if (!any) {
			return null;
		}
		if (text.length() > 0) {
			parts.add(text.toString());
		}
		return parts;
	}

	/** 位置 i 上是不是 ${名字} 或 {{名字}}；名字由字母、数字、下划线和点组成 */
	private static Placeholder placeholderAt(String s, int i) {
		String close;
		if (s.startsWith("${", i)) {
			close = "}";
		} else if (s.startsWith("{{", i)) {
			close = "}}";
		} else {
			return null;
		}
		int n = s.length();
		int j = i + 2;
		while (j < n && s.charAt(j) == ' ') {
			j++;
		}
		int from = j;
		while (j < n && (isWordPart(s.charAt(j)) || s.charAt(j) == '.')) {
			j++;
		}
		if (j == from || j - from > 64 || !isWordStart(s.charAt(from))) {
			return null;
		}
		String name = s.substring(from, j);
		if (name.endsWith(".") || name.contains("..")) {
			return null;
		}
		while (j < n && s.charAt(j) == ' ') {
			j++;
		}
		if (!s.startsWith(close, j)) {
			return null;
		}
		return new Placeholder(name, j + close.length());
	}

	private static String operatorAt(String s, int i) {
		for (String op : OPERATORS) {
			if (s.startsWith(op, i)) {
				return op;
			}
		}
		return null;
	}

	private static boolean isDigit(char c) {
		return c >= '0' && c <= '9';
	}

	private static boolean isWordStart(char c) {
		return c == '_' || Character.isLetter(c);
	}

	private static boolean isWordPart(char c) {
		return c == '_' || Character.isLetterOrDigit(c);
	}

	/** 关键字、函数名、表名都只按 ASCII 转小写，和数据库认关键字的办法一致 */
	private static String asciiLower(String s) {
		StringBuilder sb = null;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c >= 'A' && c <= 'Z') {
				if (sb == null) {
					sb = new StringBuilder(s);
				}
				sb.setCharAt(i, (char) (c + 32));
			}
		}
		return sb == null ? s : sb.toString();
	}

	private static List<Object> render(List<Token> tokens) {
		List<Object> pieces = new ArrayList<>();
		StringBuilder text = new StringBuilder();
		boolean started = false;
		boolean afterSlot = false;
		for (Token t : tokens) {
			if (t.kind == Kind.EOF) {
				break;
			}
			boolean slot = isSlot(t);
			if (started && (t.spaceBefore || slot || afterSlot)) {
				text.append(' ');
			}
			if (slot) {
				if (text.length() > 0) {
					pieces.add(text.toString());
					text.setLength(0);
				}
				pieces.add(t);
			} else {
				text.append(t.text);
			}
			started = true;
			afterSlot = slot;
		}
		if (text.length() > 0) {
			pieces.add(text.toString());
		}
		return pieces;
	}

	// ---------------------------------------------------------------- 语法

	private enum Mode {
		SELECT_LIST, WHERE, ON, GROUP_BY, ORDER_BY, LIST, LIMIT
	}

	private static final class Parser {

		private final List<Token> toks;
		private final Set<String> whitelist;
		/** WITH 里起的名字，按原文记；只有写法一模一样的引用才算引用它 */
		private final Deque<Set<String>> cteScopes = new ArrayDeque<>();
		private final Set<String> tables = new LinkedHashSet<>();
		private int pos;
		private int depth;

		Parser(List<Token> toks, Set<String> whitelist) {
			this.toks = toks;
			this.whitelist = whitelist;
		}

		void parseStatement() {
			Token first = peek();
			if (isSlot(first)) {
				throw new ServiceException(MSG_VAR_POSITION);
			}
			if (!(isWord(first, "select") || isWord(first, "with") || first.kind == Kind.LPAREN)) {
				throw unsafe();
			}
			parseQuery();
			if (peek().kind != Kind.EOF) {
				throw unexpected(peek());
			}
		}

		private void parseQuery() {
			enter();
			boolean scoped = false;
			if (isWord(peek(), "with")) {
				next();
				boolean recursive = isWord(peek(), "recursive");
				if (recursive) {
					next();
				}
				Set<String> scope = new HashSet<>();
				cteScopes.push(scope);
				scoped = true;
				while (true) {
					Token name = next();
					if (!(name.kind == Kind.QIDENT || (name.kind == Kind.WORD && !RESERVED.contains(name.lower)))) {
						throw unexpected(name);
					}
					if (peek().kind == Kind.LPAREN) {
						parseNameList();
					}
					expectWord("as");
					if (isWord(peek(), "not")) {
						next();
						expectWord("materialized");
					} else if (isWord(peek(), "materialized")) {
						next();
					}
					expect(Kind.LPAREN);
					if (recursive) {
						// 带 recursive 时查询体里的同名引用就是它自己。排在后面的名字仍然要等定义完才算：
						// MySQL 不认前向引用，那里的同名引用指向真实的表
						scope.add(name.text);
					}
					parseQuery();
					expect(Kind.RPAREN);
					// 不带 recursive 时名字在自己的查询体之后才生效，体里的同名引用指向真实的表
					scope.add(name.text);
					if (peek().kind != Kind.COMMA) {
						break;
					}
					next();
				}
			}
			parseSetTerm();
			while (peek().kind == Kind.WORD && SET_OPS.contains(peek().lower)) {
				next();
				if (isWord(peek(), "all") || isWord(peek(), "distinct")) {
					next();
				}
				parseSetTerm();
			}
			if (isWord(peek(), "order")) {
				next();
				expectWord("by");
				walk(Mode.ORDER_BY);
			}
			while (isWord(peek(), "limit") || isWord(peek(), "offset") || isWord(peek(), "fetch")) {
				next();
				walk(Mode.LIMIT);
			}
			if (scoped) {
				cteScopes.pop();
			}
			leave();
		}

		private void parseSetTerm() {
			Token t = peek();
			if (isWord(t, "select")) {
				parseSelect();
			} else if (t.kind == Kind.LPAREN) {
				next();
				parseQuery();
				expect(Kind.RPAREN);
			} else {
				throw unexpected(t);
			}
		}

		private void parseSelect() {
			next();
			walk(Mode.SELECT_LIST);
			if (isWord(peek(), "from")) {
				next();
				parseFromList();
			}
			if (isWord(peek(), "where")) {
				next();
				walk(Mode.WHERE);
			}
			if (isWord(peek(), "group")) {
				next();
				expectWord("by");
				walk(Mode.GROUP_BY);
			}
			if (isWord(peek(), "having")) {
				next();
				walk(Mode.WHERE);
			}
			if (isWord(peek(), "window")) {
				next();
				walk(Mode.LIST);
			}
		}

		private void parseFromList() {
			parseJoined();
			while (peek().kind == Kind.COMMA) {
				next();
				parseJoined();
			}
		}

		private void parseJoined() {
			parseFromItem();
			while (true) {
				Token t = peek();
				if (isWord(t, "join")) {
					next();
				} else if (isWord(t, "inner") || isWord(t, "cross")) {
					next();
					expectWord("join");
				} else if (isWord(t, "left") || isWord(t, "right") || isWord(t, "full")) {
					next();
					if (isWord(peek(), "outer")) {
						next();
					}
					expectWord("join");
				} else if (isWord(t, "natural")) {
					next();
					if (isWord(peek(), "inner")) {
						next();
					} else if (isWord(peek(), "left") || isWord(peek(), "right") || isWord(peek(), "full")) {
						next();
						if (isWord(peek(), "outer")) {
							next();
						}
					}
					expectWord("join");
				} else {
					return;
				}
				parseFromItem();
				if (isWord(peek(), "on")) {
					next();
					walk(Mode.ON);
				} else if (isWord(peek(), "using")) {
					next();
					parseNameList();
				}
			}
		}

		private void parseFromItem() {
			Token t = peek();
			boolean lateral = isWord(t, "lateral");
			if (lateral) {
				next();
				t = peek();
			}
			if (t.kind == Kind.LPAREN) {
				enter();
				next();
				if (startsQuery()) {
					parseQuery();
				} else {
					parseFromList();
				}
				expect(Kind.RPAREN);
				leave();
				parseAlias();
				return;
			}
			if (isSlot(t)) {
				throw new ServiceException(MSG_VAR_POSITION);
			}
			if (!(t.kind == Kind.QIDENT || (t.kind == Kind.WORD && !RESERVED.contains(t.lower)))) {
				throw unexpected(t);
			}
			List<Token> parts = new ArrayList<>();
			parts.add(next());
			while (peek().kind == Kind.DOT) {
				next();
				Token part = next();
				if (part.kind != Kind.WORD && part.kind != Kind.QIDENT) {
					throw unexpected(part);
				}
				parts.add(part);
			}
			if (peek().kind == Kind.LPAREN) {
				Token fn = parts.get(0);
				if (parts.size() != 1 || fn.kind != Kind.WORD || !TABLE_FUNCTIONS.contains(fn.lower)) {
					throw new ServiceException(I18n.get("查询里不能把函数当成表来查: {0}", clip(name(parts))));
				}
				parseTableFunction();
				return;
			}
			if (lateral) {
				throw unexpected(parts.get(0));
			}
			checkTable(parts);
			parseAlias();
		}

		/** generate_series(...) 这类不读表的函数：参数按普通表达式检查，里面的子查询照样核对表 */
		private void parseTableFunction() {
			enter();
			next();
			walk(Mode.LIST);
			expect(Kind.RPAREN);
			leave();
			if (isWord(peek(), "with") && isWord(peek(1), "ordinality")) {
				next();
				next();
			}
			parseAlias();
		}

		/** 刚吃掉 FROM 里的一个左括号：里面是子查询（true），还是带括号的连接（false） */
		private boolean startsQuery() {
			int k = pos;
			while (toks.get(k).kind == Kind.LPAREN) {
				k++;
			}
			Token first = toks.get(k);
			if (!(isWord(first, "select") || isWord(first, "with"))) {
				return false;
			}
			if (k == pos) {
				return true;
			}
			// ((select ...) ...：内层括号后面跟的是别名，说明它只是连接里的第一项
			Token after = toks.get(Math.min(matching(pos) + 1, toks.size() - 1));
			if (after.kind == Kind.QIDENT) {
				return false;
			}
			if (after.kind == Kind.WORD) {
				return SET_OPS.contains(after.lower) || TAIL_WORDS.contains(after.lower);
			}
			return true;
		}

		private int matching(int open) {
			int level = 0;
			for (int k = open; k < toks.size(); k++) {
				Kind kind = toks.get(k).kind;
				if (kind == Kind.LPAREN) {
					level++;
				} else if (kind == Kind.RPAREN && --level == 0) {
					return k;
				}
			}
			return toks.size() - 1;
		}

		/**
		 * 列名清单只认写了 as 的写法（as t(a, b)）。没有 as 时，「某个词 + 括号」和别的数据库里
		 * 「连接关键字 + 括号里的表」分不开，所以不认。
		 */
		private void parseAlias() {
			Token t = peek();
			if (isWord(t, "as")) {
				next();
				Token alias = next();
				if (alias.kind != Kind.WORD && alias.kind != Kind.QIDENT) {
					throw unexpected(alias);
				}
				if (peek().kind == Kind.LPAREN) {
					parseNameList();
				}
				return;
			}
			if (t.kind == Kind.QIDENT || (t.kind == Kind.WORD && !RESERVED.contains(t.lower))) {
				next();
			}
		}

		private void parseNameList() {
			expect(Kind.LPAREN);
			while (true) {
				Token name = next();
				if (name.kind != Kind.WORD && name.kind != Kind.QIDENT) {
					throw unexpected(name);
				}
				if (peek().kind != Kind.COMMA) {
					break;
				}
				next();
			}
			expect(Kind.RPAREN);
		}

		private void checkTable(List<Token> parts) {
			if (parts.size() == 1 && isCte(parts.get(0).text)) {
				return;
			}
			// dual 是 Oracle、达梦、MySQL 里只有一行的占位表（select sysdate from dual），不存业务数据，不用进白名单
			if (parts.size() == 1 && isWord(parts.get(0), "dual")) {
				return;
			}
			String name = name(parts);
			tables.add(name);
			if (!whitelist.isEmpty() && !whitelist.contains(name)) {
				throw new ServiceException(I18n.get("SQL 引用了未授权表: {0}", clip(name)));
			}
		}

		private boolean isCte(String raw) {
			for (Set<String> scope : cteScopes) {
				if (scope.contains(raw)) {
					return true;
				}
			}
			return false;
		}

		/**
		 * 走完一段表达式（选择列表、WHERE、ON、GROUP BY、ORDER BY、LIMIT……），停在这一段结束的地方。
		 * 括号里的子查询交给 parseQuery；函数调用核对清单；会改变语句结构的词只能出现在该出现的位置。
		 */
		private void walk(Mode mode) {
			Deque<String> groups = new ArrayDeque<>();
			while (true) {
				Token t = peek();
				Token prev = toks.get(pos - 1);
				switch (t.kind) {
					case EOF -> {
						if (!groups.isEmpty()) {
							throw unsafe();
						}
						return;
					}
					case RPAREN -> {
						if (groups.isEmpty()) {
							return;
						}
						groups.pop();
						next();
					}
					case LPAREN -> {
						String opener = opener(prev, t);
						Token inner = peek(1);
						next();
						if (isWord(inner, "select") || isWord(inner, "with")) {
							parseQuery();
							expect(Kind.RPAREN);
						} else {
							if (groups.size() >= MAX_GROUPS) {
								throw new ServiceException("查询嵌套太深，请简化后再试");
							}
							groups.push(opener);
						}
					}
					case COMMA -> {
						if (groups.isEmpty()) {
							if (mode == Mode.ON) {
								return;
							}
							if (mode == Mode.WHERE) {
								throw unexpected(t);
							}
						}
						next();
					}
					case DOT -> {
						Token after = peek(1);
						if (isSlot(prev) || isSlot(after)) {
							throw new ServiceException(MSG_VAR_POSITION);
						}
						boolean left = prev.kind == Kind.WORD || prev.kind == Kind.QIDENT;
						boolean right = after.kind == Kind.WORD || after.kind == Kind.QIDENT
							|| (after.kind == Kind.OP && after.text.equals("*"));
						if (!left || !right) {
							throw unexpected(t);
						}
						next();
					}
					case SLOT -> {
						checkSlot(mode, groups.isEmpty(), prev);
						next();
					}
					case STRSLOT -> {
						checkStringSlot(prev, t);
						next();
					}
					case WORD -> {
						if (!word(mode, groups, prev, t)) {
							return;
						}
						next();
					}
					case QIDENT -> {
						// 序列的取值写法加了引号也一样（seq."NEXTVAL"）
						if (prev.kind == Kind.DOT && (t.lower.equals("nextval") || t.lower.equals("currval"))) {
							throw unsupported(t.text);
						}
						next();
					}
					default -> next();
				}
			}
		}

		/** 左括号是谁打开的：函数名（小写），普通括号返回空串 */
		private String opener(Token prev, Token paren) {
			switch (prev.kind) {
				case WORD -> {
					if (pos >= 2 && toks.get(pos - 2).kind == Kind.DOT) {
						throw function(prev.text);
					}
					if (PAREN_WORDS.contains(prev.lower)) {
						return "";
					}
					if (FUNCTIONS.contains(prev.lower) || extraFunctions.contains(prev.lower)) {
						return prev.lower;
					}
					throw function(prev.text);
				}
				case QIDENT -> throw function(prev.text);
				case SLOT, STRSLOT -> throw new ServiceException(MSG_VAR_POSITION);
				case OP, COMMA, LPAREN -> {
					return "";
				}
				default -> throw unexpected(paren);
			}
		}

		/** @return false 表示这一段到此为止，当前词留给上一层处理 */
		private boolean word(Mode mode, Deque<String> groups, Token prev, Token t) {
			String w = t.lower;
			Token after = peek(1);
			if (prev.kind == Kind.DOT) {
				// 点号后面的词是列名，不当关键字；序列的取值写法除外
				if (w.equals("nextval") || w.equals("currval")) {
					throw unsupported(t.text);
				}
				return true;
			}
			// next value for 序列名：MariaDB 等数据库取序列值的写法，会推进序列
			if (w.equals("value") && isWord(prev, "next") && isWord(after, "for")) {
				throw unsupported("next value for");
			}
			boolean top = groups.isEmpty();
			if (w.equals("from")) {
				if (isDistinctFrom()) {
					return true;
				}
				if (top) {
					return false;
				}
				if (FROM_OPENERS.contains(groups.peek())) {
					return true;
				}
				throw unexpected(t);
			}
			if (w.equals("for")) {
				if (top) {
					return false;
				}
				if (FOR_OPENERS.contains(groups.peek())) {
					return true;
				}
				throw unexpected(t);
			}
			if (w.equals("group") && isWord(prev, "within")) {
				return true;
			}
			if (w.equals("with")) {
				if (after.kind == Kind.WORD && WITH_FOLLOWERS.contains(after.lower)) {
					return true;
				}
				throw unexpected(t);
			}
			if (SET_OPS.contains(w)) {
				if (top) {
					return false;
				}
				throw unexpected(t);
			}
			if (top && CLAUSE_WORDS.contains(w)) {
				return false;
			}
			if (top && mode == Mode.ON) {
				if (JOIN_WORDS.contains(w)) {
					return false;
				}
				if ((w.equals("left") || w.equals("right")) && after.kind != Kind.LPAREN) {
					return false;
				}
				// ON 条件里，一个值后面只能跟认得的关键字。认不出的词可能是某种数据库的连接关键字，
				// 放过去的话它后面的表名就绕过了白名单
				if (!ON_KEYWORDS.contains(w) && !startsValue(prev)) {
					throw unexpected(t);
				}
			}
			if ((w.equals("truncate") || w.equals("replace")) && after.kind != Kind.LPAREN) {
				throw unexpected(t);
			}
			if (BANNED_WORDS.contains(w)) {
				throw unexpected(t);
			}
			return true;
		}

		private boolean startsValue(Token prev) {
			return prev.kind == Kind.OP || (prev.kind == Kind.WORD && ON_BEFORE_VALUE.contains(prev.lower));
		}

		/** 当前词是 from：它是不是 is [not] distinct from 的一部分 */
		private boolean isDistinctFrom() {
			if (pos < 2 || !isWord(toks.get(pos - 1), "distinct")) {
				return false;
			}
			int is = isWord(toks.get(pos - 2), "not") ? pos - 3 : pos - 2;
			// 点号后面的 is 是列名，不是关键字
			return is >= 1 && isWord(toks.get(is), "is") && toks.get(is - 1).kind != Kind.DOT;
		}

		private void checkSlot(Mode mode, boolean top, Token prev) {
			Token after = peek(1);
			boolean bad = after.kind == Kind.LPAREN || isWord(prev, "as") || isSlot(prev)
				|| prev.kind == Kind.QIDENT || prev.kind == Kind.NUMBER || prev.kind == Kind.STRING
				|| prev.kind == Kind.RPAREN;
			if (!bad && top && (mode == Mode.ORDER_BY || mode == Mode.GROUP_BY)) {
				// 整个排序项或分组项就是一个变量：绑定成值以后不会按那一列排，直接说清楚
				bad = (isWord(prev, "by") || prev.kind == Kind.COMMA) && after.kind != Kind.OP;
			}
			if (bad) {
				throw new ServiceException(MSG_VAR_POSITION);
			}
		}

		private void checkStringSlot(Token prev, Token t) {
			if (isWord(prev, "as")) {
				throw new ServiceException(MSG_VAR_POSITION);
			}
			if (prev.kind != Kind.WORD) {
				return;
			}
			// E'…'、N'…'、_utf8'…' 这类带前缀的字符串换成 ? 以后前缀会变成另一个词
			if (!t.spaceBefore && (prev.text.length() == 1 || prev.text.startsWith("_"))) {
				throw unsupported(prev.text + t.text);
			}
			boolean column = pos >= 2 && toks.get(pos - 2).kind == Kind.DOT;
			if (!column && TYPED_LITERALS.contains(prev.lower)) {
				throw new ServiceException(I18n.get("「{0} ''…''」这种写法里不能带变量，请改成 cast(''…'' as {0})", prev.text));
			}
		}

		private void enter() {
			if (++depth > MAX_DEPTH) {
				throw new ServiceException("查询嵌套太深，请简化后再试");
			}
		}

		private void leave() {
			depth--;
		}

		private Token peek() {
			return toks.get(pos);
		}

		private Token peek(int ahead) {
			return toks.get(Math.min(pos + ahead, toks.size() - 1));
		}

		private Token next() {
			Token t = toks.get(pos);
			if (t.kind != Kind.EOF) {
				pos++;
			}
			return t;
		}

		private void expect(Kind kind) {
			Token t = next();
			if (t.kind != kind) {
				throw unexpected(t);
			}
		}

		private void expectWord(String word) {
			Token t = next();
			if (!isWord(t, word)) {
				throw unexpected(t);
			}
		}
	}

	// ---------------------------------------------------------------- 小工具

	private static boolean isWord(Token t, String word) {
		return t.kind == Kind.WORD && t.lower.equals(word);
	}

	private static boolean isSlot(Token t) {
		return t.kind == Kind.SLOT || t.kind == Kind.STRSLOT;
	}

	private static String name(List<Token> parts) {
		StringBuilder sb = new StringBuilder();
		for (Token part : parts) {
			if (sb.length() > 0) {
				sb.append('.');
			}
			sb.append(part.lower);
		}
		return sb.toString();
	}

	private static Set<String> words(String list) {
		return Set.copyOf(Arrays.asList(list.trim().split("\\s+")));
	}

	private static ServiceException unsafe() {
		return new ServiceException(AiConstants.MSG_SQL_UNSAFE);
	}

	private static ServiceException unsupported(String what) {
		return new ServiceException(I18n.get("查询里有不支持的写法: {0}", clip(what)));
	}

	private static ServiceException function(String name) {
		return new ServiceException(I18n.get("查询里用了不允许的函数: {0}", clip(name)));
	}

	private static ServiceException unexpected(Token t) {
		if (isSlot(t)) {
			return new ServiceException(MSG_VAR_POSITION);
		}
		if (t.kind == Kind.EOF || (t.kind == Kind.WORD && UNSAFE_WORDS.contains(t.lower))) {
			return unsafe();
		}
		return unsupported(t.text);
	}

	private static String clip(String s) {
		return s.length() > 40 ? s.substring(0, 40) + "…" : s;
	}

	private static String show(char c) {
		if (c < 0x20 || c == 0x7f || Character.isISOControl(c) || Character.isWhitespace(c) || Character.isSurrogate(c)) {
			return String.format("U+%04X", (int) c);
		}
		return String.valueOf(c);
	}
}
