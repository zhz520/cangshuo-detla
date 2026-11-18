function normalizeRoute(route) {
  const [path, query = ''] = String(route || '').split('?')
  const normalized = query.split('&').filter(Boolean).sort().join('&')
  return normalized ? `${path}?${normalized}` : path
}

const pathIcons = Object.freeze({
  '/wechat-binding': 'ChatDotRound',
  '/chat/service': 'ChatDotRound',
  '/chat/quick-reply': 'ChatDotRound',
  '/chat/record': 'ChatDotRound',
  '/order/list': 'List',
  '/refund': 'Money',
  '/complaint': 'Warning',
  '/fulfillment-review': 'CircleCheck',
  '/product/list': 'Goods',
  '/product/category': 'Goods',
  '/user/list': 'User',
  '/user/spending-rank': 'DataAnalysis',
  '/privacy-requests': 'Lock',
  '/player': 'Avatar',
  '/player/deposits': 'Money',
  '/player-level': 'Medal',
  '/invitations': 'Link',
  '/material-review': 'Picture',
  '/staff-shifts': 'Clock',
  '/statistics/income-daily': 'DataAnalysis',
  '/settlements': 'Tickets',
  '/withdraw': 'Wallet',
  '/deposit-refunds': 'Wallet',
  '/rewards': 'Wallet',
  '/system/banner': 'Picture',
  '/system/notice': 'Picture',
  '/system/reward-grab': 'Money',
  '/system/player-layout': 'Picture',
  '/system/config': 'Setting',
  '/system/sms-account': 'Setting',
  '/system/admin': 'User',
  '/system/roles': 'Lock',
  '/system/wechat-notices': 'ChatDotRound',
  '/system/email-notifications': 'Box',
  '/system/operation-log': 'DataAnalysis',
  '/platform/features': 'Box',
  '/platform/updates': 'Box'
})

export function buildMobileNavigationSections(groups = [], claimedRoutes = []) {
  const claimed = new Set(claimedRoutes.filter(Boolean).map(normalizeRoute))
  const sections = []

  function take(items) {
    return (items || []).filter(item => {
      if (!item?.path || item.path === '/dashboard') return false
      const key = normalizeRoute(item.path)
      if (claimed.has(key)) return false
      claimed.add(key)
      return true
    }).map(item => ({
      key: item.path,
      label: item.title,
      route: item.path,
      icon: pathIcons[item.path] || item.icon || 'Menu'
    }))
  }

  for (const group of groups) {
    const loose = []
    for (const item of group.items || []) {
      if (item.path) {
        loose.push(item)
      } else if (item.children?.length) {
        const items = take(item.children)
        if (items.length) {
          sections.push({ key: `${group.id}:${item.id}`, title: item.title, caption: group.title, items })
        }
      }
    }
    const items = take(loose)
    if (items.length) sections.push({ key: group.id, title: group.title, caption: '常用功能', items })
  }
  return sections
}
