package com.mugsun.boot.gen;

import com.mybatisflex.codegen.Generator;
import com.mybatisflex.codegen.config.GlobalConfig;
import com.mybatisflex.codegen.entity.Column;
import com.mybatisflex.codegen.entity.Table;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库表内省：列出数据源全部表及列元数据，供代码生成选表导入。
 */
@Service
public class GenService {

	private final DataSource dataSource;

	public GenService(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/** 列出数据源全部表及列元数据 */
	public List<Map<String, Object>> tables() {
		GlobalConfig config = new GlobalConfig();
		config.getPackageConfig().setBasePackage("com.mugsun.preview");
		List<Table> tables = new Generator(dataSource, config).getTables();
		List<Map<String, Object>> result = new ArrayList<>();
		for (Table t : tables) {
			result.add(tableMeta(t));
		}
		result.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
		return result;
	}

	private Map<String, Object> tableMeta(Table t) {
		List<Map<String, Object>> cols = new ArrayList<>();
		if (t.getColumns() != null) {
			for (Column c : t.getColumns()) {
				Map<String, Object> col = new LinkedHashMap<>();
				col.put("name", c.getName());
				col.put("property", c.getProperty());
				col.put("type", c.getPropertyType());
				col.put("comment", c.getComment());
				cols.add(col);
			}
		}
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("name", t.getName());
		map.put("comment", t.getComment());
		map.put("columns", cols);
		return map;
	}
}
