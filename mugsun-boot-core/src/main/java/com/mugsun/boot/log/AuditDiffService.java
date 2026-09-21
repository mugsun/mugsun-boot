package com.mugsun.boot.log;

import com.mugsun.boot.system.service.DictService;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 字段级变更 diff：反射比对 before/after，按 @AuditField 生成 [{label,old,new}]，dict 值翻译为中文。
 */
@Service
public class AuditDiffService {

	private final DictService dictService;

	public AuditDiffService(DictService dictService) {
		this.dictService = dictService;
	}

	public List<Map<String, String>> diff(Object before, Object after) {
		List<Map<String, String>> changes = new ArrayList<>();
		Object sample = after != null ? after : before;
		if (sample == null) {
			return changes;
		}
		for (Field f : auditFields(sample.getClass())) {
			AuditField ann = f.getAnnotation(AuditField.class);
			f.setAccessible(true);
			String oldS = render(ann, value(f, before));
			String newS = render(ann, value(f, after));
			if (!Objects.equals(oldS, newS)) {
				Map<String, String> c = new LinkedHashMap<>();
				c.put("label", ann.value());
				c.put("old", oldS == null ? "" : oldS);
				c.put("new", newS == null ? "" : newS);
				changes.add(c);
			}
		}
		return changes;
	}

	private List<Field> auditFields(Class<?> clazz) {
		List<Field> fields = new ArrayList<>();
		for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (f.isAnnotationPresent(AuditField.class)) {
					fields.add(f);
				}
			}
		}
		return fields;
	}

	private Object value(Field f, Object obj) {
		if (obj == null) {
			return null;
		}
		try {
			return f.get(obj);
		} catch (Exception e) {
			return null;
		}
	}

	private String render(AuditField ann, Object v) {
		if (v == null) {
			return null;
		}
		String s = String.valueOf(v);
		return ann.dict().isEmpty() ? s : dictService.translate(ann.dict(), s);
	}
}
