import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveWechatPayChannel } from '../utils/platform.js'

test('WeChat payment channel separates mini program, official account and H5 scenes', () => {
  const base = { miniappEnabled: true, h5Enabled: true, officialEnabled: false }
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: true, web: false, inWechat: false }), 'MINIAPP')
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: true, mobile: true }), null)
  assert.equal(resolveWechatPayChannel({ ...base, officialEnabled: true, miniapp: false, web: true, inWechat: true, mobile: true }), 'OFFICIAL')
  assert.equal(resolveWechatPayChannel({ ...base, officialEnabled: true, miniapp: false, web: true, inWechat: true, mobile: false }), null)
  assert.equal(resolveWechatPayChannel({ ...base, miniapp: false, web: true, inWechat: false }), 'H5')
  assert.equal(resolveWechatPayChannel({ ...base, h5Enabled: false, miniapp: false, web: true, inWechat: false }), null)
  assert.equal(resolveWechatPayChannel({ ...base, miniappEnabled: false, miniapp: true, web: false, inWechat: false }), null)
})
