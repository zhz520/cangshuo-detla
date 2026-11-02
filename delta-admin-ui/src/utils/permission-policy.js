export function permissionFor(path) {
  if (/^\/system(?:\/|$)/.test(path)) return 'system.manage'
  if (/^\/order(?:\/|$)/.test(path)) return 'order.read'
  if (path === '/player' || path === '/player-level') return 'player.manage'
  if (/^\/user(?:\/|$)/.test(path)) return 'user.read'
  if (/^\/statistics(?:\/|$)/.test(path) || ['/settlements', '/rewards', '/platform/income-analysis'].includes(path)) return 'finance.read'
  if (path === '/withdraw') return 'withdraw.review'
  if (['/complaint', '/replace', '/relay'].includes(path)) return 'order.manage'
  if (/^\/chat(?:\/|$)/.test(path)) return 'chat.manage'
  return null
}
