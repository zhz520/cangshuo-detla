import test from 'node:test'
import assert from 'node:assert/strict'
import {configGroupCounts,editableSystemConfigs,isSystemConfigVisible,orderedConfigGroups} from '../src/utils/system-config.js'

import { configTabs, configSection, configReadiness, orderConfigFields } from '../src/utils/config-sections.js'

test('account, age and payment keep their tabs; supplier secrets stay out of the generic config editor', () => {
  const tabFor = key => configTabs.find(tab => tab.panels.some(panel => panel.id === configSection(key)))?.id
  assert.equal(tabFor('wechat.official.app_secret'),'login')
  assert.equal(tabFor('auth.age_confirmation.enabled'),'age')
  assert.equal(tabFor('wx.pay.public-key-path'),'certificate')
  assert.equal(tabFor('wx.pay.h5-enabled'),'payment')
  assert.equal(configSection('sms.aliyun.access_key_secret'),'')
  assert.equal(tabFor('sms.aliyun.cooldown_seconds'),'sms')
  assert.equal(isSystemConfigVisible({configGroup:'短信配置',configKey:'sms.aliyun.access_key_secret'}),false)
  assert.equal(configReadiness('login', {'wechat.official.app_id':'wx-fixture','wechat.official.app_secret':'********'}),'complete')
  assert.equal(configReadiness('login', {'wechat.official.app_id':'wx-fixture'}),'incomplete')
  assert.equal(configReadiness('sms', null), null)
})

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

test('both deposit settings remain editable together in enrollment and preserve saved amounts', () => {
  const amount = { ...config('player.deposit_amount', '入驻押金金额（元）', '打手配置'), id: 71, configValue: '68.50' }
  const mode = { ...config('player.deposit_mode', '押金政策', '打手配置'), id: 72, configValue: 'REQUIRED' }
  const panel = configTabs.find(tab => tab.id === 'enrollment').panels.find(panel => panel.id === 'deposit')
  assert.equal(panel.title, '打手押金')
  const fields = editableSystemConfigs([amount, mode, config('player.deposit_required')])
    .filter(item => configSection(item.configKey) === panel.id)
  assert.deepEqual(orderConfigFields(fields), [mode, amount])
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

test('account-bound templates never enter the generic login form or its save payload', () => {
  const legacy = config('wechat.official.template.new_order', '旧版通用模板', '公众号通知')
  const roles = ['user', 'player', 'admin', 'cs'].map(role =>
    config(`wechat.official.templates.${role}`, `${role}专属模板`, '公众号通知'))
  const enabled = new Set(['official_wechat_notifications'])

  const login = config('wechat.official.app_id', '公众号 AppID', '公众号通知')
  for (const field of [legacy, ...roles]) {
    assert.equal(isSystemConfigVisible(field, enabled), false)
    assert.equal(configSection(field.configKey), '')
  }
  assert.deepEqual(editableSystemConfigs([legacy, login, ...roles], enabled), [login])
  assert.ok(!configTabs.flatMap(tab => tab.panels).some(panel => panel.id === 'wechat-templates'))
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
