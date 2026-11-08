import test from 'node:test'
import assert from 'node:assert/strict'
import {staffAdminUrl,staffHomeUrl,staffLoginUrl} from '../utils/staff-entry.js'
import {readFile} from 'node:fs/promises'

test('legacy customer-H5 staff links transfer to the configured backend origin', () => {
  assert.equal(staffLoginUrl('https://jgsapi.kk520yy.com/'),'https://jgsapi.kk520yy.com/mobile/login')
  assert.equal(staffHomeUrl('https://jgsapi.kk520yy.com'),'https://jgsapi.kk520yy.com/mobile/dashboard')
  assert.equal(staffAdminUrl('https://jgsapi.kk520yy.com','/mobile/wechat-binding'),'https://jgsapi.kk520yy.com/mobile/wechat-binding')
})

test('staff transfer never accepts an unsafe or empty origin', () => {
  for(const value of ['','http://jgsapi.example.com','javascript:alert(1)','https://jgsapi.example.com/path','https://user:pass@example.com']) {
    assert.equal(staffLoginUrl(value),'')
  }
})

test('site configuration publishes a configurable staff backend origin', async () => {
  const store=await readFile(new URL('../store/site.js',import.meta.url),'utf8')
  const controller=await readFile(new URL('../../delta-game/delta-system/src/main/java/com/delta/system/controller/SysConfigController.java',import.meta.url),'utf8')
  assert.match(store,/data\.staff_web_url/)
  assert.match(controller,/site\.staff_web_url/)
  assert.match(controller,/工作人员后台地址/)
})
