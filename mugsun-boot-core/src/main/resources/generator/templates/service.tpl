package #(servicePkg);

import #(entityPkg).#(entityName);
import #(entityPkg).#(childEntityName);
import #(mapperPkg).#(mapperName);
import #(mapperPkg).#(childMapperName);
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * #(functionName)主子表级联服务。
 *
 * @author #(author)
 */
@Service
public class #(entityName)Service {

	private final #(mapperName) #(mapperVar);
	private final #(childMapperName) #(childMapperVar);

	public #(entityName)Service(#(mapperName) #(mapperVar), #(childMapperName) #(childMapperVar)) {
		this.#(mapperVar) = #(mapperVar);
		this.#(childMapperVar) = #(childMapperVar);
	}

	/** 详情：主表 + 子表列表 */
	public #(entityName) detail(Long id) {
		#(entityName) #(entityVar) = #(mapperVar).selectOneById(id);
		if (#(entityVar) != null) {
			#(entityVar).set#(subListCap)(#(childMapperVar).selectListByQuery(
				QueryWrapper.create().eq("#(subJoinColumn)", id)));
		}
		return #(entityVar);
	}

	/** 级联保存：存主表 → 子表按 id 差异处理（有 id 更新、无 id 新增、被移除的逻辑删，不再「删旧插新」累积软删行） */
	@Transactional(rollbackFor = Exception.class)
	public void save(#(entityName) #(entityVar)) {
		if (#(entityVar).getId() == null) {
			#(mapperVar).insertSelective(#(entityVar));
		} else {
			#(mapperVar).update(#(entityVar));
		}
		Long mainId = #(entityVar).getId();
		List<#(childEntityName)> subList = #(entityVar).get#(subListCap)() == null
			? java.util.List.of() : #(entityVar).get#(subListCap)();
		java.util.Set<Long> keepIds = new java.util.HashSet<>();
		for (#(childEntityName) sub : subList) {
			sub.set#(subJoinCap)(mainId);
			if (sub.getId() == null) {
				#(childMapperVar).insertSelective(sub);
			} else {
				#(childMapperVar).update(sub);
			}
			// 新插行由 insertSelective 回填雪花 id，与存量行一并纳入保留集，防被下方移除条件误删
			keepIds.add(sub.getId());
		}
		// 被移除的子行逻辑删（is_deleted=1，与平台逻辑删除口径一致）
		QueryWrapper removeQuery = QueryWrapper.create().eq("#(subJoinColumn)", mainId);
		if (!keepIds.isEmpty()) {
			removeQuery.notIn("id", keepIds);
		}
		#(childMapperVar).deleteByQuery(removeQuery);
	}

	/** 级联删除：删子表 → 删主表 */
	@Transactional(rollbackFor = Exception.class)
	public void remove(List<Long> ids) {
		if (ids != null && !ids.isEmpty()) {
			#(childMapperVar).deleteByQuery(QueryWrapper.create().in("#(subJoinColumn)", ids));
			#(mapperVar).deleteBatchByIds(ids);
		}
	}
}
