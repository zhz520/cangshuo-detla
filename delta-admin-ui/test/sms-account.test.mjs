import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { serviceAccountState } from '../src/utils/service-account-state.mjs'

const source = readFileSync(new URL('../src/views/system/SmsAccount.vue', import.meta.url), 'utf8')

test('SMS service supports the authority gateway and a self-managed Aliyun provider', () => {
  assert.match(source, /阿里云短信（本实例配置）/)
  assert.match(source, /settings\.provider === 'ALIYUN'/)
  assert.match(source, /accessKeySecret/)
  assert.match(source, /verificationTemplateCode/)
  assert.match(source, /playerFinishTemplateCode/)
  assert.match(source, /saveAuthoritySettings/)
  assert.match(source, /当前使用本实例阿里云短信，不使用授权中心套餐/)
  assert.match(source, /余额充值/)
  assert.match(source, /rechargePackages/)
  assert.match(source, /credit_cents/)
  assert.match(source, /同一账号失败次数限制/)
  assert.match(source, /getIdentityLimits/)
  assert.match(source, /saveIdentityLimits/)
  assert.match(source, /maxAttempts/)
})

test('persisted provider and gateway readiness, not the service switch, control package discovery', () => {
  const aliyun = { provider: 'ALIYUN', configured: true, authorityConfigured: false }
  const authority = { provider: 'AUTHORITY', configured: true, enabled: false }
  assert.equal(serviceAccountState('sms', aliyun, 'ALIYUN'), 'DIRECT')
  assert.equal(serviceAccountState('sms', aliyun, 'AUTHORITY'), 'UNSAVED')
  assert.equal(serviceAccountState('sms', authority, 'ALIYUN'), 'UNSAVED')
  assert.equal(serviceAccountState('sms', authority, 'AUTHORITY'), 'CONNECTED')
  assert.equal(serviceAccountState('identity', authority, 'AUTHORITY'), 'CONNECTED')
  assert.equal(serviceAccountState('identity', { ...authority, configured: false }, 'AUTHORITY'), 'UNCONNECTED')
  assert.equal(serviceAccountState('sms', null, 'AUTHORITY'), 'LOADING')
})

test('failed limit reads cannot turn displayed defaults into stored settings', () => {
  assert.match(source, /const limitSettings = ref\(null\)/)
  assert.match(source, /v-if="limitSettings"/)
  assert.match(source, /\|\| limitsError.value \|\| readOnly.value \|\| !limitSettings.value\) return/)
  assert.match(source, /@retry="loadLimits"/)
})
