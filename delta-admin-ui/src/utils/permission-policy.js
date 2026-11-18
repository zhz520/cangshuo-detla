export function permissionFor(path) {
  if (/^\/product(?:\/|$)/.test(path)) return 'product.manage'
  if (['/platform/features', '/platform/themes', '/platform/updates'].includes(path)) return 'system.manage'
  if (/^\/system(?:\/|$)/.test(path)) return 'system.manage'
  if (/^\/order(?:\/|$)/.test(path)) return 'order.read'
  if (/^\/player(?:\/|$)/.test(path) || path === '/player-level') return 'player.manage'
  if (/^\/user(?:\/|$)/.test(path)) return 'user.read'
  if (/^\/statistics(?:\/|$)/.test(path) || ['/settlements', '/rewards', '/platform/income-analysis'].includes(path)) return 'finance.read'
  if (path === '/withdraw') return 'withdraw.review'
  if (path === '/deposit-refunds') return 'withdraw.review'
  if (path === '/refund') return 'order.refund'
  if (['/complaint', '/fulfillment-review'].includes(path)) return 'order.manage'
  if (/^\/chat(?:\/|$)/.test(path)) return 'chat.manage'
  return null
}
