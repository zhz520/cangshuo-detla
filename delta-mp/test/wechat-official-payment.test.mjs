import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL(path, import.meta.url), 'utf8')

test('official-account pay is exposed for orders, recharge and player deposits', async () => {
  const api = await read('../api/pay.js')
  assert.match(api, /createOfficialPayment[\s\S]*\/pay\/wx\/official/)
  assert.match(api, /createOfficialRechargePayment[\s\S]*\/pay\/recharge\/official/)
  assert.match(api, /createOfficialPlayerDeposit[\s\S]*\/pay\/player-deposit\/official/)

  const order = await read('../pages/order/pay.vue')
  assert.match(order, /channel === 'OFFICIAL'[\s\S]*createOfficialPayment[\s\S]*authorizeUrl/)
  assert.match(order, /const wechatPayLabel = '微信支付'/)

  const recharge = await read('../pages/wallet/recharge.vue')
  assert.match(recharge, /channel === 'OFFICIAL'[\s\S]*createOfficialRechargePayment/)

  const apply = await read('../pages-player/apply/index.vue')
  assert.match(apply, /channel === 'OFFICIAL'[\s\S]*scene: 'APPLY'[\s\S]*authorizeUrl/)

  const withdraw = await read('../pages-player/withdraw/index.vue')
  assert.match(withdraw, /channel === 'OFFICIAL'[\s\S]*scene: 'WITHDRAW'[\s\S]*authorizeUrl/)
})

test('site config separates mini program, official account and external H5 switches', async () => {
  const store = await read('../store/site.js')
  assert.match(store, /wxPayMiniappEnabled/)
  assert.match(store, /wxPayOfficialEnabled/)
  assert.match(store, /wxPayH5Enabled/)
  assert.match(store, /wx_pay_official_enabled/)
  assert.match(store, /wx_pay_h5_enabled/)
})

test('deposit QR payment refreshes the withdrawal guard after the callback arrives', async () => {
  const withdraw = await read('../pages-player/withdraw/index.vue')
  assert.match(withdraw, /<PaymentQrDialog[^>]*@closed="onDepositQrClosed"/)
  assert.match(withdraw, /pollDepositStatus\(attempts = 40, interval = 3000\)/)
  assert.match(withdraw, /if \(!result\.data\.needsPayment\)[\s\S]*qrVisible\.value = false/)
  assert.match(withdraw, /depositStatus\.value = result\.data/)
})
