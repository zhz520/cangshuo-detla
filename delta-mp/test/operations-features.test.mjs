import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL('../' + path, import.meta.url), 'utf8')

test('H5 login honors server login policy and submits a captured invite code', async () => {
  const page = await read('pages/login/index.vue')
  const store = await read('store/user.js')
  const app = await read('App.vue')
  assert.match(page, /v-if="quickLoginEnabled"/)
  assert.match(page, /v-if="passwordEnabled"/)
  assert.match(page, /phoneLoginEnabled\.value=Boolean\(data\?\.phoneEnabled\)/)
  assert.match(page, /passwordEnabled\.value=Boolean\(data\?\.passwordEnabled\)/)
  assert.match(page, /clientStorage\.set\('invite_code',invited\)/)
  assert.match(store, /clientStorage\.get\('invite_code'\)/)
  assert.match(store, /h5Login\(\{ phone, code, requestId, privacyAccepted:true, inviteCode \}\)/)
  assert.match(app, /oauth\.get\('invite'\)/)
})

test('identity page uses the metered server verification and removes photo upload from that path', async () => {
  const page = await read('pages/mine/identity.vue')
  const api = await read('api/user.js')
  assert.match(page, /v-if="!status\.autoAvailable"/)
  assert.match(page, /await autoVerifyIdentity\(payload\)/)
  assert.match(page, /本次核验费用由平台承担，不向用户单独收费/)
  assert.match(api, /post\('\/user\/identity\/auto-verify'/) 
})

test('public purchase feed requires consent and ranking exposes user privacy controls', async () => {
  const page = await read('pages/display/index.vue')
  const api = await read('api/display.js')
  assert.match(page, /purchaseFeedConsent/)
  assert.match(page, /changePrivacy\('spendRankVisible'/)
  assert.match(page, /changePrivacy\('purchaseFeedConsent'/)
  assert.match(api, /\/user\/display\/privacy/)
})

test('home prioritizes products and presents recent purchases as a floating ticker', async () => {
  const home = await read('pages/index/index.vue')
  const mine = await read('pages/mine/index.vue')
  const ticker = await read('components/RecentPurchaseTicker.vue')
  const display = await read('pages/display/index.vue')
  assert.doesNotMatch(home, /class="community-entry"/)
  assert.doesNotMatch(home, /pages\/(membership|coupons|invites)\/index/)
  assert.match(home, /v-if="banners.length"/)
  assert.match(home, /v-if="categories.length"/)
  assert.match(home, /<RecentPurchaseTicker/)
  assert.match(home, /getPurchaseFeed\(/)
  assert.match(mine, /class="service-panel"/)
  assert.match(mine, /class="more-card"/)
  assert.doesNotMatch(mine, /WorkbenchEntry/)
  assert.match(mine, /\/pages\/membership\/index/)
  assert.match(mine, /\/pages\/coupons\/index/)
  assert.match(mine, /\/pages\/invites\/index/)
  assert.match(mine, /\/pages\/display\/index\?tab=rank/)
  assert.match(ticker, /class="purchase-ticker"/)
  assert.match(ticker, /vertical/)
  assert.match(ticker, /position:\s*fixed/)
  assert.match(display, /options\?\.tab === 'feed'/)
})

test('mine page keeps a compact service layout and reflects player application state', async () => {
  const mine = await read('pages/mine/index.vue')
  assert.match(mine, />更多服务</)
  assert.match(mine, /class="legal-links"/)
  assert.doesNotMatch(mine, /section-card/)
  assert.match(mine, /getPlayerApplicationStatus\(\)/)
  assert.match(mine, /playerStatus\.value==='ACTIVE'/)
  assert.match(mine, /playerStatus\.value==='PENDING'/)
  assert.match(mine, /playerStatus\.value==='FROZEN'/)
  assert.match(mine, /playerStatus\.value==='REJECTED'/)
})

test('invite center shares a link that carries the invite code', async () => {
  const page = await read('pages/invites/index.vue')
  assert.match(page, /\?invite=\$\{encodeURIComponent\(info\.value\.code\)\}/)
  assert.match(page, /customerWebUrl/)
  assert.match(page, /复制邀请链接/)
})

test('teammate acceptance confirms the invited split before calling the API', async () => {
  const page = await read('pages-player/invite/list.vue')
  assert.match(page, /title: '确认接受邀请'/) 
  assert.match(page, /按邀请时的分成方案履约/) 
  assert.match(page, /if \(!confirmed \|\| disposed\) return[\s\S]*?await acceptInvite\(orderId\)/) 
})
