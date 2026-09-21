package com.mugsun.boot.serial;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.mugsun.boot.serial.entity.SysSerialNumber;
import com.mugsun.boot.serial.entity.SysSerialNumberRecord;
import com.mugsun.boot.serial.mapper.SysSerialNumberMapper;
import com.mugsun.boot.serial.mapper.SysSerialNumberRecordMapper;
import com.mugsun.core.tool.api.R;
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
	public R<Page<SysSerialNumber>> page(@RequestParam(defaultValue = "1") long pageNum,
										 @RequestParam(defaultValue = "10") long pageSize) {
		return R.data(serialMapper.paginate(pageNum, pageSize, QueryWrapper.create().orderBy("id", false)));
	}

	@GetMapping("/detail")
	public R<SysSerialNumber> detail(@RequestParam Long id) {
		return R.data(serialMapper.selectOneById(id));
	}

	@PostMapping("/submit")
	public R<Void> submit(@RequestBody SysSerialNumber entity) {
		// 入库前校验格式可解析，避免非法规则污染
		SerialNumberInfo.parse(entity);
		if (entity.getId() == null) {
			serialMapper.insert(entity);
		} else {
			serialMapper.update(entity);
		}
		return R.success("操作成功");
	}

	@PostMapping("/remove")
	public R<Void> remove(@RequestBody List<Long> ids) {
		serialMapper.deleteBatchByIds(ids);
		return R.success("删除成功");
	}

	@PostMapping("/generate")
	public R<List<String>> generate(@RequestParam String code, @RequestParam(defaultValue = "1") int count) {
		return R.data(serialNumberService.generate(code, count));
	}

	@GetMapping("/record/page")
	public R<Page<SysSerialNumberRecord>> recordPage(@RequestParam(defaultValue = "1") long pageNum,
													 @RequestParam(defaultValue = "10") long pageSize,
													 @RequestParam(required = false) String code) {
		QueryWrapper query = QueryWrapper.create().orderBy("record_date", false).orderBy("id", false);
		if (code != null && !code.isBlank()) {
			query.eq("serial_code", code);
		}
		return R.data(recordMapper.paginate(pageNum, pageSize, query));
	}
}
