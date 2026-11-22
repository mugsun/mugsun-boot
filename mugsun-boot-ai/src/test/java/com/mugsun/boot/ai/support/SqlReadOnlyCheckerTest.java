package com.mugsun.boot.ai.support;

import com.mugsun.boot.ai.AiConstants;
import com.mugsun.core.tool.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SqlReadOnlyCheckerTest {

	private static final Set<String> SALES = Set.of("wdx074_sales");

	private static String sql(String sql) {
		return SqlReadOnlyChecker.check(sql, SALES).sql();
	}

	private static void passes(String sql) {
		assertDoesNotThrow(() -> SqlReadOnlyChecker.check(sql, SALES), sql);
	}

	private static ServiceException rejected(String sql) {
		return assertThrows(ServiceException.class, () -> SqlReadOnlyChecker.check(sql, SALES), sql);
	}

	private static void unsafe(String sql) {
		assertEquals(AiConstants.MSG_SQL_UNSAFE, rejected(sql).getMessage(), sql);
	}

	private static void unauthorized(String sql, String table) {
		assertEquals("SQL 引用了未授权表: " + table, rejected(sql).getMessage(), sql);
	}

	private static SqlReadOnlyChecker.Param param(Object value, boolean quoted) {
		return new SqlReadOnlyChecker.Param(value, quoted);
	}

	@Test
	void keepsTheQueryAndDropsComments() {
		assertEquals("select 1", SqlReadOnlyChecker.check("select 1", Set.of()).sql());
		assertEquals("select 1", SqlReadOnlyChecker.check(" select 1 ; ", Set.of()).sql());
		assertEquals("WITH a AS (SELECT 1) SELECT * FROM a",
			SqlReadOnlyChecker.check("WITH a AS (SELECT 1) SELECT * FROM a", Set.of()).sql());
		assertEquals("select id from wdx074_sales where region = 'a  b'",
			sql("select id -- 注释里的 ; delete\n from wdx074_sales /* into x */ where  region = 'a  b'"));
		assertEquals("select '${x} {{y}}' from wdx074_sales", sql("select '${x} {{y}}' from wdx074_sales"));
	}

	@Test
	void rejectsAnythingButOneSelect() {
		for (String s : List.of("delete from t", "update t set a=1", "insert into t values(1)", "drop table t",
			"select 1; delete from t", "select * into t2 from t", "SELECT id FROM t FOR UPDATE",
			"select 1 /* */; drop table t", "select * from t --\ninto evil from t",
			"explain analyze select 1", "truncate table t", "call p()", "set statement_timeout = 0",
			"copy t to stdout", "select * from t for share", "select * from t limit 1 for update",
			"select * into outfile '/tmp/x' from t", "select * from t into outfile '/tmp/x'",
			"select 1; select 2", "", "   ", ";")) {
			assertEquals(AiConstants.MSG_SQL_UNSAFE, assertThrows(ServiceException.class,
				() -> SqlReadOnlyChecker.check(s, Set.of()), s).getMessage(), s);
		}
		assertThrows(ServiceException.class, () -> SqlReadOnlyChecker.check(null, Set.of()));
	}

	@Test
	void collectsTablesAndChecksWhitelist() {
		assertEquals(Set.of("users", "orders"),
			SqlReadOnlyChecker.check("SELECT * FROM users u JOIN orders o ON u.id=o.uid", Set.of()).tables());
		assertDoesNotThrow(() -> SqlReadOnlyChecker.check("select * from users", Set.of("users")));
		assertDoesNotThrow(() -> SqlReadOnlyChecker.check("select * from secrets", Set.of()));
		assertThrows(ServiceException.class, () -> SqlReadOnlyChecker.check("select * from secrets", Set.of("users")));
		assertDoesNotThrow(() -> SqlReadOnlyChecker.check("select * from USERS", Set.of(" Users ")));
		assertEquals(Set.of(), SqlReadOnlyChecker.check("select now()", Set.of("users")).tables());
		assertEquals(Set.of("public.wdx074_sales"),
			SqlReadOnlyChecker.check("select * from Public.WDX074_SALES", Set.of("public.wdx074_sales")).tables());
	}

	@Test
	void everyTableReferenceIsChecked() {
		unauthorized("select s.id, u.account from wdx074_sales s, sys_user u", "sys_user");
		unauthorized("select * from wdx074_sales s cross join sys_user u", "sys_user");
		unauthorized("select * from wdx074_sales s natural join sys_user", "sys_user");
		unauthorized("select * from wdx074_sales s left outer join sys_user u on u.id = s.id", "sys_user");
		unauthorized("select * from wdx074_sales s join lateral (select * from sys_user) x on true", "sys_user");
		unauthorized("select * from wdx074_sales s join wdx074_sales t on s.id = t.id, sys_user u", "sys_user");
		unauthorized("select * from (wdx074_sales s join sys_user u on true)", "sys_user");
		unauthorized("select * from (select * from sys_user) x", "sys_user");
		unauthorized("select * from ((select * from sys_user) x join wdx074_sales s on true)", "sys_user");
		unauthorized("select * from ((select region from wdx074_sales) union (select account from sys_user)) q",
			"sys_user");
		unauthorized("select * from wdx074_sales s, `sys_user` u", "sys_user");
		unauthorized("select (select password from sys_user limit 1) from wdx074_sales", "sys_user");
		unauthorized("select * from wdx074_sales where id in (select id from sys_user)", "sys_user");
		unauthorized("select * from wdx074_sales where exists (select 1 from sys_user)", "sys_user");
		unauthorized("select region from wdx074_sales union all select account from sys_user", "sys_user");
		unauthorized("select region from wdx074_sales order by (select max(id) from sys_user)", "sys_user");
		unauthorized("with x as (select * from sys_user) select * from x", "sys_user");
		unauthorized("select * from \"sys_user\"", "sys_user");
		unauthorized("select * from `sys_user`", "sys_user");
		unauthorized("select * from SYS_USER", "sys_user");
		unauthorized("select * from public.sys_user", "public.sys_user");
		unauthorized("select * from public.wdx074_sales", "public.wdx074_sales");
		unauthorized("select * from generate_series(1, (select count(*) from sys_user)) g", "sys_user");
		unauthorized("select t.is distinct from sys_user", "sys_user");
		rejected("select password, 1.from sys_user");
		rejected("select * from wdx074_sales s straight_join sys_user u");
		rejected("select * from wdx074_sales s cross apply sys_user u");
		rejected("select * from wdx074_sales s where s.id = 1 join sys_user u");
		rejected("select * from wdx074_sales s (sys_user)");
		rejected("select * from wdx074_sales tablesample bernoulli (10)");
		rejected("select * from wdx074_sales s join wdx074_sales t on s.id = t.id anti join sys_user u on true");
	}

	@Test
	void withNamesShadowTablesOnlyWhereTheDatabaseAgrees() {
		assertEquals(Set.of(), SqlReadOnlyChecker.check(
			"with sys_user as (select 1 as id) select * from sys_user", SALES).tables());
		unauthorized("with sys_user as (select * from sys_user) select * from sys_user", "sys_user");
		unauthorized("with \"SYS_USER\" as (select 1 as id) select * from sys_user", "sys_user");
		unauthorized("with sys_user as (select 1 as id) select * from SYS_USER", "sys_user");
		unauthorized("select * from (with sys_user as (select 1 as id) select * from sys_user) q, sys_user", "sys_user");
		unauthorized("with a as (select * from b), b as (select 1) select * from a", "b");
		assertEquals(Set.of(), SqlReadOnlyChecker.check(
			"with recursive t as (select 1 as n union all select n + 1 from t where n < 5) select * from t", SALES).tables());
		unauthorized("with recursive a as (select * from sys_user), sys_user as (select 1 as id) select * from a", "sys_user");
	}

	@Test
	void statementsThatWriteAreRejected() {
		unsafe("with x as (update wdx074_sales set amount = 0.01 where id = 1 returning id) select * from x");
		unsafe("with x as (delete from wdx074_sales returning *) select * from x");
		unsafe("with x as (insert into wdx074_sales (id) values (1) returning id) select * from x");
		unsafe("select * from wdx074_sales for update");
		unsafe("select * from wdx074_sales where id = 1 for no key update");
		rejected("select * from wdx074_sales where id = (delete from wdx074_sales returning id)");
		rejected("select * from wdx074_sales s lock in share mode");
	}

	@Test
	void functionsMustBeOnTheAllowList() {
		for (String fn : List.of("query_to_xml('select * from sys_user', true, true, '')", "pg_sleep(10)",
			"pg_read_file('/etc/passwd')", "version()", "current_setting('server_version')",
			"set_config('a', 'b', false)", "nextval('seq')", "dblink('a', 'b')", "load_file('/etc/passwd')",
			"sleep(5)", "benchmark(1000000, md5('a'))", "lo_import('/etc/passwd')", "my_function(1)")) {
			String name = fn.substring(0, fn.indexOf('('));
			assertEquals("查询里用了不允许的函数: " + name,
				rejected("select " + fn + " from wdx074_sales").getMessage(), fn);
		}
		rejected("select public.lower('a') from wdx074_sales");
		rejected("select \"lower\"('a') from wdx074_sales");
		rejected("select s.nextval from wdx074_sales s");
		rejected("select current_user");
		assertEquals("查询里不能把函数当成表来查: query_to_xml",
			rejected("select * from query_to_xml('select 1', true, true, '') x").getMessage());
		rejected("select * from wdx074_sales, dblink('a', 'select 1') as t(a)");
		passes("select g.n, s.region from generate_series(1, 3) as g(n) left join wdx074_sales s on s.id = g.n");
		passes("select d from generate_series(current_date - 6, current_date, interval '1 day') d");
		passes("select * from unnest(string_to_array('a,b', ',')) with ordinality as t(v, i)");
		passes("select * from wdx074_sales s, lateral generate_series(1, 2) g");
	}

	@Test
	void extraFunctionsComeFromConfiguration() {
		rejected("select my_fn(amount) from wdx074_sales");
		try {
			assertEquals(List.of("pg_sleep", "query_to_xml", "a b", "select", "dblink_connect"),
				SqlReadOnlyChecker.setExtraFunctions(
					List.of(" My_Fn ", "pg_sleep", "query_to_xml", "a b", "select", "dblink_connect", " ")));
			passes("select MY_FN(amount) from wdx074_sales");
			rejected("select pg_sleep(1) from wdx074_sales");
			rejected("select query_to_xml('select 1', true, true, '') from wdx074_sales");
			rejected("select * from my_fn(1) t");
			new AiSqlProperties().setExtraFunctions(List.of("other_fn"));
			passes("select other_fn(amount) from wdx074_sales");
			rejected("select my_fn(amount) from wdx074_sales");
		} finally {
			assertTrue(SqlReadOnlyChecker.setExtraFunctions(null).isEmpty());
		}
		rejected("select other_fn(amount) from wdx074_sales");
	}

	@Test
	void dualNeedsNoWhitelistEntry() {
		passes("select sysdate from dual");
		assertEquals(Set.of(), SqlReadOnlyChecker.check("select 1 from DUAL", SALES).tables());
		unauthorized("select * from public.dual", "public.dual");
		unauthorized("select * from \"dual\"", "dual");
		unauthorized("select * from dual, sys_user", "sys_user");
	}

	@Test
	void commonQueriesPass() {
		passes("select region, sum(amount) as total, count(distinct product) cnt, round(avg(amount)::numeric, 2) "
			+ "from wdx074_sales where amount >= 10 and region <> '华北' and product like '产品%' "
			+ "group by region having sum(amount) > 100 order by total desc nulls last limit 10 offset 5");
		passes("select s.region as \"地区\", sum(s.amount) 总额 from wdx074_sales s group by s.region");
		passes("select region, row_number() over (partition by region order by amount desc) rn, sum(amount) over w, "
			+ "count(*) filter (where amount > 10) from wdx074_sales window w as (partition by region)");
		passes("select percentile_cont(0.5) within group (order by amount) from wdx074_sales");
		passes("select distinct on (region) region, product, amount from wdx074_sales order by region, amount desc");
		passes("select case when amount between 10 and 100 then '中' when amount > 100 then '大' else '小' end as size, "
			+ "count(*) from wdx074_sales group by 1");
		passes("select * from wdx074_sales a join wdx074_sales b using (id) "
			+ "left join wdx074_sales c on c.id = a.id and c.amount is not null "
			+ "where exists (select 1 from wdx074_sales d where d.id = a.id) and a.region in ('华南', '华北') "
			+ "and a.product is distinct from b.product");
		passes("select to_char(now(), 'YYYY-MM') m, date_trunc('month', current_date), extract(year from now()), "
			+ "substring(region from 1 for 2), trim(both ' ' from region), cast(amount as decimal(10,2)), "
			+ "amount::text || 'x', now() - interval '7 days', date '2024-01-01' from wdx074_sales");
		passes("select `region`, date_format(now(), '%Y-%m'), ifnull(amount, 0), if(amount > 1, 'a', 'b'), "
			+ "left(region, 1), replace(region, 'a', 'b'), truncate(amount, 1), group_concat(product separator ',') "
			+ "from `wdx074_sales` where amount <=> 1 limit 5, 10");
		passes("(select region from wdx074_sales) union all (select product from wdx074_sales) "
			+ "order by 1 fetch first 5 rows only");
		passes("select region, product, sum(amount) from wdx074_sales group by rollup (region, product)");
		passes("select a.id from wdx074_sales a left join wdx074_sales b on left(a.region, 1) = right(b.region, 1) "
			+ "and a.amount between b.amount - 1 and b.amount + 1 inner join wdx074_sales c on c.id = a.id");
		passes("select `地区`, `amount_2`, \"小 计\" from `wdx074_sales` where region = \"华 南\"");
		assertEquals("select amount <=> 1, data->>'k', a<>b, -1 from wdx074_sales",
			sql("select amount <=> 1, data->>'k', a<>b, -1 from wdx074_sales"));
	}

	@Test
	void charactersThatDatabasesReadDifferentlyAreRejected() {
		for (String s : List.of("select 'a\\b' from wdx074_sales", "select $$x$$", "select $1", "select 1 # c",
			"select @@version", "select @a", "select amount & 1 from wdx074_sales",
			"select amount | 1 from wdx074_sales", "select tags[1] from wdx074_sales", "select {fn now()}",
			"select ? from wdx074_sales", "select 'abc", "select 1 /* x", "select 0x1F", "select 1e",
			"select \"a\"\"b\" from wdx074_sales", "select 1 from wdx074_sales where id = 1\u3000or 1 = 1",
			"select 1\u0000+ 1", "select ${x} from wdx074_sales", "select `a b` from wdx074_sales",
			"select 1 from wdx074_sales where 1 = ` 1 union select password from sys_user where 1 = ` 1")) {
			rejected(s);
		}
	}

	@Test
	void wordsGluedToAStringCannotActAsAPrefix() {
		// Oracle 一类数据库里 q'Z…Z' 到定界符后面的引号才结束：这里当成字符串的那一段，到了那边是代码
		assertEquals("查询里有不支持的写法: q'…'",
			rejected("select q'Za' bZ' as c from sys_user --' from wdx074_sales").getMessage());
		for (String s : List.of("select q'[a]' from wdx074_sales", "select Q'!a!' from wdx074_sales",
			"select nq'[a]' from wdx074_sales", "select NQ'[a]' from wdx074_sales",
			"select s.q'[a]' from wdx074_sales s", "select r'a' from wdx074_sales", "select u'a' from wdx074_sales",
			"select aq'a' from wdx074_sales", "select _q'a' from wdx074_sales",
			"select 1 from wdx074_sales where region is'a'")) {
			rejected(s);
		}
		assertThrows(ServiceException.class,
			() -> SqlReadOnlyChecker.checkTemplate("select q'[${x}]' from wdx074_sales", SALES));
		// 中间有空格或注释，就只是一个词加一个普通字符串，各家数据库读出来都一样
		passes("select q, 'a', q 'a' from wdx074_sales");
		assertEquals("select q 'a' from wdx074_sales", sql("select q/* */'a' from wdx074_sales"));
		// 不改变字符串在哪里结束的前缀照原样保留
		assertEquals("select N'华南', e'a', B'01', x'1F', _utf8'a' from wdx074_sales",
			sql("select N'华南', e'a', B'01', x'1F', _utf8'a' from wdx074_sales"));
		// 其余的词拼回去时和引号隔开，数据库没法把它当前缀
		assertEquals("select 1 as 'a', date '2024-01-01', interval '1 day' from wdx074_sales"
				+ " where region like '华%' or 'a'='a'",
			sql("select 1 as'a', date'2024-01-01', interval'1 day' from wdx074_sales where region like'华%' or'a'='a'"));
		assertEquals("select abc 'a' from wdx074_sales", sql("select abc'a' from wdx074_sales"));
		assertEquals("select region from wdx074_sales where region in('a','b') order by 'a'",
			sql("select region from wdx074_sales where region in('a','b') order by'a'"));
	}

	@Test
	void doubleSlashIsRejected() {
		// 达梦的手册把 // 当单行注释用；字符串里可以换行，注释吃掉开头的引号以后，引号里的内容就成了代码
		assertEquals("查询里有不支持的写法: //", rejected("select 1 // '\n from sys_user -- '").getMessage());
		rejected("select amount // 2 from wdx074_sales");
		assertEquals("select amount / 2 / 3, amount / 2 from wdx074_sales",
			sql("select amount / 2 / 3, amount / /* x */ 2 from wdx074_sales"));
		assertEquals("select 'a // b' from wdx074_sales", sql("select 'a // b' from wdx074_sales"));
	}

	@Test
	void sequenceValuesCannotBeTaken() {
		for (String s : List.of("select s.nextval from wdx074_sales s", "select s.CURRVAL from wdx074_sales s",
			"select s.\"NEXTVAL\" from wdx074_sales s", "select s.`currval` from wdx074_sales s",
			"select next value for seq from wdx074_sales",
			"select substring(next value for seq from 1 for 1) from wdx074_sales",
			"select overlay(region placing 'a' from next value for seq for 1) from wdx074_sales")) {
			rejected(s);
		}
		assertEquals("查询里有不支持的写法: \"NEXTVAL\"",
			rejected("select s.\"NEXTVAL\" from wdx074_sales s").getMessage());
		assertEquals("查询里有不支持的写法: next value for",
			rejected("select substring(next value for seq from 1 for 1) from wdx074_sales").getMessage());
		passes("select region from wdx074_sales order by id offset 5 rows fetch next 5 rows only");
		passes("select value, next, s.\"value\", s.\"next\" from wdx074_sales s");
		passes("select next value from wdx074_sales");
	}

	@Test
	void limitsLengthAndNesting() {
		assertTrue(rejected("select '" + "x".repeat(20_001) + "'").getMessage().contains("20000"));
		assertEquals("查询嵌套太深，请简化后再试", rejected("select (".repeat(40) + "1" + ")".repeat(40)).getMessage());
		assertEquals("查询嵌套太深，请简化后再试", rejected("select " + "(".repeat(70) + "1" + ")".repeat(70)).getMessage());
	}

	@Test
	void templateTurnsVariablesIntoParameters() {
		SqlReadOnlyChecker.Plan plan = SqlReadOnlyChecker.checkTemplate(
			"select id, region from wdx074_sales where region = '${region}' order by id limit ${n}", SALES);
		assertTrue(plan.hasSlots());
		assertEquals(List.of("region", "n"), plan.slotNames());
		assertThrows(IllegalStateException.class, plan::sql);

		String attack = "华南' union select account, password from sys_user --";
		SqlReadOnlyChecker.Bound bound = plan.bind(Map.of("region", attack, "n", 5));
		assertEquals("select id, region from wdx074_sales where region = ? order by id limit ?", bound.sql());
		assertEquals(List.of(param(attack, true), param(5L, false)), bound.params());
	}

	@Test
	void templateSupportsBothPlaceholderStylesAndTextAroundVariables() {
		SqlReadOnlyChecker.Plan plan = SqlReadOnlyChecker.checkTemplate(
			"select * from wdx074_sales where region = {{region}} and product like '%${ input.kw }%' "
				+ "and region <> '${region}'", SALES);
		assertEquals(List.of("region", "input.kw"), plan.slotNames());
		SqlReadOnlyChecker.Bound bound = plan.bind(Map.of("region", "华南", "input.kw", "产品"));
		assertEquals("select * from wdx074_sales where region = ? and product like ? and region <> ?", bound.sql());
		assertEquals(List.of(param("华南", false), param("%产品%", true), param("华南", true)), bound.params());
	}

	@Test
	void templateExpandsListsAndNormalizesValues() {
		SqlReadOnlyChecker.Plan plan = SqlReadOnlyChecker.checkTemplate(
			"select * from wdx074_sales where id in (${ids})", SALES);
		SqlReadOnlyChecker.Bound three = plan.bind(Map.of("ids", List.of(1, 2, 3)));
		assertEquals("select * from wdx074_sales where id in ( ?, ?, ? )", three.sql());
		assertEquals(List.of(param(1L, false), param(2L, false), param(3L, false)), three.params());
		SqlReadOnlyChecker.Bound none = plan.bind(Map.of("ids", List.of()));
		assertEquals("select * from wdx074_sales where id in ( NULL )", none.sql());
		assertTrue(none.params().isEmpty());
		List<Integer> many = new ArrayList<>();
		for (int i = 0; i < 201; i++) {
			many.add(i);
		}
		assertThrows(ServiceException.class, () -> plan.bind(Map.of("ids", many)));
		assertEquals("变量「ids」是一组值，不能写在引号里。请去掉引号，写成 in (${ids})",
			assertThrows(ServiceException.class, () -> SqlReadOnlyChecker.checkTemplate(
				"select * from wdx074_sales where region in ('${ids}')", SALES).bind(Map.of("ids", List.of("a")))).getMessage());

		SqlReadOnlyChecker.Plan mixed = SqlReadOnlyChecker.checkTemplate(
			"select * from wdx074_sales where amount > ${a} and amount < ${b} and region = '${c}' and ${d} "
				+ "and id = '${e}'", SALES);
		Map<String, Object> values = new HashMap<>();
		values.put("a", 1.5d);
		values.put("b", new BigDecimal("9.90"));
		values.put("c", null);
		values.put("d", true);
		values.put("e", 7);
		assertEquals(List.of(param(BigDecimal.valueOf(1.5d), false), param(new BigDecimal("9.90"), false),
			param(null, true), param(true, false), param("7", true)), mixed.bind(values).params());
		values.put("a", Map.of("x", 1));
		assertThrows(ServiceException.class, () -> mixed.bind(values));
	}

	@Test
	void templateRejectsVariablesOutsideValuePositions() {
		for (String s : List.of("${sql}", "select * from ${t}", "select * from '${t}'",
			"select * from wdx074_sales ${a}", "select * from wdx074_sales s join ${t} on true",
			"select * from wdx074_sales order by ${col}", "select * from wdx074_sales order by ${col} desc",
			"select region from wdx074_sales group by ${col}", "select ${fn}(1) from wdx074_sales",
			"select amount as ${alias} from wdx074_sales", "select s.${col} from wdx074_sales s",
			"select \"${col}\" from wdx074_sales", "select * from wdx074_sales where id = 1 ${tail}",
			"select * from wdx074_sales where region = 'a' ${tail}",
			"select * from wdx074_sales where id = ${a}${b}")) {
			assertEquals(SqlReadOnlyChecker.MSG_VAR_POSITION, assertThrows(ServiceException.class,
				() -> SqlReadOnlyChecker.checkTemplate(s, SALES), s).getMessage(), s);
		}
		ServiceException typed = assertThrows(ServiceException.class, () -> SqlReadOnlyChecker.checkTemplate(
			"select * from wdx074_sales where created_at >= date '${d}'", SALES));
		assertTrue(typed.getMessage().contains("cast('…' as date)"), typed.getMessage());
		assertThrows(ServiceException.class,
			() -> SqlReadOnlyChecker.checkTemplate("select E'${x}' from wdx074_sales", SALES));
		assertEquals("SQL 引用了未授权表: sys_user", assertThrows(ServiceException.class,
			() -> SqlReadOnlyChecker.checkTemplate("select region from wdx074_sales where region = '${r}' "
				+ "union select account from sys_user", SALES)).getMessage());
	}

	@Test
	void templateWithoutVariablesBehavesLikeThePlainCheck() {
		SqlReadOnlyChecker.Plan plan = SqlReadOnlyChecker.checkTemplate("select 1 -- ${x}\n from wdx074_sales", SALES);
		assertFalse(plan.hasSlots());
		assertEquals("select 1 from wdx074_sales", plan.sql());
		SqlReadOnlyChecker.Bound bound = plan.bind(Map.of());
		assertEquals("select 1 from wdx074_sales", bound.sql());
		assertTrue(bound.params().isEmpty());

		SqlReadOnlyChecker.Plan twice = SqlReadOnlyChecker.checkTemplate(
			"select * from wdx074_sales where region = '${r}' or product = '${r}'", SALES);
		assertEquals(List.of("r"), twice.slotNames());
		assertEquals(2, twice.bind(Map.of("r", "x")).params().size());
	}
}
