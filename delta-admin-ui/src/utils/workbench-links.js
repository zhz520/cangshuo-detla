export const taskPaths = Object.freeze({
  ASSIGN: '/order/list', REFUND: '/order/list', COMPLAINT: '/complaint',
  WITHDRAW: '/withdraw', RELAY: '/relay', REPLACE: '/replace', MATERIAL: '/material-review',
  ENROLLMENT: '/player', IDENTITY: '/user/identity-review', PRIVACY: '/privacy-requests',
  CHAT: '/chat/service', UPDATE: '/platform/updates', MAINTENANCE: '/platform/updates'
})

export function taskLocation(task) {
  const path = taskPaths[task.kind]
  if (!path) return null
  if (task.kind === 'UPDATE' || task.kind === 'MAINTENANCE') return { path }
  if (task.kind === 'CHAT') return { path, query: { sessionId: task.id } }
  if (task.kind === 'REFUND') return { path, query: { orderNo: task.reference } }
  if (task.kind === 'ASSIGN') return { path, query: { orderNo: task.reference, status: task.state } }
  return { path, query: { taskId: task.id } }
}
