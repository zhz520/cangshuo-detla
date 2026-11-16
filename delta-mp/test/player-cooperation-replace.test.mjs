import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const root = resolve(import.meta.dirname, '..')
const read = path => readFile(resolve(root, path), 'utf8')

test('primary player can replace a teammate from the order detail without losing the team lead', async () => {
  const detail = await read('pages-player/order/detail.vue')
  const invite = await read('pages-player/invite/teammate.vue')
  const api = await read('api/player.js')

  assert.match(detail, /primaryPlayer === true/)
  assert.match(detail, /goReplaceTeammate/)
  assert.match(detail, /replace=1&teammatePlayerId=/)
  assert.match(detail, /更换/)
  assert.match(invite, /replaceMode/)
  assert.match(invite, /replacementPlayerId/)
  assert.match(invite, /确认更换/)
  assert.match(invite, /setNavigationBarTitle/)
  assert.match(api, /replaceTeammate = \(orderId, data = \{\}\)/)
})
