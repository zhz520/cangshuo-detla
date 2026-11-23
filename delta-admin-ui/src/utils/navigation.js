import { permissionFor } from './permission-policy.js'
import { legacyConfigTabs } from './config-sections.js'

// Navigation order is independent of route registration; URLs and bookmarks stay intact.
const sections = [
  { id: 'workbench', title: '工作台', items: [
    { path: '/dashboard' },
    { id: 'service', title: '客服服务', icon: 'ChatDotRound', paths: ['/chat/service', '/chat/quick-reply', '/chat/routing', '/chat/record'] }
  ] },
  { id: 'business', title: '业务运营', items: [
    { id: 'orders', title: '订单与售后', icon: 'List', paths: ['/order/list', '/refund', '/complaint', '/fulfillment-review'] },
    { id: 'products', title: '商品管理', icon: 'Goods', paths: ['/product/list', '/product/category', '/product/recommend-category', '/product/form-fields'] },
    { id: 'marketing', title: '营销活动', icon: 'Present', paths: ['/marketing/coupons', '/marketing/invites', '/marketing/membership', '/marketing/flash-sales'] }
  ] },
  { id: 'people', title: '人员管理', items: [
    { id: 'users', title: '用户与隐私', icon: 'User', paths: ['/user/list', '/user/spending-rank', '/privacy-requests'] },
    { id: 'players', title: '打手与审核', icon: 'Avatar', paths: ['/player', '/player/deposits', '/player-level', '/invitations', '/material-review', '/staff-shifts'] }
  ] },
  { id: 'finance', title: '财务结算', items: [
    { id: 'finance', title: '结算与统计', icon: 'DataAnalysis', paths: ['/statistics/income-daily', '/settlements', '/withdraw', '/deposit-refunds', '/rewards', '/platform/income-analysis', '/system/recharge-packages'] }
  ] },
  { id: 'wechat', title: '公众号运营', items: [
    { id: 'wechat-ops', title: '公众号运营', icon: 'ChatDotRound', paths: ['/wechat/accounts', '/wechat/tools'] }
  ] },
  { id: 'settings', title: '平台设置', items: [
    { id: 'content', title: '内容与活动', icon: 'Picture', paths: ['/system/banner', '/system/notice', '/system/content', '/system/reward-grab', '/system/player-layout'] },
    { id: 'system', title: '系统管理', icon: 'Setting', paths: ['/system/config', '/system/sms-account', '/system/identity-account', '/system/admin', '/system/roles', '/system/wechat-notices', '/system/email-notifications', '/system/sensitive-words', '/system/operation-log'] }
  ] },
  { id: 'updates', title: '功能更新', items: [
    { id: 'updates', title: '功能与更新', icon: 'Box', paths: ['/platform/features', '/platform/themes', '/platform/updates'] }
  ] }
]

function join(parent, path) {
  return (path.startsWith('/') ? path : `${parent}/${path}`).replace(/\/+/g, '/').replace(/\/$/, '') || '/'
}

export function visibleNavigationRoutes(routes, role, allowed, featureAllowed = () => true) {
  const leaves = new Map()
  function visit(route, parent = '', parents = []) {
    if (route.meta?.mobileAlias) return
    const path = join(parent, route.path), lineage = [...parents, route]
    if (route.children?.length) return route.children.forEach(child => visit(child, path, lineage))
    if (!route.meta?.title || lineage.some(node => node.meta?.hidden || node.meta?.roles && !node.meta.roles.includes(role))) return
    if (lineage.some(node => node.meta?.feature && !featureAllowed(node.meta.feature))) return
    const permission = route.meta.permission || permissionFor(path)
    if (permission && !allowed(permission)) return
    leaves.set(path, { path, title: route.meta.title, icon: route.meta.icon || parents[parents.length - 1]?.meta?.icon || 'Menu' })
  }
  routes.forEach(route => { if (route.children) visit(route) })
  return leaves
}

export function buildNavigation(routes, role, allowed, featureAllowed = () => true) {
  const available = visibleNavigationRoutes(routes, role, allowed, featureAllowed), used = new Set()
  if (role === 'admin' && available.has('/system/config') && allowed('system.manage')) {
    for (const [path, tab] of Object.entries(legacyConfigTabs)) {
      if (tab !== 'invitation' || allowed('player.manage')) available.delete(path)
    }
  }
  function take(path) {
    const entry = available.get(path)
    if (!entry || used.has(path)) return null
    used.add(path)
    return { ...entry }
  }
  const groups = sections.map(section => ({ ...section, items: section.items.map(item => {
    if (item.path) return take(item.path)
    const children = item.paths.map(take).filter(Boolean)
    return children.length ? { id: `nav-${item.id}`, title: item.title, icon: item.icon, children } : null
  }).filter(Boolean) })).filter(group => group.items.length)
  // Future registered screens remain reachable until assigned to a business group.
  const unassigned = [...available.keys()].filter(path => !used.has(path)).map(take)
  if (unassigned.length) groups.push({ id: 'other', title: '其他功能', items: unassigned })
  return groups
}

export function activeNavigationMenu(path, groups) {
  for (const group of groups) for (const item of group.items) {
    if (item.children?.some(child => child.path === path)) return item.id
  }
  return null
}
