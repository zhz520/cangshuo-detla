import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL(path, import.meta.url), 'utf8')

test('player cover and cards request retina-safe thumbnail widths', async () => {
  const detail = await read('../pages/players/detail.vue')
  assert.match(detail, /<view class="cover"><AppImage :width="1080" eager/)

  const card = await read('../components/PlayerShowcaseCard.vue')
  assert.match(card, /<AppImage :width="320"/)
})
