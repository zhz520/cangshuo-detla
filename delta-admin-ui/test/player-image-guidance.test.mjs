import test from 'node:test'
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'

test('admin player uploads state square avatar and 15:8 cover ratios', async () => {
  const source = await readFile(new URL('../src/components/PlayerHomepageFields.vue', import.meta.url), 'utf8')
  assert.match(source, /最佳比例 1:1 方图，建议 640×640 像素以上/)
  assert.match(source, /最佳比例 21:9 横图，建议 2520×1080 像素；页面会统一居中裁切为 21:9 展示/)
  assert.match(source, /最佳比例 1:1 方图，建议 1000×1000 像素以上/)
})
