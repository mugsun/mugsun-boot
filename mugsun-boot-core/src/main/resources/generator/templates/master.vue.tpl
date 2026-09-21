<!-- #(functionName) 管理页（代码生成产物·主子表一对多·内嵌子表编辑） -->
<template>
  <div class="art-full-height">
    <ElCard class="art-table-card">
      <div style="margin-bottom: 12px">
        <ElButton @click="openAdd()" v-ripple>新增</ElButton>
      </div>
      <ElTable :data="list" border v-loading="loading">
#for(col : listColumns)
        <ElTableColumn prop="#(col.prop)" label="#(col.label)" min-width="140" />
#end
        <ElTableColumn label="操作" width="180" fixed="right">
          <template v-slot:default="{ row }">
            <ElButton link type="primary" size="small" @click="openEdit(row)">编辑</ElButton>
            <ElButton link type="danger" size="small" @click="remove(row)">删除</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      <div style="display: flex; justify-content: flex-end; margin-top: 12px">
        <ElPagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          :total="page.total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="load"
        />
      </div>
      <ElDialog v-model="dialogVisible" :title="currentRow.id ? '编辑' : '新增'" width="760px" align-center>
        <ElForm :model="currentRow" label-width="100px">
#for(col : formColumns)
          <ElFormItem label="#(col.label)">
            #(col.control)
          </ElFormItem>
#end
        </ElForm>
        <div style="margin: 8px 0; display: flex; align-items: center; gap: 8px">
          <b>#(childEntityName) 子表</b>
          <ElButton link type="primary" size="small" @click="addSub()">添加子行</ElButton>
        </div>
        <ElTable :data="currentRow.#(subListField)" border size="small">
#for(col : childFormColumns)
          <ElTableColumn label="#(col.comment)" min-width="120">
            <template v-slot:default="{ row }">
              #(col.control)
            </template>
          </ElTableColumn>
#end
          <ElTableColumn label="操作" width="70">
            <template v-slot:default="{ $index }">
              <ElButton link type="danger" size="small" @click="delSub($index)">删除</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
        <template v-slot:footer>
          <ElButton @click="dialogVisible = false">取消</ElButton>
          <ElButton type="primary" @click="submit">提交</ElButton>
        </template>
      </ElDialog>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { ref, reactive, onMounted } from 'vue'
  import { #(elImports) } from 'element-plus'
#if(dictCodes.size() > 0)
  import { useDict } from '@/hooks/core/useDict'
#end
  import {
    fetch#(entityName)Page,
    fetch#(entityName)Detail,
    fetchSave#(entityName),
    fetchRemove#(entityName)
  } from '@/api/#(module)/#(businessKebab)'

  defineOptions({ name: '#(entityName)' })
#if(dictCodes.size() > 0)

  const { #(dictVars) } = useDict(#(dictArgs))
#end

  const list = ref<any[]>([])
  const loading = ref(false)
  const dialogVisible = ref(false)
  const currentRow = ref<any>({ #(subListField): [] })
  const page = reactive({ current: 1, size: 20, total: 0 })

  const load = async (): Promise<void> => {
    loading.value = true
    try {
      const res: any = await fetch#(entityName)Page({ pageNum: page.current, pageSize: page.size })
      list.value = res?.records || []
      page.total = res?.totalRow || 0
    } finally {
      loading.value = false
    }
  }

  const openAdd = (): void => {
    currentRow.value = { #(subListField): [] }
    dialogVisible.value = true
  }

  const openEdit = async (row: any): Promise<void> => {
    const detail: any = await fetch#(entityName)Detail(row.id)
    detail.#(subListField) = detail.#(subListField) || []
    currentRow.value = detail
    dialogVisible.value = true
  }

  const addSub = (): void => {
    currentRow.value.#(subListField).push({})
  }

  const delSub = (i: number): void => {
    currentRow.value.#(subListField).splice(i, 1)
  }

  const submit = async (): Promise<void> => {
    await fetchSave#(entityName)(currentRow.value)
    ElMessage.success('操作成功')
    dialogVisible.value = false
    await load()
  }

  const remove = async (row: any): Promise<void> => {
    await ElMessageBox.confirm('确认删除该主表及其子表？', '提示', { type: 'warning' })
    await fetchRemove#(entityName)([row.id])
    ElMessage.success('删除成功')
    await load()
  }

  onMounted(load)
</script>
