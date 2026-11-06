import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL('../' + path, import.meta.url), 'utf8')

test('site configuration exposes independent purchase and guest-checkout switches', async () => {
  const source = await read('store/site.js')
  assert.match(source, /orderPurchaseEnabled = ref\(false\)/)
  assert.match(source, /guestCheckoutEnabled = ref\(false\)/)
  assert.match(source, /data\.order_purchase_enabled/)
  assert.match(source, /data\.order_guest_checkout_enabled/)
})

test('all frontend immediate purchase entry points obey the purchase switch', async () => {
  const files = [
    'pages/product/detail.vue',
    'pages/players/detail.vue',
    'components/PlayerListCard.vue'
  ]
  for (const file of files) {
    const source = await read(file)
    assert.match(source, /siteStore\.orderPurchaseEnabled/)
    assert.match(source, /guestCheckoutEnabled/)
  }
})

test('guest order form requires phone, lookup code and privacy consent', async () => {
  const source = await read('pages/order/create.vue')
  assert.match(source, /游客下单凭证/)
  assert.match(source, /guestPhone/)
  assert.match(source, /guestLookupCode/)
  assert.match(source, /guestPrivacyAccepted/)
  assert.match(source, /createGuestOrder/)
  assert.match(source, /setGuestSession/)
  assert.match(source, /pages\/order\/lookup/)
})

test('guest lookup page can query or bind without rewriting order ownership', async () => {
  const source = await read('pages/order/lookup.vue')
  assert.match(source, /lookupGuestOrder/)
  assert.match(source, /bindGuestOrder/)
  assert.match(source, /游客订单 ID 不会被改写/)
  const auth = await read('utils/auth.js')
  assert.match(auth, /guest_token/)
  assert.match(auth, /getCurrentRole/)
})
