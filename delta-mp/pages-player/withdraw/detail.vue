<template>
  <view class="detail-page">
    <PageState :loading="loading && !detail" :error="error" @retry="load" />
    <view v-if="loading && detail" class="refresh-tip">正在更新记录…</view>
    <template v-if="detail">
    <!-- 状态 -->
    <view class="status-header" :class="statusClass">
      <text class="status-text">{{ WITHDRAW_STATUS_TEXT[detail.status] || detail.status }}</text>
      <text v-if="detail.rejectReason" class="reject-reason">原因：{{ detail.rejectReason }}</text>
    </view>
    <!-- 金额 -->
    <view class="amount-section">
      <text class="amount">¥{{ Number(detail.amount).toFixed(2) }}</text>
    </view>
    <!-- 详情信息 -->
    <view class="info-card">
      <view class="info-row"><text class="label">提现单号</text><text class="value">{{ detail.id }}</text></view>
      <view class="info-row"><text class="label">提现方式</text><text class="value">{{ accountTypeText }}</text></view>
      <view class="info-row"><text class="label">提现账号</text><text class="value">{{ detail.accountNoSnapshot || detail.accountNo || '未记录' }}</text></view>
      <view class="info-row"><text class="label">收款人</text><text class="value">{{ detail.accountNameSnapshot || detail.accountName || '未记录' }}</text></view>
      <view class="info-row"><text class="label">申请时间</text><text class="value">{{ detail.createdAt }}</text></view>
      <view v-if="detail.processedAt" class="info-row"><text class="label">处理时间</text><text class="value">{{ detail.processedAt }}</text></view>
    </view>
    <view v-if="detail.proofImage" class="proof-card">
      <text class="proof-label">打款凭证</text>
      <AppImage class="proof-image" :src="detail.proofImage" mode="aspectFit" @click="previewProof" />
    </view>
    </template>
  </view>
</template>
<script setup>
import { ref, computed } from 'vue'
import { onLoad, onShow, onUnload } from '@dcloudio/uni-app'
import { getWithdrawDetail } from '@/api/player'
import PageState from '@/components/PageState.vue'
import AppImage from '@/components/AppImage.vue'

const WITHDRAW_STATUS_TEXT = { PENDING: '审核中', APPROVED: '已通过', REJECTED: '已拒绝', COMPLETED: '已到账', CANCELLED: '已取消' }
const detail = ref(null)
const loading = ref(true)
const error = ref('')
let recordId = '', active, version = 0, disposed = false
const accountTypeText = computed(() => ({ ALIPAY: '支付宝', WECHAT: '微信', BANK: '银行卡' }[detail.value?.accountTypeSnapshot || detail.value?.accountType] || '收款账户'))
const statusClass = computed(() => {
  const map = { PENDING: 'pending', APPROVED: 'approved', COMPLETED: 'completed', REJECTED: 'rejected' }
  return map[detail.value?.status] || ''
})

onLoad(opts => { recordId = opts?.id || '' })
onShow(() => load())
onUnload(() => { disposed = true; version++ })
function load() {
  if (disposed) return Promise.resolve()
  if (active) return active
  if (!/^\d+$/.test(String(recordId))) { loading.value = false; error.value = '缺少有效的提现记录编号，请返回列表重新进入'; return Promise.resolve() }
  const current = ++version
  loading.value = true
  error.value = ''
  active = (async () => {
    try {
      const { data } = await getWithdrawDetail(recordId, { loading: false, silent: true })
      if (!data || data.id == null || data.amount == null) throw new Error('提现记录不存在或数据异常，请重试')
      if (!disposed && current === version) detail.value = data
    } catch (e) {
      if (!disposed && current === version) error.value = e?.msg || e?.message || '提现记录加载失败，请重试'
    } finally {
      if (!disposed && current === version) { loading.value = false; active = null }
    }
  })()
  return active
}
function previewProof() { if (detail.value?.proofImage) uni.previewImage({ urls: [detail.value.proofImage], current: detail.value.proofImage }) }
</script>
<style lang="scss" scoped>
.detail-page { background: var(--store-soft); min-height: var(--app-page-height, 100vh); box-sizing: border-box; padding: 1rpx 0 calc(24rpx + var(--app-safe-bottom, env(safe-area-inset-bottom))); }
.refresh-tip { color: var(--store-regular); font-size: 24rpx; text-align: center; padding: 16rpx; }
.status-header { margin: 24rpx 24rpx 0; border-radius: 20rpx; padding: 40rpx; text-align: center; color: var(--store-text); background: var(--store-surface); border: 1rpx solid var(--store-line);
  &.pending { color: #996b30; } &.approved, &.completed { color: #347a5b; } &.rejected { color: #b84b55; }
  .status-text { font-size: 36rpx; font-weight: bold; display: block; }
  .reject-reason { font-size: 24rpx; opacity: 0.9; display: block; margin-top: 8rpx; }
}
.amount-section { text-align: center; padding: 48rpx; .amount { font-size: 56rpx; font-weight: bold; color: var(--store-text); } }
.info-card, .proof-card { margin: 24rpx; background: var(--store-surface); border: 1rpx solid var(--store-line); border-radius: 20rpx; padding: 8rpx 24rpx; }
.proof-card { padding: 24rpx; }.proof-label { display: block; color: var(--store-regular); font-size: 26rpx; margin-bottom: 16rpx; }.proof-image { width: 100%; height: 360rpx; }
.info-row { display: flex; justify-content: space-between; gap: 24rpx; padding: 20rpx 0; border-bottom: 1rpx solid #f7f7f7;
  .label { font-size: 26rpx; color: var(--store-regular); flex-shrink: 0; } .value { font-size: 26rpx; color: var(--store-text); text-align: right; word-break: break-all; }
}
</style>
