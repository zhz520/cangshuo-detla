// Every identity, amount, phone mask, business record and response here is synthetic.
// Unknown reads and all non-allowlisted writes fail closed; there is no backend fallback.
export const fixtureVersion = '2026-11-23.3'
export const fixtureToken = role => `SYNTHETIC-QA-ONLY-${role}-NOT-A-JWT`
const image = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="360" height="240"><rect width="360" height="240" fill="#dce5ed"/><text x="30" y="125" font-size="26" fill="#243443">SYNTHETIC QA</text></svg>')
const page = records => ({ records, total: records.length, current: 1, size: 10, pages: records.length ? 1 : 0 })
const profile = { id: 900001, userId: 900001, nickname: '合成测试用户', username: 'synthetic-qa', phone: '13800000000', avatar: image, balance: 500, status: 'ACTIVE', isOnline: 1, levelId: 900001, levelName: '合成测试等级', role: 'USER', hasPassword: true, isPlayer: true, playerStatus: 'ACTIVE' }
const product = { id: 900001, name: '合成测试服务', title: '合成测试服务', categoryId: 900001, categoryName: '合成测试分类', price: 88, minPrice: 88, basePrice: 88, salePrice: 88, originalPrice: 99, image, cover: image, coverImage: image, images: JSON.stringify([image]), description: '仅用于隔离视觉审计的合成测试说明。', content: '<p>合成测试内容，未连接业务系统。</p>', status: 1, sales: 12, salesCount: 12, specs: [], specGroups: [], priceRules: [], enabled: true }
const player = { ...profile, nickname: '合成测试接单员', playerId: 900001, introduction: '合成资料，用于检查文本布局与详情状态。', canOrder: true, tags: ['合成测试'], categories: [], services: [], reviews: [], score: 5, rating: 5, orderCount: 12, completedCount: 12, totalOrders: 12, level: { id: 900001, name: '合成测试等级' }, gallery: [], materials: [] }
const order = id => ({ id: Number(id || 900001), orderNo: `SYNTHETIC-${id || 900001}`, status: String(id) === '900002' ? 'PENDING_PAYMENT' : 'IN_PROGRESS', productId: 900001, productName: product.name, productImage: image, categoryId: 900001, categoryName: product.categoryName, amount: 88, totalAmount: 88, paidAmount: String(id) === '900002' ? 0 : 88, quantity: 1, commissionRate: 0.1, userId: 900001, playerId: 900001, playerName: player.nickname, playerNickname: player.nickname, userNickname: profile.nickname, createdAt: '2026-11-13T08:00:00', paidAt: String(id) === '900002' ? null : '2026-11-13T08:01:00', payDeadline: String(id) === '900002' ? new Date(Date.now() + 15 * 60000).toISOString() : null, specInfo: '合成测试规格', formData: '{}', dynamicFormData: '{}', remark: '合成测试订单，仅供本地截图。', players: [player], assignments: [], progress: [], serviceStatus: 'IN_PROGRESS' })
const categories = [{ id: 900001, name: '合成测试分类', parentId: 0, icon: image, image, children: [] }]
const levels = [{ id: 900001, name: '合成测试等级', sort: 1, enabled: true, commissionRate: 0.1 }]
const features = ['identity_verification', 'advanced_income_reports', 'email_notifications', 'player_directory_layouts'].map(id => ({ id, name: `合成测试 ${id}`, enabled: true, entitled: true, status: 'ENABLED', expiresAt: null, included: true, purchased: true }))
const account = { id: 900001, type: 'ALIPAY', account: 'synthetic-qa@example.invalid', realName: '合成测试', isDefault: true }
const session = { id: 900001, sessionId: 900001, orderId: 900001, type: 'ORDER', otherId: 900001, otherName: '合成测试会话', otherAvatar: image, userName: profile.nickname, playerName: player.nickname, unreadCount: 0, lastMessage: '合成测试消息', updatedAt: '2026-11-13T08:00:00' }
const themeSettings = theme => ({ activeTheme: theme, themes: ['default', 'neumorphism'].map(id => ({ id, name: id === 'default' ? '默认主题（合成测试）' : '新拟态（合成测试）', description: '本地隔离 fixture，授权状态为合成数据', enabled: true, entitled: true, included: id === 'default', purchased: true, available: true, status: 'ENABLED', previewImage: image, features: [] })) })
const listReads = new Set([
  '/order/my', '/order/review/my', '/order/complaint/my', '/pay/transactions', '/message/list',
  '/player/work/my', '/player/work/available', '/player/order/hall', '/player/order/invite-list', '/player/order/teammate/available',
  '/player/earnings/detail', '/player/withdraw', '/user/favorites', '/user/follows', '/workbench/tasks',
  '/admin/user/list', '/admin/player/list', '/admin/order/list', '/cs/user/list', '/cs/player/list', '/cs/order/list',
  '/admin/complaint/list', '/cs/complaint/list', '/admin/refunds', '/cs/refunds', '/cs/replace/list', '/cs/relay/list',
  '/admin/withdraw/list', '/admin/reward/list', '/cs/chat/session/list', '/cs/chat/record/list', '/cs/chat/message/list',
  '/system/admin/list', '/system/operation-log/list', '/system/sensitive-word/list', '/system/notice/list', '/system/banner/list',
  '/admin/statistics/user-spending-rank', '/cs/order/spending-rank', '/admin/identity/list',
])
const arrayReads = new Set([
  '/user/privacy/requests', '/user/rewards/eligible-orders',
  '/product/recommend/categories', '/system/banner/active', '/system/notice/active', '/system/notice/latest',
  '/system/recharge-packages', '/user/game-info/list', '/player/homepage/revisions',
  '/app/display/spend-ranking', '/app/display/purchase-feed', '/user/coupons/available', '/user/coupons/mine',
  '/system/content/list', '/system/quick-reply/list', '/system/config/groups',
])
function answer(data, state = 'synthetic-object') { return { known: true, status: 200, state, body: { code: 200, data, msg: 'SYNTHETIC QA FIXTURE' } } }
export function fixtureResponse({ pathname, method = 'GET', theme = 'default', auth = 'admin', scenario = 'normal' }) {
  const p = pathname.replace(/^\/(?:__qa_api__|api)(?=\/|$)/, '') || '/'
  if (scenario === 'unauthorized' && p === '/cs/permissions') return { known: true, status: 200, state: 'synthetic-expired-auth', body: { code: 401, msg: '合成身份已过期', data: null } }
  if (scenario === 'approval-500' && method === 'PUT' && p === '/admin/player/900001/approve') return { known: true, status: 500, state: 'synthetic-approval-500-no-backend', body: { code: 500, data: null, msg: '合成审核接口错误（500）' } }
  if (method !== 'GET') {
    // The app marks chat read on page load. Simulate only that automatic write locally.
    if (method === 'POST' && p === '/common/chat/message/read') return answer(null, 'synthetic-auto-read-write-no-backend')
    return { known: false, status: 405, state: 'blocked-mutation', body: { code: 405, data: null, msg: 'QA_BLOCKED_MUTATION' } }
  }
  if (p === '/system/config/site') return answer({ site_name: '合成测试站点', site_subtitle: '本地隔离视觉审计', site_admin_title: '合成测试后台', site_logo: image, player_deposit_mode: 'NONE', player_deposit_required: 'false', player_commission_rate: '0.1', staff_web_url: '', customer_web_url: '', customer_service_mode: 'internal', tech_support_text: 'SYNTHETIC QA ONLY', wx_pay_enabled: 'false', order_purchase_enabled: 'true', player_directory_enabled: 'true', notice_popup_every_visit: 'false' })
  if (p === '/system/config/theme') return answer({ id: theme, name: `合成主题 ${theme}` }, 'synthetic-theme')
  if (p === '/system/maintenance/status') return answer({ suspended: false, updating: false })
  if (p === '/cs/permissions') return answer(scenario === 'permission-denied' ? [] : ['*', 'order.refund'], 'synthetic-guard-permissions')
  if (p === '/admin/platform/features') return answer(features.map(f => ({ ...f, enabled: scenario !== 'feature-denied', entitled: scenario !== 'feature-denied' })), 'synthetic-entitlements')
  if (p === '/admin/platform/themes') return answer(themeSettings(theme), 'synthetic-entitlements')
  if (p === '/admin/platform/market') return answer({ overview: { status: 'VALID', projectName: '合成测试项目', expiresAt: '2099-01-01T00:00:00' }, features, authorityConfigured: false, lastSync: null }, 'synthetic-entitlements')
  if (p === '/admin/platform/overview') return answer({ status: 'VALID', projectName: '合成测试项目' }, 'synthetic-entitlements')
  if (p === '/admin/platform/updates/remote') return answer({ currentVersion: 'SYNTHETIC', connected: false, installerAvailable: false, componentVersions: { backend: 'SYNTHETIC', adminUi: 'SYNTHETIC', h5: 'SYNTHETIC' }, project: {}, updates: { releases: [], latestVersion: '' }, releases: [], job: null }, 'synthetic-disconnected-update-info')
  if (p === '/admin/platform/reports/income') {
    const period = { startDate: '2026-11-01', endDate: '2026-11-13', summary: { orderCount: 0, orderAmount: 0, playerIncome: 0, commissionIncome: 0, averageOrderAmount: 0 }, days: [] }
    return answer({ basis: '合成零值收益数据', current: period, previous: period, changePercent: {} }, 'synthetic-empty-report')
  }
  if (p === '/user/profile') return answer(profile, 'seeded-user')
  if (p === '/user/auth/login-policy') return answer({ primaryMethod: 'PHONE', reason: '' }, 'synthetic-phone-login')
  if (p === '/user/auth/wechat/security') return answer({ phone: '138****0000', phoneBound: true, wechatBound: false, wechatAvailable: false, passwordSet: true }, 'synthetic-security')
  if (p === '/product/flash-sale/active') return answer({ active: false }, 'synthetic-no-promotion')
  if (/^\/order\/\d+\/reward$/.test(p)) return answer({ enabled: false, options: [] }, 'synthetic-no-reward')
  if (p === '/player/deposit/refund/context') return answer({ paidAmount: 0, requiredAmount: 0, outstandingAmount: 0, payments: [], history: [] }, 'synthetic-empty-deposit')
  if (p === '/player/profile') return answer(player, 'seeded-player')
  if (p === '/cs/profile') return answer({ ...profile, role: auth, nickname: '合成测试工作人员' }, 'seeded-staff-profile')
  if (p === '/user/auth/captcha' && method === 'GET') return answer({ captchaId: 'SYNTHETIC', image }, 'seeded-login-captcha')
  if (p === '/cs/auth/captcha') return answer({ enabled: false, required: false, captchaId: 'SYNTHETIC', image: '' }, 'synthetic-login-captcha-disabled')
  if (scenario === 'approval-500' && p === '/admin/player/list') return answer(page([{ ...player, level: 1, realName: '合成审核申请人', status: 'PENDING', gameLevel: '合成测试段位', serviceTypes: '合成测试服务', proofImages: image }]), 'seeded-pending-application')
  if (scenario === 'approval-500' && p === '/admin/player-level/list') return answer([{ id: 1, name: '合成测试等级 1', status: 1 }], 'seeded-level-id-1')
  if (p === '/player/apply/status') return answer({ applied: false, status: 'NONE' }, 'synthetic-application-not-submitted')
  if (p === '/user/wallet') return answer({ balance: 500, totalRecharge: 500, totalConsume: 0, frozenAmount: 0 })
  if (p === '/player/home') return answer({ todayCompleted: 12, todayIncome: 88, wallet: { totalIncome: 500, balance: 500 }, pendingCount: 0, processingCount: 1 })
  if (p === '/player/earnings/summary') return answer({ balance: 500, totalIncome: 500, frozenAmount: 0, withdrawnAmount: 0, pendingAmount: 0 })
  if (p === '/player/withdraw/deposit-status') return answer({ needsPayment: false, requiredAmount: 0 })
  if (p === '/player/account') return answer([account], 'seeded-account')
  if (/^\/player\/account\/\d+$/.test(p)) return answer(account, 'seeded-account')
  if (/^\/player\/withdraw\/\d+$/.test(p)) return answer({ id: 900001, amount: 50, fee: 0, status: 'PENDING', type: 'ALIPAY', account: account.account, accountType: 'ALIPAY', accountName: '合成测试', createdAt: '2026-11-13T08:00:00' }, 'seeded-withdrawal')
  if (p === '/message/unread-count') return answer(0, 'synthetic-zero')
  if (p === '/player/remind') return answer({ inviteCount: 0, systemUnread: 0, messageUnread: 0 })
  if (p === '/workbench/summary') return answer({ unread: 0, total: 0, tasks: [], items: [], groups: [], counts: {}, modules: {}, counters: {}, role: auth.toUpperCase() }, 'synthetic-empty-workbench')
  if (p === '/common/wechat/binding') return answer({ enabled: false, licensed: false, bound: false }, 'synthetic-unconfigured-wechat')
  if (p === '/user/identity/status') return answer({ status: 'NOT_SUBMITTED', enabled: true, verified: false }, 'synthetic-unsubmitted-identity')
  if (p === '/user/display/privacy') return answer({ hideSpendRanking: false, hidePurchaseFeed: false })
  if (p === '/user/membership') return answer({ enabled: true, tier: { name: '合成会员', minSpend: 0, discountRate: 1 }, netSpend: 88, nextThreshold: 1000, nextTier: { name: '合成进阶会员' } })
  if (p === '/user/invites') return answer({ code: 'QA2SYNTH', invitedCount: 0, rewardTotal: 0, relations: [] })
  if (/^\/user\/(favorites|follows)\/\d+\/status$/.test(p)) return answer({ favorited: false, followed: false })
  if (['/product/category/all', '/product/category/tree', '/product/category/list', '/app/players/categories'].includes(p)) return answer(categories, 'seeded-category')
  if (['/app/players/levels', '/admin/player-level/list'].includes(p)) return answer(levels, 'seeded-level')
  if (p === '/app/players/layout') return answer({ id: 'default', layout: 'list', enabled: true, layouts: [], settings: {} })
  if (p === '/app/players') return answer(page([player]), 'seeded-player-list')
  if (/^\/app\/players\/\d+$/.test(p)) return answer(player, 'seeded-player-detail')
  if (/^\/app\/players\/\d+\/(reviews|reviewable-orders)$/.test(p)) return answer(p.endsWith('/reviews') ? page([]) : [], 'synthetic-empty-list')
  if (p === '/product/recommend') return answer([product], 'seeded-product-list')
  if (p === '/product/list') return answer(page([product]), 'seeded-product-list')
  if (/^\/product\/\d+$/.test(p)) return answer(product, 'seeded-product-detail')
  if (/^\/app\/product\/\d+$/.test(p)) return answer({ product, categoryName: product.categoryName, specs: [], specGroups: [], priceRules: [], reviewSummary: { count: 0, averageRating: 0 }, ratingDistribution: [], reviews: [] }, 'seeded-product-detail')
  if (/^\/product\/category\/\d+\/form-fields$/.test(p) || /^\/user\/game-info\/list\/\d+$/.test(p)) return answer([], 'synthetic-empty-list')
  if (p === '/order/quote') return answer({ amount: 88, totalAmount: 88, originalAmount: 88, discountAmount: 0 })
  if (p === '/order/available-players') return answer([player], 'seeded-player-list')
  if (/^\/(order|player\/work)\/\d+$/.test(p)) return answer(order(p.split('/').at(-1)), 'seeded-order-detail')
  if (/^\/(order|player\/order)\/\d+\/(progress|refunds)$/.test(p)) return answer([], 'synthetic-empty-list')
  if (/^\/order\/review\/(product|player)\/\d+$/.test(p)) return answer(page([]), 'synthetic-empty-list')
  if (/^\/order\/complaint\/\d+$/.test(p)) return answer({ id: 900001, orderId: 900001, orderNo: 'SYNTHETIC-900001', content: '合成测试投诉内容', reason: '合成测试原因', status: 'PENDING', images: '', createdAt: '2026-11-13T08:00:00' }, 'seeded-complaint-detail')
  if (p === '/common/chat/session/list') return answer([session], 'seeded-session-list')
  if (/^\/common\/chat\/session\/(?:by-order\/)?\d+$/.test(p)) return answer(session, 'seeded-session-detail')
  if (p === '/common/chat/message/list') return answer(page([]), 'synthetic-empty-chat')
  if (/^\/system\/content\/key\//.test(p)) return answer({ title: '合成测试内容', content: '<p>本段为隔离视觉审计生成的合成内容，未使用客户资料。</p>', enabled: true }, 'synthetic-content')
  if (listReads.has(p)) return answer(page([]), 'synthetic-empty-list')
  if (arrayReads.has(p)) return answer([], 'synthetic-empty-list')
  return { known: false, status: 501, state: 'fixture-gap', body: { code: 501, data: null, msg: `QA_FIXTURE_GAP ${method} ${p}` } }
}
