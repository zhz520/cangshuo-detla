const panel = (id, title, fields = []) => ({ id, title, fields })
const page = (id, title, panels = [], editor) => ({ id, title, panels, editor })

export const configGroups = [
  { id: 'basic', title: '基础设置', description: '平台外观与站点入口', tabs: [
    page('site', '站点与品牌', [panel('brand', '品牌资料', ['平台名称', '平台副标题', '平台 Logo']), panel('site-details', '其他信息', ['管理后台标题', '技术支持文字', '技术支持链接']), panel('origin', '站点入口', ['客户 H5 地址', '工作人员后台地址'])]),
    page('display', '前台展示', [panel('display', '展示策略', ['最近购买动态', '消费排行', '展示打手列表'])]),
  ] },
  { id: 'account', title: '账户与安全', description: '登录认证与风控', tabs: [
    page('login', '登录与微信', [panel('login', '注册与登录', ['网页主登录方式', '微信优先时提供手机号入口', '允许辅助密码登录']), panel('wechat', '微信登录及公众号', ['启用网页微信登录', '公众号 AppID', '公众号 AppSecret', '启用公众号通知', '客户与打手绑定回调页', '管理员与客服绑定回调页']), panel('wechat-templates', '公众号通知模板', ['角色模板', '触发事件', '模板参数'])]),
    page('identity', '实名认证', [panel('identity', '实名策略', ['实名认证提示方式'])]),
    page('risk', '内容风控', [panel('risk', '审核与冻结', ['图片语音审核方式', '违规冻结阈值', '检测接口', '检测接口令牌'])]),
    page('age', '年龄确认', [panel('age', '访问策略', ['打开前台时确认已满 18 岁'])]),
  ] },
  { id: 'payment', title: '支付与交易', description: '支付通道与订单', tabs: [
    page('payment', '支付方式', [panel('payment', '渠道开关', ['微信支付', '公众号支付', 'H5 支付', '小程序支付'])]),
    page('certificate', '微信商户证书', [panel('merchant', '商户与应用', ['商户号', 'AppID']), panel('certificate', '证书与密钥', ['API v3 密钥', '商户私钥文件', '商户证书序列号']), panel('public-key', '微信支付公钥', ['公钥 ID', '公钥文件'])]),
    page('order', '下单与协作', [panel('order', '下单策略', ['启用商品购买', '启用游客下单', '打手协同接单'])]),
  ] },
  { id: 'message', title: '消息与通知', description: '站内外消息管理', tabs: [
    page('sms', '短信提醒', [panel('sms-send', '客服提醒', ['客服短信提醒冷却秒数']), panel('sms-templates', '聊天提醒规则', ['用户与打手短信冷却秒数', '打手短信提醒扣费金额'])]),
    page('email', '邮件通知', [panel('email', 'SMTP 设置', ['服务器', '端口', '发件邮箱', '授权码', '收件邮箱', '通知事件'])], 'email'),
    page('support', '客服设置', [panel('support', '客户入口', ['接待方式', '企业微信链接', '首页客服入口'])]),
  ] },
  { id: 'business', title: '业务配置', description: '运营规则与平台业务', tabs: [
    page('enrollment', '接单员入驻', [panel('enrollment', '入驻条件', ['仅邀请入驻']), panel('deposit', '打手押金', ['押金政策', '入驻押金金额（元）']), panel('player', '接单员策略', ['接单与状态策略'])]),
    page('settlement', '提现与结算', [panel('withdraw', '提现条件', ['最低提现金额']), panel('settlement', '结算规则', ['客服提成比例', '售后观察期'])]),
    page('marketing', '营销策略', [panel('marketing', '活动开关', ['优惠券', '邀请奖励'])]),
    page('membership', '会员等级', [panel('membership', '会员规则', ['启用会员权益', '等级名称', '成长门槛', '折扣率'])], 'membership'),
    page('invitation', '入驻邀请', [panel('invitation', '邀请链接', ['邀请名称', '有效期', '设备限制'])], 'invitation'),
  ] },
]

export const configTabs = configGroups.flatMap(g => g.tabs.map(tab => ({ ...tab, group: g.id })))
export const defaultConfigTab = 'site'
export const legacyConfigTabs = Object.freeze({
  '/marketing/membership': 'membership',
  '/system/email-notifications': 'email',
  '/invitations': 'invitation',
})

export function configSection(key) {
  if (/^site\.(staff|customer)_web_url$/.test(key)) return 'origin'
  if (/^site_admin_title$|^site\.tech_support_/.test(key)) return 'site-details'
  if (/^site[._]/.test(key)) return 'brand'
  if (/^display\.|^player\.directory\.enabled$/.test(key)) return 'display'
  if (key === 'auth.age_confirmation.enabled') return 'age'
  if (key === 'auth.wechat.enabled') return 'wechat'
  if (/^auth\./.test(key)) return 'login'
  if (/^wechat\.official\.templates\./.test(key)) return 'wechat-templates'
  if (/^wechat\.official\./.test(key)) return 'wechat'
  if (/^identity\./.test(key)) return 'identity'
  if (/^risk\./.test(key)) return 'risk'
  if (/^order\./.test(key)) return 'order'
  if (/^player\.deposit_(mode|amount)$/.test(key)) return 'deposit'
  if (key === 'player.invite_required') return 'enrollment'
  if (/^player\./.test(key)) return 'player'
  if (/^withdraw\./.test(key)) return 'withdraw'
  if (/^settlement\./.test(key)) return 'settlement'
  if (/^marketing\./.test(key)) return 'marketing'
  if (/^wx\.pay\.(enabled|h5-enabled|official-enabled|miniapp-enabled|h5-type|h5-app-name)$/.test(key)) return 'payment'
  if (/^wx\.pay\.public-key-/.test(key)) return 'public-key'
  if (/^wx\.pay\.(api-v3-key|private-key-path|cert-serial-no)$/.test(key)) return 'certificate'
  if (/^wx\.pay\./.test(key)) return 'merchant'
  if (key === 'sms.aliyun.cooldown_seconds') return 'sms-send'
  if (['sms.aliyun.user_player_cooldown_seconds', 'sms.aliyun.player_fee'].includes(key)) return 'sms-templates'
  if (/^customer_service\./.test(key)) return 'support'
  return ''
}

const fieldOrder = [
  'site_name', 'site_subtitle', 'site_logo', 'site_admin_title', 'site.tech_support_text', 'site.tech_support_url',
  'site.customer_web_url', 'site.staff_web_url',
  'auth.wechat.enabled', 'wechat.official.app_id', 'wechat.official.app_secret', 'wechat.official.enabled',
  'wechat.official.redirect_url', 'wechat.official.staff_redirect_url',
  'auth.primary_method', 'auth.phone.enabled', 'auth.password.enabled',
  'player.deposit_mode', 'player.deposit_amount',
  'wx.pay.enabled', 'wx.pay.official-enabled', 'wx.pay.h5-enabled', 'wx.pay.miniapp-enabled',
]
const rank = new Map(fieldOrder.map((key, index) => [key, index]))

export function orderConfigFields(items) {
  return [...items].sort((a, b) => (rank.get(a.configKey) ?? 100) - (rank.get(b.configKey) ?? 100))
}

const needs = {
  login: ['wechat.official.app_id', 'wechat.official.app_secret'],
}
export function configReadiness(tab, saved) {
  if (!needs[tab] || !saved) return null
  const keys = [...needs[tab]]
  return keys.every(key => Boolean(String(saved[key] ?? '').trim())) ? 'complete' : 'incomplete'
}
