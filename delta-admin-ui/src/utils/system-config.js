const dedicatedConfigKeys = new Set([
  'wallet.recharge.packages',
  'features.reward_grab',
  'player.directory.layout',
  'player.deposit_required',
  'wechat.official.template.new_order',
  'wechat.official.template.order_accepted',
  'wechat.official.template.order_completed',
  'wechat.official.template.material_reviewed',
  'wechat.official.template.withdraw_reviewed'
])

export function isSystemConfigVisible(config, enabledFeatures = new Set()) {
  if (!config?.configKey || dedicatedConfigKeys.has(config.configKey) || config.configKey.startsWith('email.notifications.')) {
    return false
  }
  return config.configGroup !== '公众号通知' || enabledFeatures?.has('official_wechat_notifications')
}

export function editableSystemConfigs(configs, enabledFeatures = new Set()) {
  return (configs || []).filter(config => Boolean(config.configName) && isSystemConfigVisible(config, enabledFeatures))
}
