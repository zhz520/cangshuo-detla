export function paidRequestAccess(url = '') {
  const path = String(url).split('?')[0]
  if (/^\/admin\/platform\/reports\/income\/?$/.test(path)) return { feature: 'advanced_income_reports', permission: 'finance.read' }
  if (/^\/admin\/identity\/applications(?:\/|$)/.test(path)) return { feature: 'identity_verification', permission: 'user.manage' }
  if (/^\/admin\/wechat\/notifications(?:\/|$)/.test(path)) return { feature: 'official_wechat_notifications', permission: 'system.manage' }
  return null
}
