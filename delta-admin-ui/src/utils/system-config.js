const dedicatedConfigKeys = new Set([
  'wallet.recharge.packages',
  'membership.tiers',
  'membership.enabled',
  'theme.active_id',
  'theme.user_switch_enabled',
  'theme.user_switch_options',
  'notice.popup_every_visit',
  'features.reward_grab',
  'player.directory.layout',
  'player.deposit_required',
  'wechat.official.template.new_order',
  'wechat.official.template.order_accepted',
  'wechat.official.template.order_completed',
  'wechat.official.template.material_reviewed',
  'wechat.official.template.withdraw_reviewed'
])

export const SYSTEM_CONFIG_GROUP_ORDER = [
  '站点配置',
  '打手配置',
  '订单配置',
  '微信支付配置',
  '短信配置',
  '公众号通知',
  '公告配置',
  '提现配置',
  '入驻配置',
  '内容风控',
  '客服配置',
  '结算配置',
  '邮件通知',
  '余额配置',
  '打手与审核',
  'reward-grab'
]

export function configGroupName(config) {
  return config?.configGroup || '默认'
}

export function orderedConfigGroups(configs) {
  const groups = [...new Set((configs || []).map(configGroupName))]
  const rank = new Map(SYSTEM_CONFIG_GROUP_ORDER.map((name, index) => [name, index]))
  return groups.sort((left, right) => {
    const leftRank = rank.has(left) ? rank.get(left) : Number.MAX_SAFE_INTEGER
    const rightRank = rank.has(right) ? rank.get(right) : Number.MAX_SAFE_INTEGER
    if (leftRank !== rightRank) return leftRank - rightRank
    return left.localeCompare(right, 'zh-CN')
  })
}

export function configGroupCounts(configs) {
  const counts = new Map()
  for (const config of configs || []) {
    const group = configGroupName(config)
    counts.set(group, (counts.get(group) || 0) + 1)
  }
  return counts
}

export function isSystemConfigVisible(config, enabledFeatures = new Set()) {
  if (!config?.configKey || dedicatedConfigKeys.has(config.configKey) || config.configKey.startsWith('email.notifications.')) {
    return false
  }
  if (['wechat.official.app_id', 'wechat.official.app_secret'].includes(config.configKey)) return true
  if (config.configGroup === '短信配置') return ['sms.aliyun.cooldown_seconds', 'sms.aliyun.user_player_cooldown_seconds', 'sms.aliyun.player_fee'].includes(config.configKey)
  if (config.configKey === 'identity.auto.enabled') return false
  if (config.valueType === 'json' && !/^(wechat\.official\.templates|sms\.notification\.templates)\.(user|player|admin|cs)$/.test(config.configKey)) return false
  return config.configGroup !== '公众号通知' || enabledFeatures?.has('official_wechat_notifications')
}

export function editableSystemConfigs(configs, enabledFeatures = new Set()) {
  return (configs || []).filter(config => Boolean(config.configName) && isSystemConfigVisible(config, enabledFeatures))
}
