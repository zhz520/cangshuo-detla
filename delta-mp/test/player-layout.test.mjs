import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL(path, import.meta.url), 'utf8')

test('public player directory supports licensed grid, list and compact layouts', async () => {
  const page = await read('../pages/players/index.vue')
  const api = await read('../api/player.js')
  const card = await read('../components/PlayerListCard.vue')
  assert.match(page, /layout==='grid'/)
  assert.match(page, /layout==='compact'/)
  assert.match(page, /PlayerListCard/)
  assert.match(api, /getPublicPlayerLayout/)
  assert.match(card, /在线接单/)
  assert.match(card, /暂时离线/)
  assert.match(card, /立即点单/)
})
