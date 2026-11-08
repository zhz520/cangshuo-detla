import test from 'node:test'
import assert from 'node:assert/strict'
import {notificationConfigExample} from '../src/utils/notification-examples.js'

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
})
