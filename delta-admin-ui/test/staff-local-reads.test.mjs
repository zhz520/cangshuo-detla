import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createSSRApp, compile, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { parse } from 'vue/compiler-sfc'
import { createRequestClient } from '../src/utils/request-client.js'
import { readPlayerOrders } from '../src/views/player/player-orders.js'

const read = p => readFileSync(new URL('../src/' + p, import.meta.url), 'utf8')
const template = parse(read('views/chat/ChatSessionPanel.vue')).descriptor.template.content
const chatRender = compile(template)
async function sessionsHtml(state) {
  const app = createSSRApp({
    setup: () => ({ sessions: [], error: '', loading: false, visible: false, activeId: null, thumbnailUrl: s => s, ...state }),
    render: chatRender
  })
  app.component('ElButton', (_, {slots}) => h('button', slots.default?.()))
  app.component('ElAvatar', (_, {slots}) => h('span', slots.default?.()))
  app.component('LoadFeedback', ({error}) => error ? h('div', { role: 'alert' }, error) : null)
  return renderToString(app)
}

test('session panel keeps existing rows and selected state on an error; empty only after a successful empty read', async () => {
  const empty = await sessionsHtml({})
  assert.match(empty, /暂无会话/)
  assert.doesNotMatch(await sessionsHtml({ loading: true }), /暂无会话/)
  const failed = await sessionsHtml({ sessions: [{ id: 8, targetName: '测试会话', unreadCount: 3 }], activeId: 8, error: '网络异常' })
  assert.match(failed, /测试会话/)
  assert.match(failed, /session-item active/)
  assert.match(failed, /role="alert"/)
  assert.doesNotMatch(failed, /暂无会话|class="unread-badge"/)
})

test('player orders read is scoped to the selected player and page, never an unfiltered fallback', async () => {
  const calls = []
  const api = async (...args) => { calls.push(args); return { data: { records: [{id: 2}], total: '3' } } }
  const query = { pageNum: 2, pageSize: 10, playerId: 999 }
  assert.deepEqual(await readPlayerOrders(17, query, api), { records: [{id: 2}], total: 3 })
  for (const bad of [undefined, null, 0, '']) await assert.rejects(readPlayerOrders(bad, query, api), /打手编号/)
  assert.deepEqual(calls, [[{playerId: 17, pageNum: 2, pageSize: 10}, {silent: true}]])
  assert.match(read('views/player/PlayerOrdersDialog.vue'), /props.isAdmin \? adminOrderList : csOrderList/)
})

test('local read failures never show global progress or consume the counter of a concurrent write', async () => {
  const calls = []
  let count = 0
  const api = createRequestClient(() => new Promise((resolve, reject) => calls.push({resolve, reject})),
    c => JSON.stringify([c.url, !!c.silent]), {start: () => count++, finish: () => count--})
  const local = api.get('/list', {silent: true}), write = api.post('/action', {}, {silent: true})
  assert.equal(count, 1)
  calls[0].reject(new Error('offline'))
  await assert.rejects(local, /offline/)
  assert.equal(count, 1)
  calls[1].resolve({})
  await write
  assert.equal(count, 0)
})
