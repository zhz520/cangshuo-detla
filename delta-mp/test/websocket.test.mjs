import { readFile } from 'node:fs/promises'
import test from 'node:test'
import assert from 'node:assert/strict'
const urlSource = (await readFile(new URL('../utils/connection-url.js', import.meta.url), 'utf8')).replace('export function', 'function')
const original = await readFile(new URL('../utils/websocket.js', import.meta.url), 'utf8')
async function harness() {
  let timerId = 0
  const timers = new Map(), sockets = []
  globalThis.__socketHarness = { timers, sockets,
    setTimer(fn, delay) { timers.set(++timerId, { fn, delay }); return timerId },
    clearTimer(id) { timers.delete(id) }
  }
  globalThis.uni = { connectSocket() {
    const socket = { handlers:{}, sent:[], closed:0, send(x) { this.sent.push(JSON.parse(x.data)) }, close() { this.closed++ } }
    for (const name of ['Open','Message','Error','Close']) socket['on'+name] = fn => { socket.handlers[name.toLowerCase()] = fn }
    sockets.push(socket); return socket
  } }
  const prelude = `const setTimeout = (fn,delay) => globalThis.__socketHarness.setTimer(fn,delay), clearTimeout = id => globalThis.__socketHarness.clearTimer(id), setInterval = setTimeout, clearInterval = clearTimeout; const console = {log(){},warn(){},error(){}};\n`
  const source = prelude + original.replace("import { resolveWebSocketUrl } from './connection-url'", urlSource).replaceAll('import.meta.env', '({})')
  const module = await import('data:text/javascript;base64,' + Buffer.from(source + '\n// ' + Math.random()).toString('base64'))
  function run(delay) { const found = [...timers].find(([,t])=>t.delay === delay); assert(found, 'timer '+delay); timers.delete(found[0]); found[1].fn() }
  function authenticate(socket) { socket.handlers.open(); socket.handlers.message({data:JSON.stringify({type:'auth',success:true})}) }
  return { ...module, sockets, timers, run, authenticate }
}
test('explicit logout cancels a queued role switch and never reconnects', async () => {
  const h = await harness(); h.connectWebSocket('a',{chatRole:'USER'}); h.authenticate(h.sockets[0])
  h.connectWebSocket('b',{chatRole:'PLAYER'}); h.closeWebSocket(); h.sockets[0].handlers.close()
  assert.equal(h.sockets.length, 1); assert.equal(h.timers.size, 0); assert.equal(h.isWsConnected(), false)
})
test('late events from a replaced socket cannot authenticate or close the new role connection', async () => {
  const h = await harness(); h.connectWebSocket('a',{chatRole:'USER'}); const old = h.sockets[0]
  h.connectWebSocket('b',{chatRole:'PLAYER'}); old.handlers.close(); h.run(100)
  const current = h.sockets[1]
  old.handlers.message({data:JSON.stringify({type:'auth',success:true})}); assert.equal(h.isWsConnected(),false)
  h.authenticate(current); assert.equal(current.sent[0].token, 'b'); assert.equal(current.sent[0].chatRole,'PLAYER')
  old.handlers.close(); assert.equal(h.isWsConnected(),true)
  h.closeWebSocket(); current.handlers.close(); assert.equal(h.timers.size,0)
})
test('a stalled handshake is closed instead of remaining CONNECTING forever', async () => {
  const h = await harness(); h.connectWebSocket('a'); h.run(12000)
  assert.equal(h.sockets[0].closed,1)
  h.closeWebSocket(); h.sockets[0].handlers.close(); assert.equal(h.timers.size,0)
})
