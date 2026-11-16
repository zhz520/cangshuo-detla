import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'

const root = resolve(import.meta.dirname, '../..')
const read = path => readFile(resolve(root, path), 'utf8')

test('primary player can replace a collaboration teammate with a new online player', async () => {
  const detail = await read('delta-mp/pages-player/order/detail.vue')
  const invite = await read('delta-mp/pages-player/invite/teammate.vue')
  const api = await read('delta-mp/api/player.js')
  const controller = await read('delta-game/delta-player/src/main/java/com/delta/player/controller/PlayerOrderController.java')
  const order = await read('delta-game/delta-order/src/main/java/com/delta/order/entity/Order.java')
  const work = await read('delta-game/delta-order/src/main/java/com/delta/order/controller/PlayerWorkController.java')

  assert.match(detail, /canManageTeammates/)
  assert.match(detail, /goReplaceTeammate/)
  assert.match(detail, /replace=1&teammatePlayerId=/)
  assert.match(detail, /移除全部队友/)
  assert.match(invite, /replaceMode/)
  assert.match(invite, /replaceTeammate/)
  assert.match(invite, /replacementPlayerId/)
  assert.match(invite, /更换协同打手/)
  assert.match(api, /replaceTeammate = \(orderId, data = \{\}\)/)
  assert.match(controller, /ReplaceTeammateRequest/)
  assert.match(controller, /teammatePlayerId/)
  assert.match(controller, /TEAMMATE_REPLACED/)
  assert.match(controller, /validateTeamAllocation/)
  assert.match(order, /primaryPlayer/)
  assert.match(work, /setPrimaryPlayer/)
})
