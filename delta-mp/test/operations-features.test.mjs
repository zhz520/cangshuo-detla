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
