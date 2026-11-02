import test from 'node:test'
import assert from 'node:assert/strict'
import {editableSystemConfigs,isSystemConfigVisible} from '../src/utils/system-config.js'

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
