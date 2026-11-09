import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { parse, compileScript } from '@vue/compiler-sfc'

const filename = fileURLToPath(new URL('../src/views/platform/FeatureMarket.vue', import.meta.url))
const source = await readFile(filename, 'utf8')
const helperSource = source.match(/<script>\s*([\s\S]*?)<\/script>/)?.[1]
if (!helperSource) throw new Error('FeatureMarket helper script is missing')
const helpers = await import('data:text/javascript;base64,' + Buffer.from(helperSource).toString('base64'))
const now = Date.parse('2026-11-02T12:00:00+08:00')

test('paid cards show the catalog price and reference term before purchase', () => {
  const feature = { paid: true, priceCents: 5000, termDays: 365, status: 'NOT_PURCHASED' }
  const maintenanceUntil = new Date(now + 10 * 86400000).toISOString()
  assert.equal(helpers.purchaseTerms(feature, now, maintenanceUntil),
    '付费扩展 · ¥50.00 · 参考 365 天 · 本次最多可购 10 天 · 在线购买')
  assert.equal(helpers.purchaseTerms({ paid: false }), '基础功能 · 已包含')
  assert.equal(helpers.licensePeriodText(feature, now), '授权期限：未授权')
})

test('purchased cards show start, expiry and remaining days calculated from the expiry date', () => {
  const active = {
    paid: true, entitled: true, status: 'ENABLED', startsAt: '2026-11-02T12:00:00+08:00',
    expiresAt: '2026-11-05T12:00:00+08:00'
  }
  assert.equal(helpers.remainingDays(active.expiresAt, now), 3)
  const text = helpers.licensePeriodText(active, now)
  assert.match(text, /^生效 .+ · 授权至 .+ · 剩余 3 天$/)
  assert.equal(helpers.remainingDays('2026-11-01T12:00:00+08:00', now), 0)
})

test('missing expiry explicitly means long-term or unauthorized', () => {
  assert.match(helpers.licensePeriodText({ paid: true, entitled: true, status: 'ENABLED' }, now), /^生效时间待同步 · 长期有效$/)
  assert.match(helpers.licensePeriodText({ paid: true, entitled: true, status: 'ENABLED', startsAt: '2026-11-02T12:00:00+08:00' }, now), /^生效 .+ · 长期有效$/)
  assert.equal(helpers.licensePeriodText({ paid: true, entitled: false, status: 'NOT_PURCHASED' }, now), '授权期限：未授权')
  assert.equal(helpers.licensePeriodText({ paid: true, entitled: false, status: 'EXPIRED' }, now), '授权期限：未授权')
})

test('feature market keeps local switches available when authority marks the entitlement valid', () => {
  const parsed = parse(source, { filename })
  assert.equal(parsed.errors.length, 0)
  const compiled = compileScript(parsed.descriptor, { id: 'feature-market-ui', inlineTemplate: true })
  assert.match(compiled.content, /managedByAuthority/)
  assert.doesNotMatch(compiled.content, /功能启用状态由授权中心管理/)
  assert.match(source, /!feature\.entitled \|\| syncing \|\| loading \|\| !!changing \|\| !!buying/)
  assert.doesNotMatch(source, /启用状态由授权中心控制/)
  assert.match(source, /是否实际启用由本系统管理员自行控制/)
  assert.match(source, /续费/)
  assert.match(source, /createPlatformCheckout/)
  assert.match(source, /checkout\.protocol !== 'https:'/)
  assert.match(source, /window\.location\.assign\(checkout\.href\)/)
})
