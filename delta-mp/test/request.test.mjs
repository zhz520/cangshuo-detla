import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
const loadingSource = await readFile(new URL('../utils/loading.js', import.meta.url), 'utf8')
const { createLoadingController } = await import('data:text/javascript;base64,' + Buffer.from(loadingSource).toString('base64'))

test('fast requests do not flash; overlapping requests keep their own loading handles', () => {
  let callback, shows = [], hides = 0, cancelled = 0
  const loader = createLoadingController({ show: x => shows.push(x), hide: () => hides++, schedule: fn => { callback = fn; return 1 }, cancel: () => cancelled++ })
  const fast = loader.begin(); fast(); assert.equal(shows.length, 0); assert.equal(cancelled, 1)
  const read = loader.begin(); const write = loader.begin(true); callback()
  assert.equal(shows[0].mask, true)
  write(); assert.equal(shows.at(-1).mask, false); assert.equal(hides, 0)
  write(); assert.equal(hides, 0)
  read(); assert.equal(hides, 1)
})

async function harness() {
  const requests = [], redirects = [], storage = new Map([['app_role', 'player'], ['player_token', 'player-a'], ['user_token', 'user-a']])
  globalThis.uni = { request: opts => requests.push(opts), showToast() {}, showLoading() {}, hideLoading() {}, reLaunch: opts => redirects.push(opts.url) }
  globalThis.getCurrentPages = () => [{ route: 'pages-player/mine/index' }]
  globalThis.__requestTestStorage = storage
  const source = (await readFile(new URL('../api/request.js', import.meta.url), 'utf8'))
    .replace("import { clientStorage } from '@/utils/client-storage'", "const clientStorage = { get: key => globalThis.__requestTestStorage.get(key), set: (key,v) => globalThis.__requestTestStorage.set(key,v), remove: key => globalThis.__requestTestStorage.delete(key) }")
    .replace("import { getCurrentRole, getTokenByRole, removeGuestSession } from '@/utils/auth'", "const getTokenByRole = role => clientStorage.get(role + '_token') || ''; const getCurrentRole = () => { const requested = clientStorage.get('app_role') || 'user'; if (requested === 'player' && getTokenByRole('player')) return 'player'; if (requested === 'cs' && getTokenByRole('cs')) return 'cs'; if (getTokenByRole('user')) return 'user'; if (getTokenByRole('guest')) return 'guest'; return requested }; const removeGuestSession = () => { clientStorage.remove('guest_token'); clientStorage.remove('guest_info') }")
    .replace("import { createLoadingController } from '@/utils/loading'", loadingSource.replace('export function', 'function'))
    .replaceAll('import.meta.env', '({ DEV:false })')
  const module = await import('data:text/javascript;base64,' + Buffer.from(source + '\n// ' + Math.random()).toString('base64'))
  function reply(index, code, data) { requests[index].success({ statusCode: code, data }); requests[index].complete() }
  return { ...module, requests, redirects, storage, reply }
}

test('concurrent GETs coalesce by identity, completed reads refresh, writes stay separate', async () => {
  const h = await harness()
  const first = h.get('/player/home', {}, { loading: false }), duplicate = h.get('/player/home', {}, { loading: false })
  assert.equal(h.requests.length, 1); assert.equal(first, duplicate)
  assert.equal(h.requests[0].timeout, 20000)
  h.reply(0, 200, { code: 200, data: { todayCompleted: 2 } }); await first
  const fresh = h.get('/player/home', {}, { loading: false }); assert.equal(h.requests.length, 2)
  h.reply(1, 200, { code: 200, data: {} }); await fresh
  const writes = [h.post('/player/withdraw', {}, { loading: false }), h.post('/player/withdraw', {}, { loading: false })]
  assert.equal(h.requests.length, 4)
  h.reply(2, 200, { code: 200 }); h.reply(3, 200, { code: 200 }); await Promise.all(writes)
})

test('old token or role responses do not log out a new session', async () => {
  const h = await harness()
  const old = h.get('/player/profile', {}, { loading: false }); const rejected = assert.rejects(old)
  h.storage.set('player_token', 'player-b'); h.reply(0, 401, { code: 401 }); await rejected
  assert.equal(h.storage.get('player_token'), 'player-b'); assert.equal(h.redirects.length, 0)
  const background = h.get('/player/home', {}, { loading: false }); const rejectedBackground = assert.rejects(background)
  h.storage.set('app_role', 'user'); h.reply(1, 401, { code: 401 }); await rejectedBackground
  assert.equal(h.storage.get('user_token'), 'user-a'); assert.equal(h.redirects.length, 0)
})

test('failed requests leave the shared read slot so retry really sends again', async () => {
  const h = await harness()
  const failed = h.get('/player/work/my', {}, { loading: false }); const rejected = assert.rejects(failed)
  h.requests[0].fail({ errMsg: 'request:fail timeout' }); h.requests[0].complete(); await rejected
  const retry = h.get('/player/work/my', {}, { loading: false })
  assert.equal(h.requests.length, 2); h.reply(1, 200, { code: 200, data: [] }); await retry
})

test('a refresh after a mutation cannot reuse a read started before it', async () => {
  const h = await harness()
  const old = h.get('/player/home', {}, { loading: false })
  const write = h.post('/player/online-status', {}, { loading: false })
  h.reply(1, 200, {code:200}); await write
  const fresh = h.get('/player/home', {}, { loading: false })
  assert.equal(h.requests.length, 3); assert.notEqual(old, fresh)
  h.reply(2, 200, {code:200,data:{fresh:true}}); h.reply(0,200,{code:200,data:{fresh:false}})
  assert.equal((await fresh).data.fresh,true); await old
})
