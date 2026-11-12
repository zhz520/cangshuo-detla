import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL('../' + path, import.meta.url), 'utf8')

test('administrators can delete a player only after the guarded confirmation', async () => {
  const page = await read('src/views/player/PlayerList.vue')
  const api = await read('src/api/business.js')
  assert.match(api, /adminPlayerDelete/)
  assert.match(api, /url: `\/admin\/player\/\$\{id\}`, method: 'delete'/)
  assert.match(page, /handleDelete/)
  assert.match(page, /v-if="isAdmin"[^>]*handleDelete|handleDelete[^>]*v-if="isAdmin"/)
  assert.match(page, /确认删除打手/)
  assert.match(page, /保留历史订单和资金记录/)
  assert.match(page, /有余额、冻结金额、进行中订单、未完成提现、未处理投诉或待追回打赏时不能删除/)
})
