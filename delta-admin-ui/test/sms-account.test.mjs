import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../src/views/system/SmsAccount.vue', import.meta.url), 'utf8')

test('SMS service supports the authority gateway and a self-managed Aliyun provider', () => {
  assert.match(source, /阿里云短信（本实例配置）/)
  assert.match(source, /settings\.provider === 'ALIYUN'/)
  assert.match(source, /accessKeySecret/)
  assert.match(source, /verificationTemplateCode/)
  assert.match(source, /playerFinishTemplateCode/)
  assert.match(source, /saveAuthoritySettings/)
  assert.match(source, /本实例直接发送/)
  assert.match(source, /余额充值/)
  assert.match(source, /rechargePackages/)
  assert.match(source, /credit_cents/)
  assert.match(source, /同一账号失败次数限制/)
  assert.match(source, /getIdentityLimits/)
  assert.match(source, /saveIdentityLimits/)
  assert.match(source, /maxAttempts/)
})
