/** #(functionName)表单 */
export interface #(entityName)Form {
  id?: number | string
#for(col : columns)
  #(col.javaField)?: #(col.tsType)
#end
}

/** #(functionName)查询 */
export interface #(entityName)Query {
  pageNum?: number
  pageSize?: number
}
