import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL('../' + path, import.meta.url), 'utf8')

test('site configuration exposes independent purchase and guest-checkout switches', async () => {
  const source = await read('store/site.js')
  assert.match(source, /orderPurchaseEnabled = ref\(false\)/)
  assert.match(source, /orderContactPurchaseEnabled = ref\(false\)/)
  assert.match(source, /purchaseContactOnly/)
  assert.match(source, /guestCheckoutEnabled = ref\(false\)/)
  assert.match(source, /data\.order_purchase_enabled/)
  assert.match(source, /data\.order_contact_purchase_enabled/)
  assert.match(source, /data\.order_guest_checkout_enabled/)
  assert.match(source, /playerDirectoryEnabled/)
  assert.match(source, /data\.player_directory_enabled/)
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
  }
})

test('product detail renders rich description images and contact-only purchase state', async () => {
  const source = await read('pages/product/detail.vue')
  assert.match(source, /productDescriptionHtml\(product\.value\?\.detail \|\| product\.value\?\.description\)/)
  assert.match(source, /siteStore\.orderContactPurchaseEnabled/)
  assert.match(source, />联系客服购买</)
  assert.match(source, /rich-body :deep\(img\)/)
  const content = await read('utils/product-content.js')
  assert.match(content, /max-width: 100%', 'height: auto'/)
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

test('guest lookup entry is switch-gated and lives on the mine page', async () => {
  const home = await read('pages/index/index.vue')
  const mine = await read('pages/mine/services.vue')
  assert.doesNotMatch(home, /guest-order-entry|goGuestLookup/)
  assert.match(mine, /siteStore\.guestCheckoutEnabled/)
  assert.match(mine, />游客订单</)
})

test('protected order pages redirect missing sessions before fetching', async () => {
  const list = await read('pages/order/list.vue')
  const detail = await read('pages/order/detail.vue')
  assert.match(list, /userStore\.syncSessionFromStorage\?\.\(\)/)
  assert.match(list, /uni\.redirectTo\(\{ url: '\/pages\/login\/index\?redirect='/)
  assert.match(list, /error\.value = e\?\.msg/)
  assert.match(detail, /userStore\.syncSessionFromStorage\?\.\(\)/)
  assert.match(detail, /uni\.redirectTo\(\{ url: '\/pages\/login\/index\?redirect='/)
  assert.match(detail, /loadError\.value = e\?\.msg/)
})

test('unauthenticated purchase entry points do not force a login redirect', async () => {
  const product = await read('pages/product/detail.vue')
  const playerCard = await read('components/PlayerListCard.vue')
  const player = await read('pages/players/detail.vue')
  const create = await read('pages/order/create.vue')
  assert.doesNotMatch(product, /function goBuy\([\s\S]*?userStore\.checkLogin/)
  assert.doesNotMatch(playerCard, /function placeOrder\([\s\S]*?userStore\.checkLogin/)
  assert.doesNotMatch(player, /function placeOrder\([\s\S]*?userStore\.checkLogin/)
  assert.match(create, /游客下单暂未开放，请登录后下单/)
  const user = await read('store/user.js')
  assert.match(user, /function checkLogin\([\s\S]*?showModal[\s\S]*?confirmText: '去登录'/)
})

test('formal login and logout clear any previous guest session', async () => {
  const source = await read('store/user.js')
  assert.match(source, /function applyLoginResult[\s\S]*?clearGuestSession\(\)/)
  assert.match(source, /function logout\([\s\S]*?getGuestToken\(\)[\s\S]*?clearGuestSession\(\)/)
  assert.match(source, /async function exitGuest\(\)/)
  const app = await read('App.vue')
  assert.match(app, /syncSessionFromStorage\(\)/)
  const card = await read('components/OrderCard.vue')
  const list = await read('pages/order/list.vue')
  assert.match(card, /ownerActions/)
  assert.match(list, /canUseOwnerActions/)
  const services = await read('pages/mine/services.vue')
  assert.match(services, /if \(userStore\.isLoggedIn\) officialWechat\.load\('user'\)/)
})

test('the player directory tab and direct pages respect the display switch', async () => {
  const tab = await read('components/CustomTabBar.vue')
  const list = await read('pages/players/index.vue')
  const detail = await read('pages/players/detail.vue')
  assert.match(tab, /siteStore\.playerDirectoryEnabled/)
  assert.match(list, /打手页面暂未开放/)
  assert.match(detail, /打手页面暂未开放/)
})
