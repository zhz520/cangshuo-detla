import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('agreement pages consume the content_config configValue returned by the backend', async () => {
  const page = await readFile(new URL('../pages/agreement/index.vue', import.meta.url), 'utf8')
  assert.match(page, /res\.data\?\.configValue/)
  assert.doesNotMatch(page, /res\.data\?\.content(?!Value)/)
})

test('legacy tenants keep the original documents without sharing demo content', async () => {
  const { resolveContentDocument } = await import('../utils/content-document.mjs')
  const agreement = resolveContentDocument('user_agreement', null, '示例 & 平台')
  const privacy = resolveContentDocument('privacy_policy', '  ', '示例 & 平台')
  assert.equal((agreement.match(/<h2>/g) || []).length, 8)
  assert.equal((privacy.match(/<h2>/g) || []).length, 9)
  assert.match(agreement, /示例 &amp; 平台/)
  assert.match(privacy, /如何使用您的信息/)
  assert.doesNotMatch(agreement + privacy, /KK云电竞/)
  assert.equal(resolveContentDocument('privacy_policy', '<p>租户正文</p>', '示例'), '<p>租户正文</p>')
  assert.equal(resolveContentDocument('about_us', null, '示例'), '')
})
