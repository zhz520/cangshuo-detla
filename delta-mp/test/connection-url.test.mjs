import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
const source = await readFile(new URL('../utils/connection-url.js', import.meta.url), 'utf8')
const { resolveWebSocketUrl } = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'))
test('shared H5 uses the customer origin even when built with another customer URL', () => {
  assert.equal(resolveWebSocketUrl({location:{protocol:'https:',host:'customer.example'},configured:'wss://other.example/ws/chat'}), 'wss://customer.example/ws/chat')
  assert.equal(resolveWebSocketUrl({location:{protocol:'http:',host:'127.0.0.1:7421'}}), 'ws://127.0.0.1:7421/ws/chat')
})
test('explicit instance configuration supports proxy paths without downgrading HTTPS', () => {
  assert.equal(resolveWebSocketUrl({location:{protocol:'https:',host:'customer.example'},runtime:'wss://chat.example/ws'}), 'wss://chat.example/ws')
  assert.equal(resolveWebSocketUrl({location:{protocol:'https:',host:'customer.example'},runtime:'ws://chat.example/ws'}), 'wss://customer.example/ws/chat')
  assert.equal(resolveWebSocketUrl({location:{protocol:'https:',host:'customer.example'},runtime:'javascript:bad'}), 'wss://customer.example/ws/chat')
})
test('mini program keeps its deployment endpoint', () => {
  assert.equal(resolveWebSocketUrl({configured:'wss://mini.example/ws/chat'}), 'wss://mini.example/ws/chat')
})
