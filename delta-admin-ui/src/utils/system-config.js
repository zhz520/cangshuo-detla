const dedicatedConfigKeys = new Set([
  'wallet.recharge.packages',
  'features.reward_grab',
  'player.deposit_required'
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
