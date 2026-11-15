import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL('../' + path, import.meta.url), 'utf8')

test('headers share one compact title size and a vector back icon', async () => {
  const header = await read('components/ScreenHeader.vue')
  const theme = await read('styles/default-2026.scss')
  assert.match(header, /header-back\.svg/)
  assert.match(header, /font-size: 40rpx/)
  assert.match(theme, /--store-title-size: 40rpx/)
  assert.match(theme, /--store-icon-size: 42rpx/)
})

test('homepage banner copy only comes from admin-configured fields', async () => {
  const home = await read('pages/index/index.vue')
  assert.match(home, /title: item\?\.title \|\| ''/)
  assert.match(home, /subtitle: item\?\.subtitle \|\| ''/)
  assert.match(home, /button: item\?\.buttonText \|\| ''/)
  assert.doesNotMatch(home, /storefront \? heroSubtitle/)
})

test('product image slots use a consistent 3:2-safe display without forced cropping', async () => {
  const card = await read('components/ProductCard.vue')
  const list = await read('pages/product/list.vue')
  const detail = await read('pages/product/detail.vue')
  const category = await read('pages/category/index.vue')
  const bubble = await read('components/ChatBubble.vue')
  assert.match(card, /coverHeight: \{ type: Number, default: 220 \}/)
  assert.match(card, /coverMode: \{ type: String, default: 'aspectFill' \}/)
  assert.match(list, /cover-height="460" cover-mode="aspectFit"/)
  assert.match(detail, /height: 460rpx/)
  assert.match(detail, /mode="aspectFit"/)
  assert.match(category, /width: 210rpx/)
  assert.match(category, /height: 140rpx/)
  assert.match(bubble, /width: 180rpx/)
  assert.match(bubble, /height: 120rpx/)
})

test('closed customer-service sessions expose a single rating flow', async () => {
  const room = await read('pages/chat/room.vue')
  const api = await read('api/chat.js')
  assert.match(api, /closeChatSession/)
  assert.match(api, /rateChatSession/)
  assert.match(room, /sessionStatus === 'CLOSED'/)
  assert.match(room, /评价本次客服服务/)
  assert.match(room, /submitRating/)
})

test('agreement pages read the admin content instead of embedding copy', async () => {
  const user = await read('pages/agreement/user.vue')
  const privacy = await read('pages/agreement/privacy.vue')
  const about = await read('pages/agreement/about.vue')
  const document = await read('components/ContentDocument.vue')
  assert.match(user, /type="user_agreement"/)
  assert.match(privacy, /type="privacy_policy"/)
  assert.match(about, /type="about_us"/)
  assert.match(document, /getContentByKey/)
})

test('missing avatars fall back to the DELTA default image', async () => {
  const image = await read('components/AppImage.vue')
  const mine = await read('pages/mine/index.vue')
  const chat = await read('components/ChatBubble.vue')
  assert.match(image, /fallback: \{ type: String, default: '\/static\/icons\/图片\.svg' \}/)
  assert.match(mine, /delta-default-avatar\.png/)
  assert.match(chat, /fallback="\/static\/images\/delta-default-avatar\.png"/)
})
