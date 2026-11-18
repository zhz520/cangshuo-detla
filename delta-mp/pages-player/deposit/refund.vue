<template>
  <view class="deposit-refund-page">
    <PageState :loading="loading && !ready" :error="loadError" @retry="load" />
    <template v-if="ready">
      <view class="summary-card">
        <text class="summary-label">已缴押金</text>
        <text class="summary-amount">¥{{ money(context.paidAmount) }}</text>
        <text class="summary-sub">当前应缴 ¥{{ money(context.requiredAmount) }} · 待补 ¥{{ money(context.outstandingAmount) }}</text>
      </view>

      <view class="section">
        <text class="section-title">可申请退款的押金</text>
        <view v-for="item in context.payments" :key="item.paymentId" class="payment-card">
          <view class="payment-head">
            <text class="payment-amount">¥{{ money(item.amount) }}</text>
            <text class="payment-time">{{ item.paidAt || item.refundStatus }}</text>
          </view>
          <text class="payment-no">{{ item.paymentNo }}</text>
          <text v-if="item.blockReason" class="payment-block">{{ item.blockReason }}</text>
          <button v-else-if="item.eligible" class="refund-button" :disabled="busy" @click="openReason(item)">申请退款</button>
        </view>
        <EmptyState v-if="!context.payments.length" text="暂无已缴押金" image="/pages-player/static/icons/暂无记录.svg" />
      </view>

      <view class="section">
        <text class="section-title">退款申请记录</text>
        <view v-for="item in context.history" :key="item.id" class="history-card">
          <view class="history-head">
            <text class="history-amount">¥{{ money(item.amount) }}</text>
            <text :class="['history-status', item.status.toLowerCase()]">{{ statusText(item.status) }}</text>
          </view>
          <text class="history-reason">{{ item.reason }}</text>
          <text v-if="item.reviewNote" class="history-note">审核说明：{{ item.reviewNote }}</text>
          <text class="history-time">{{ item.createdAt }}</text>
          <button v-if="item.status === 'PENDING'" class="cancel-button" :disabled="busy" @click="cancel(item.id)">取消申请</button>
        </view>
        <EmptyState v-if="!context.history.length" text="暂无退款申请" image="/pages-player/static/icons/暂无记录.svg" />
      </view>
    </template>

    <view v-if="reasonVisible" class="mask" @click="closeReason">
      <view class="reason-panel" @click.stop>
        <text class="reason-title">押金退款申请</text>
        <text class="reason-tip">退款将按原支付渠道退回，提交后由客服审核。</text>
        <textarea v-model="reason" class="reason-input" maxlength="500" placeholder="请填写退款原因（5-500字）" />
        <view class="reason-actions">
          <button class="reason-cancel" :disabled="busy" @click="closeReason">取消</button>
          <button class="reason-submit" :disabled="busy || reason.trim().length < 5" @click="submit">{{ busy ? '提交中…' : '提交申请' }}</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import PageState from '@/components/PageState.vue'
import EmptyState from '@/components/EmptyState.vue'
import { getDepositRefundContext, submitDepositRefund, cancelDepositRefund } from '@/api/player'

const loading = ref(false), ready = ref(false), loadError = ref(''), busy = ref(false)
const context = ref({ requiredAmount: 0, paidAmount: 0, outstandingAmount: 0, payments: [], history: [] })
const reasonVisible = ref(false), reason = ref(''), selected = ref(null)

function money(value) { return Number(value || 0).toFixed(2) }
function statusText(status) {
  return ({ PENDING: '待审核', REFUNDING: '退款中', REFUNDED: '已退款', REJECTED: '已驳回', FAILED: '退款失败', CANCELLED: '已取消' })[status] || status
}
async function load() {
  if (loading.value) return
  loading.value = true; loadError.value = ''
  try {
    const result = await getDepositRefundContext()
    context.value = result.data || context.value
    ready.value = true
  } catch (_) {
    loadError.value = '押金退款信息加载失败，请重试'
  } finally { loading.value = false }
}
function openReason(item) { selected.value = item; reason.value = ''; reasonVisible.value = true }
function closeReason() { if (!busy.value) reasonVisible.value = false }
async function submit() {
  if (!selected.value || reason.value.trim().length < 5 || busy.value) return
  busy.value = true
  try {
    await submitDepositRefund({ paymentId: selected.value.paymentId, reason: reason.value.trim() })
    reasonVisible.value = false
    uni.showToast({ title: '退款申请已提交', icon: 'none' })
    await load()
  } catch (_) {} finally { busy.value = false }
}
async function cancel(id) {
  if (busy.value) return
  const modal = await new Promise(resolve => uni.showModal({
    title: '取消退款申请', content: '确认取消本次押金退款申请？', success: resolve, fail: () => resolve({ confirm: false })
  }))
  if (!modal.confirm) return
  busy.value = true
  try {
    await cancelDepositRefund(id)
    uni.showToast({ title: '已取消', icon: 'none' })
    await load()
  } catch (_) {} finally { busy.value = false }
}
onShow(load)
</script>

<style lang="scss" scoped>
.deposit-refund-page { min-height: var(--app-page-height, 100vh); padding: 24rpx; background: var(--store-soft); box-sizing: border-box; }
.summary-card { padding: 32rpx; border-radius: 20rpx; background: var(--store-primary); color: #fff; }
.summary-label { display: block; font-size: 24rpx; opacity: .82; }
.summary-amount { display: block; margin-top: 8rpx; font-size: 52rpx; font-weight: 800; }
.summary-sub { display: block; margin-top: 10rpx; font-size: 23rpx; opacity: .82; }
.section { margin-top: 26rpx; padding: 24rpx; border-radius: 20rpx; background: var(--store-surface); }
.section-title { display: block; margin-bottom: 18rpx; font-size: 29rpx; font-weight: 700; color: var(--store-text); }
.payment-card, .history-card { padding: 20rpx 0; border-top: 1rpx solid var(--store-line); }
.payment-card:first-of-type, .history-card:first-of-type { border-top: 0; }
.payment-head, .history-head { display: flex; justify-content: space-between; align-items: baseline; gap: 16rpx; }
.payment-amount, .history-amount { font-size: 32rpx; font-weight: 700; color: var(--store-text); }
.payment-time, .history-time { color: var(--store-muted); font-size: 22rpx; }
.payment-no, .history-reason, .history-note { display: block; margin-top: 8rpx; color: var(--store-regular); font-size: 23rpx; word-break: break-all; }
.payment-block { display: block; margin-top: 10rpx; color: #a15c00; font-size: 23rpx; line-height: 1.6; }
.refund-button, .cancel-button { margin: 16rpx 0 0; padding: 12rpx 24rpx; border-radius: 999rpx; font-size: 25rpx; }
.refund-button { background: var(--store-primary); color: #fff; }
.cancel-button { background: var(--store-line); color: var(--store-text); }
.history-status { font-size: 23rpx; color: var(--store-muted); }
.history-status.pending, .history-status.refunding { color: #a15c00; }
.history-status.refunded { color: #1f7a4d; }
.history-status.rejected, .history-status.failed { color: #a63842; }
.mask { position: fixed; inset: 0; z-index: 1200; display: flex; align-items: flex-end; background: rgba(17,19,22,.55); }
.reason-panel { width: 100%; padding: 30rpx 28rpx calc(28rpx + env(safe-area-inset-bottom)); border-radius: 24rpx 24rpx 0 0; background: var(--store-surface); box-sizing: border-box; }
.reason-title { display: block; font-size: 32rpx; font-weight: 800; color: var(--store-text); }
.reason-tip { display: block; margin-top: 8rpx; color: var(--store-muted); font-size: 23rpx; }
.reason-input { box-sizing: border-box; width: 100%; min-height: 180rpx; margin-top: 20rpx; padding: 18rpx; border-radius: 14rpx; background: var(--store-soft); font-size: 27rpx; }
.reason-actions { display: flex; gap: 16rpx; margin-top: 22rpx; }
.reason-cancel, .reason-submit { flex: 1; padding: 20rpx; border-radius: 999rpx; font-size: 27rpx; }
.reason-cancel { background: var(--store-line); color: var(--store-text); }
.reason-submit { background: var(--store-primary); color: #fff; }
</style>
