import { ElMessage } from 'element-plus'

export function showBatchResult(payload, label) {
  const result = payload?.data ?? payload ?? {}
  const success = Number(result.success || 0)
  const failed = Number(result.failed || 0)
  if (!failed) {
    ElMessage.success(`${label}完成，共处理 ${success} 项`)
    return result
  }
  const detail = (result.messages || []).slice(0, 3).join('；')
  ElMessage.warning({
    message: `${label}：成功 ${success} 项，失败 ${failed} 项${detail ? '。' + detail : ''}`,
    duration: 8000,
    grouping: true
  })
  return result
}
