package com.mugsun.boot.serial;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.mugsun.boot.common.repeat.RepeatSubmit;
import com.mugsun.boot.serial.entity.SysSerialNumber;
import com.mugsun.boot.serial.entity.SysSerialNumberRecord;
import com.mugsun.boot.serial.mapper.SysSerialNumberMapper;
import com.mugsun.boot.serial.mapper.SysSerialNumberRecordMapper;
import com.mugsun.core.tool.api.R;
import com.mugsun.core.tool.exception.ServiceException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 单号生成器：规则维护 + 单号生成 + 生成记录查询。
 */
@RestController
@RequestMapping("/system/serial-number")
@SaCheckLogin
public class SerialNumberController {

	private final SysSerialNumberMapper serialMapper;
	private final SysSerialNumberRecordMapper recordMapper;
	private final SerialNumberService serialNumberService;

	public SerialNumberController(SysSerialNumberMapper serialMapper, SysSerialNumberRecordMapper recordMapper,
								  SerialNumberService serialNumberService) {
		this.serialMapper = serialMapper;
		this.recordMapper = recordMapper;
		this.serialNumberService = serialNumberService;
	}

	@GetMapping("/page")
	@SaCheckPermission("sys:serial:list")
	public R<Page<SysSerialNumber>> page(@RequestParam(defaultValue = "1") long pageNum,
										 @RequestParam(defaultValue = "10") long pageSize,
										 @RequestParam(required = false) String name,
										 @RequestParam(required = false) String code) {
		QueryWrapper query = QueryWrapper.create();
		if (name != null && !name.isBlank()) {
			query.like("business_name", name.trim());
		}
		if (code != null && !code.isBlank()) {
			query.like("code", code.trim());
		}
		query.orderBy("id", false);
		return R.data(serialMapper.paginate(pageNum, pageSize, query));
	}

	@GetMapping("/detail")
	@SaCheckPermission("sys:serial:list")
	public R<SysSerialNumber> detail(@RequestParam(required = false) Long id) {
		if (id == null) {
			throw new ServiceException("请从列表中选择一条单号规则后再查看");
		}
		SysSerialNumber row = serialMapper.selectOneById(id);
		if (row == null) {
			throw new ServiceException("这个单号规则不存在。请刷新列表后再看");
		}
		return R.data(row);
	}

	@SaCheckPermission("sys:serial:save")
	@RepeatSubmit
	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysSerialNumber entity) {
		if (entity == null) {
			throw new ServiceException("请填写单号编码");
		}
		boolean creating = entity.getId() == null;
		normalize(entity);
		SerialNumberInfo.parse(entity);
		if (creating) {
			SysSerialNumber taken = serialMapper.selectOneByQuery(QueryWrapper.create().eq("code", entity.getCode()));
			if (taken != null) {
				throw new ServiceException("单号编码「" + entity.getCode() + "」已经有了。请换一个编码，或编辑原来那个");
			}
			entity.setLastNumber(null);
			entity.setLastTime(null);
			serialMapper.insert(entity);
			return R.success("已保存。列表里可以查看这个规则的格式和当前序号");
		}
		SysSerialNumber before = serialMapper.selectOneById(entity.getId());
		if (before == null) {
			throw new ServiceException("这个单号规则不存在。请刷新列表后再编辑");
		}
		if (before.getCode() != null && !before.getCode().equals(entity.getCode())) {
			throw new ServiceException("单号编码创建后不能修改。请新增一条规则，再删除原来的");
		}
		entity.setCode(before.getCode());
		entity.setLastNumber(before.getLastNumber());
		entity.setLastTime(before.getLastTime());
		serialMapper.update(entity);
		return R.success("已保存。列表里可以查看这个规则的格式和当前序号");
	}

	@SaCheckPermission("sys:serial:remove")
	@RepeatSubmit
	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			throw new ServiceException("请先选择要删除的单号规则");
		}
		if (ids.stream().anyMatch(id -> id == null)) {
			throw new ServiceException("有规则不存在。请刷新列表后再删除");
		}
		List<SysSerialNumber> rows = serialMapper.selectListByIds(ids);
		if (rows.size() != ids.size()) {
			throw new ServiceException("有规则不存在，或已经被删除。请刷新列表后再试");
		}
		for (SysSerialNumber row : rows) {
			serialMapper.deleteById(row.getId());
		}
		return R.success("已删除 " + rows.size() + " 条规则。列表里不会再出现");
	}

	@SaCheckPermission("sys:serial:generate")
	@RepeatSubmit
	@PostMapping("/generate")
	public R<List<String>> generate(@RequestParam(required = false) String code,
									@RequestParam(defaultValue = "1") int count) {
		if (code == null || code.isBlank()) {
			throw new ServiceException("请填写单号编码。可以从列表里复制");
		}
		List<String> numbers = serialNumberService.generate(code.trim(), count);
		R<List<String>> result = R.data(numbers);
		result.setMsg("已生成 " + numbers.size() + " 个单号。列表里的当前序号会跟着更新");
		return result;
	}

	@GetMapping("/record/page")
	@SaCheckPermission("sys:serial:list")
	public R<Page<SysSerialNumberRecord>> recordPage(@RequestParam(defaultValue = "1") long pageNum,
													 @RequestParam(defaultValue = "10") long pageSize,
													 @RequestParam(required = false) String code) {
		QueryWrapper query = QueryWrapper.create().orderBy("record_date", false).orderBy("id", false);
		if (code != null && !code.isBlank()) {
			query.eq("serial_code", code.trim());
		}
		return R.data(recordMapper.paginate(pageNum, pageSize, query));
	}

	private void normalize(SysSerialNumber entity) {
		String code = entity.getCode() == null ? "" : entity.getCode().trim();
		if (code.isEmpty()) {
			throw new ServiceException("请填写单号编码");
		}
		if (!code.matches("^[A-Za-z][A-Za-z0-9_]{0,63}$")) {
			throw new ServiceException("单号编码请用字母开头，只能包含字母、数字和下划线");
		}
		entity.setCode(code);
		String name = entity.getBusinessName() == null ? "" : entity.getBusinessName().trim();
		if (name.isEmpty()) {
			throw new ServiceException("请填写业务名称");
		}
		if (name.length() > 64) {
			throw new ServiceException("业务名称请控制在 64 个字以内");
		}
		entity.setBusinessName(name);
		String format = entity.getFormat() == null ? "" : entity.getFormat().trim();
		if (format.length() > 64) {
			throw new ServiceException("单号格式请控制在 64 个字符以内");
		}
		entity.setFormat(format);
		String ruleType = entity.getRuleType() == null ? "" : entity.getRuleType().trim();
		entity.setRuleType(ruleType);
		Long init = entity.getInitNumber();
		if (init == null) {
			entity.setInitNumber(0L);
		} else if (init < 0 || init > 1_000_000_000_000L) {
			throw new ServiceException("初始值请填写 0 到 1000000000000 之间的整数");
		}
		String remark = entity.getRemark() == null ? "" : entity.getRemark().trim();
		if (remark.length() > 255) {
			throw new ServiceException("备注请控制在 255 个字以内");
		}
		entity.setRemark(remark.isEmpty() ? null : remark);
	}
}
