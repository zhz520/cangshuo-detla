import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const read = path => readFile(new URL('../' + path, import.meta.url), 'utf8')

test('desktop order QR payment polls the order and redirects after mobile payment', async () => {
  const page = await read('pages/order/pay.vue')
  const api = await read('api/order.js')
  const dialog = await read('components/PaymentQrDialog.vue')
  assert.match(api, /getOrderDetail = \(id, opts = \{\}\)/)
  assert.match(page, /startQrPolling/)
  assert.match(page, /getOrderDetail\(orderId\.value, \{ loading: false, silent: true \}\)/)
  assert.match(page, /setInterval\(pollQrPayment, 2000\)/)
  assert.match(page, /支付完成后会自动跳转/)
  assert.match(page, /uni\.redirectTo/)
  assert.match(dialog, /支付完成后会自动跳转|hint/)
})
