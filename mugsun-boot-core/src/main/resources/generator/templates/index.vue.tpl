<!-- #(functionName) 管理页（代码生成产物·useCrud 组合式） -->
<template>
  <div class="art-full-height">
    <ElCard class="art-table-card">
      <div style="margin-bottom: 12px">
        <ElButton @click="showDialog('add')" v-ripple>新增</ElButton>
      </div>
      <ArtTable
        :loading="loading"
        :data="data as any[]"
        :columns="columns"
        :pagination="pagination"
        border
        @pagination:size-change="handleSizeChange"
        @pagination:current-change="handleCurrentChange"
      />
      <ElDialog v-model="dialogVisible" :title="dialogType === 'add' ? '新增' : '编辑'" width="520px" align-center>
        <ElForm :model="currentRow" label-width="100px">
#for(col : formColumns)
          <ElFormItem label="#(col.label)">
            #(col.control)
          </ElFormItem>
#end
        </ElForm>
        <template v-slot:footer>
          <ElButton @click="dialogVisible = false">取消</ElButton>
          <ElButton type="primary" @click="handleSubmit(currentRow)">提交</ElButton>
        </template>
      </ElDialog>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { h } from 'vue'
  import { #(elImports) } from 'element-plus'
  import { useCrud } from '@/hooks/core/useCrud'
#if(dictCodes.size() > 0)
  import { useDict } from '@/hooks/core/useDict'
#end
  import { fetch#(entityName)Page, fetchSave#(entityName), fetchRemove#(entityName) } from '@/api/#(module)/#(businessKebab)'
  import type { ColumnOption } from '@/types/component'

  defineOptions({ name: '#(entityName)' })
#if(dictCodes.size() > 0)

  const { #(dictVars) } = useDict(#(dictArgs))
#end

  const columnsFactory = (): ColumnOption[] => [
    { type: 'index', width: 60, label: '序号' },
#for(col : listColumns)
    { prop: '#(col.prop)', label: '#(col.label)', minWidth: 140 },
#end
    {
      prop: 'operation',
      label: '操作',
      width: 160,
      fixed: 'right',
      formatter: (row: any) =>
        h('div', [
          h(ElButton, { link: true, type: 'primary', size: 'small', onClick: () => showDialog('edit', row) }, () => '编辑'),
          h(ElButton, { link: true, type: 'danger', size: 'small', onClick: () => handleDelete(row) }, () => '删除')
        ])
    }
  ]

  const {
    columns, data, loading, pagination, handleSizeChange, handleCurrentChange,
    dialogVisible, dialogType, currentRow, showDialog, handleDelete, handleSubmit
  } = useCrud({
    listApi: (params: any) => fetch#(entityName)Page(params),
    saveApi: (data: any) => fetchSave#(entityName)(data),
    removeApi: (id: any) => fetchRemove#(entityName)([id]),
    columnsFactory,
    label: '#(functionName)'
  })
</script>
