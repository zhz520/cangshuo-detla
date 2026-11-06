import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('notice content keeps a safe gap above the acknowledgement button', async () => {
  const source = await readFile(new URL('../pages/index/index.vue', import.meta.url), 'utf8')
  assert.match(source, /\.popup-body\s*\{[\s\S]*?padding:\s*32rpx 32rpx 44rpx/)
  assert.match(source, /\.popup-footer\s*\{[\s\S]*?padding:\s*32rpx 32rpx calc\(28rpx \+ var\(--app-safe-bottom/)
  assert.match(source, /\.popup-footer\s*\{[\s\S]*?box-shadow:/)
})
