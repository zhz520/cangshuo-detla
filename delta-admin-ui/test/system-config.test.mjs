import test from 'node:test'
import assert from 'node:assert/strict'
import {configGroupCounts,editableSystemConfigs,isSystemConfigVisible,orderedConfigGroups} from '../src/utils/system-config.js'

const config = (configKey, configName = configKey, configGroup = '站点配置') => ({configKey,configName,configGroup})

test('system config save excludes dedicated email and business settings', () => {
  const configs = [
    config('site_name'),
    config('email.notifications.enabled'),
    config('email.notifications.password'),
    config('wallet.recharge.packages'),
    config('features.reward_grab'),
    config('player.deposit_required'),
    config('internal.helper', '')
  ]
  assert.deepEqual(editableSystemConfigs(configs).map(item => item.configKey), ['site_name'])
  assert.equal(isSystemConfigVisible(configs[1]), false)
})

test('official notification fields follow the paid feature and save only visible values', () => {
  const official = config('wechat.official.enabled', '公众号通知', '公众号通知')
  assert.equal(isSystemConfigVisible(official, new Set()), false)
  assert.equal(isSystemConfigVisible(official, new Set(['official_wechat_notifications'])), true)
  assert.deepEqual(
    editableSystemConfigs([official], new Set(['official_wechat_notifications'])).map(item => item.configKey),
    ['wechat.official.enabled']
  )
})

test('official notification settings expose one dedicated template group per role', () => {
  const legacy = config('wechat.official.template.new_order', '旧版通用模板', '公众号通知')
  const roles = ['user', 'player', 'admin', 'cs'].map(role =>
    config(`wechat.official.templates.${role}`, `${role}专属模板`, '公众号通知'))
  const enabled = new Set(['official_wechat_notifications'])

  assert.equal(isSystemConfigVisible(legacy, enabled), false)
  assert.deepEqual(editableSystemConfigs([legacy, ...roles], enabled).map(item => item.configKey), roles.map(item => item.configKey))
})

test('system config groups keep a stable menu order and expose item counts', () => {
  const configs = [
    config('a', 'A', '公众号通知'),
    config('b', 'B', '站点配置'),
    config('c', 'C', '打手配置'),
    config('d', 'D', '自定义分组'),
    config('e', 'E', '站点配置')
  ]

  assert.deepEqual(orderedConfigGroups(configs), ['站点配置', '打手配置', '公众号通知', '自定义分组'])
  assert.equal(configGroupCounts(configs).get('站点配置'), 2)
  assert.equal(configGroupCounts(configs).get('自定义分组'), 1)
})
