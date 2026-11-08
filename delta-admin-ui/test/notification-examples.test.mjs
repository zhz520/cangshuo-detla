import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {
  NOTIFICATION_EVENTS,
  NOTIFICATION_ROLES,
  NOTIFICATION_SAMPLES,
  notificationConfigExample,
  notificationConfigGuide
} from '../src/utils/notification-examples.js'

const roles = ['user','player','admin','cs']
const events = ['new_order','order_status','refund_status','complaint_status','staff_review']
const wechatSources = new Set(['message','status','time','orderNo','amount','customerName','playerName'])
const smsSources = wechatSources

test('every WeChat role has an example for every notification event', () => {
  for (const role of roles) {
    const parsed = JSON.parse(notificationConfigExample(`wechat.official.templates.${role}`))
    assert.deepEqual(Object.keys(parsed), events)
    for (const event of events) {
      assert.match(parsed[event].templateId, /请替换/)
      assert.ok(Object.keys(parsed[event].fields).length >= 2)
      for (const source of Object.values(parsed[event].fields)) assert.ok(wechatSources.has(source))
    }
    assert.deepEqual(parsed.staff_review.fields, {thing1:'message',phrase2:'status',time3:'time'})
  }
})

test('every SMS role has an example for every notification event', () => {
  for (const role of roles) {
    const parsed = JSON.parse(notificationConfigExample(`sms.notification.templates.${role}`))
    assert.deepEqual(Object.keys(parsed), events)
    for (const event of events) {
      assert.match(parsed[event].templateCode, /请替换/)
      assert.ok(Object.keys(parsed[event].fields).length >= 2)
      for (const source of Object.values(parsed[event].fields)) assert.ok(smsSources.has(source))
    }
    assert.deepEqual(parsed.staff_review.fields, {message:'message',status:'status',time:'time'})
  }
})

test('unrelated configuration fields do not receive notification examples', () => {
  assert.equal(notificationConfigExample('sms.aliyun.enabled'), '')
  assert.equal(notificationConfigExample('wechat.official.app_id'), '')
  assert.equal(notificationConfigGuide('sms.aliyun.enabled'), null)
})

test('each role guide explains fields, provider setup and all five event previews', () => {
  for (const channel of ['wechat', 'sms']) {
    for (const role of NOTIFICATION_ROLES) {
      const guide = notificationConfigGuide(`${channel === 'wechat' ? 'wechat.official.templates' : 'sms.notification.templates'}.${role}`)
      assert.equal(guide.channel, channel)
      assert.equal(guide.roleLabel, ({user:'客户',player:'打手',admin:'管理员',cs:'客服'})[role])
      assert.equal(guide.sources.length, 7)
      assert.deepEqual(guide.events.map(item => item.key), NOTIFICATION_EVENTS)
      assert.ok(guide.providerSteps.length >= 6)
      assert.match(guide.linkHelp, channel === 'wechat' ? /url/ : /变量名/)
      for (const event of guide.events) {
        assert.ok(event.trigger.length > 10)
        assert.equal(event.finalExample, NOTIFICATION_SAMPLES[role][event.key])
        assert.ok(event.finalExample.length > 15)
        assert.match(event.content, channel === 'wechat' ? /\{\{.+?\.DATA\}\}/ : /\$\{[A-Za-z]+\}/)
      }
    }
  }
})

test('the notification documentation includes every role and event content preview', () => {
  const document = readFileSync(new URL('../../docs/通知模板完整示例.md', import.meta.url), 'utf8')
  for (const role of NOTIFICATION_ROLES) {
    for (const event of NOTIFICATION_EVENTS) {
      assert.ok(document.includes(NOTIFICATION_SAMPLES[role][event]), `missing ${role}/${event} final content preview`)
    }
  }
})

test('every template-content variable has a matching field mapping', () => {
  for (const channel of ['wechat', 'sms']) {
    for (const role of NOTIFICATION_ROLES) {
      const configKey = `${channel === 'wechat' ? 'wechat.official.templates' : 'sms.notification.templates'}.${role}`
      const guide = notificationConfigGuide(configKey)
      const example = JSON.parse(notificationConfigExample(configKey))
      for (const event of guide.events) {
        const pattern = channel === 'wechat' ? /\{\{([A-Za-z_]+\d*)\.DATA\}\}/g : /\$\{([A-Za-z_]+)\}/g
        const contentFields = [...event.content.matchAll(pattern)].map(match => match[1]).sort()
        const mappedFields = Object.keys(example[event.key].fields).sort()
        assert.deepEqual(mappedFields, contentFields, `${role}/${event.key}/${channel}`)
      }
    }
  }
})
