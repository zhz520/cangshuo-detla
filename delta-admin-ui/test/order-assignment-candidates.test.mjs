import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = name => readFile(new URL('../src/' + name, import.meta.url), 'utf8')

test('assignment candidate requests use the order route for each staff role', async () => {
  const source = (await read('api/order-assignment.js'))
    .replace("import request from '@/utils/request'", 'const request = config => config')
  const { orderAssignmentCandidates } = await import('data:text/javascript,' + encodeURIComponent(source))
  const params = { keyword: '测试打手', pageNum: 2, pageSize: 20 }
  assert.deepEqual(orderAssignmentCandidates(true, 200, params), {
    url: '/admin/order/200/assign/candidates', params, silent: true
  })
  assert.deepEqual(orderAssignmentCandidates(false, 200, params), {
    url: '/cs/order/200/assign/candidates', params, silent: true
  })
})

test('main assignment displays the server level and prevents selecting full or stale candidates', async () => {
  const source = await read('views/order/OrderList.vue')
  assert.doesNotMatch(source, /adminPlayerList|csPlayerList/)
  assert.match(source, /orderAssignmentCandidates\(isAdmin, assignOrderId\.value/)
  assert.match(source, /requiredLevelName/)
  assert.match(source, /当前|订单要求等级/)
  assert.match(source, /playerLoading \|\| assigning \|\| isFull\(row\)/)
  assert.match(source, /playerList\.value\.includes\(row\)/)
  assert.match(source, /current === candidateRequest/)
})

test('invite and replacement share the displayed order level and backend candidate data', async () => {
  const source = await read('views/order/OrderTeamPanel.vue')
  assert.match(source, /orderTeamCandidates\(props\.admin, props\.order\.id/)
  assert.match(source, /candidateInfo\.requiredLevelName/)
  assert.match(source, /candidate\.levelName/)
  assert.match(source, /replacedTeammateId: target\.value\?\.playerId/)
  assert.match(source, /candidateError\.value \|\| !inviteeId\.value \|\| selectedCandidateFull\.value/)
})
