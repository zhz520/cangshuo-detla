<template>
  <view class="earnings-page">
    <PageState :loading="loading && !summary" :error="error" @retry="load" />
    <view v-if="loading && summary" class="refresh-tip">正在更新收益…</view>
    <template v-if="summary">
    <!-- 收益概览 -->
    <view class="summary-card">
      <text class="card-label">累计收益（元）</text>
      <text class="total-amount">¥{{ summary.totalIncome || '0.00' }}</text>
      <view class="wallet-split"><text>可提现 ¥{{ Number(summary.balance || 0).toFixed(2) }}</text><text>待结算 ¥{{ Number(summary.pendingIncome || 0).toFixed(2) }}</text></view>
      <text v-if="Number(summary.pendingRefundRecovery)>0" class="reward-total">退款待追回 ¥{{Number(summary.pendingRefundRecovery).toFixed(2)}}，后续订单收益优先抵扣</text>
      <text class="reward-total">其中打赏收入 ¥{{ Number(summary.rewardIncome || 0).toFixed(2) }}</text>
      <view class="row">
        <view class="col"><text class="num">¥{{ summary.monthIncome || '0.00' }}</text><text class="desc">本月收益</text></view>
        <view class="divider" />
        <view class="col"><text class="num">¥{{ summary.weekIncome || '0.00' }}</text><text class="desc">本周收益</text></view>
        <view class="divider" />
        <view class="col"><text class="num">¥{{ summary.todayIncome || '0.00' }}</text><text class="desc">今日收益</text></view>
      </view>
    </view>
    <!-- 统计卡片 -->
    <view class="stat-grid">
      <view class="stat-item">
        <text class="stat-num">{{ summary.totalOrders || 0 }}</text>
        <text class="stat-label">累计订单</text>
      </view>
      <view class="stat-item">
        <text class="stat-num">{{ summary.monthOrders || 0 }}</text>
        <text class="stat-label">本月订单</text>
      </view>
      <view class="stat-item">
        <text class="stat-num">¥{{ summary.avgOrderAmount || '0.00' }}</text>
        <text class="stat-label">单均收益</text>
      </view>
      <view class="stat-item">
        <text class="stat-num">{{ summary.completionRate || '0' }}%</text>
        <text class="stat-label">完成率</text>
      </view>
    </view>
    </template>
    <!-- 操作入口 -->
    <view class="action-list">
      <view class="action-row" @click="goDetail">
        <text>📋 收益明细</text><text class="arrow">></text>
      </view>
      <view class="action-row" @click="goWithdraw">
        <text>💳 去提现</text><text class="arrow">></text>
      </view>
    </view>
  </view>
</template>
<script setup>
import { ref } from 'vue'
import { onShow, onUnload } from '@dcloudio/uni-app'
import { getEarningsSummary } from '@/api/player'
import PageState from '@/components/PageState.vue'

const summary = ref(null)
const loading = ref(true)
const error = ref('')
let active, version = 0, disposed = false

onShow(() => load())
onUnload(() => { disposed = true; version++ })
function load() {
  if (disposed) return Promise.resolve()
  if (active) return active
  const current = ++version
  loading.value = true
  error.value = ''
  active = (async () => {
    try {
      const { data } = await getEarningsSummary({ loading: false, silent: true })
      if (!data || typeof data !== 'object' || Array.isArray(data) || data.totalIncome == null) throw new Error('收益数据异常，请重试')
      if (!disposed && current === version) summary.value = data
    } catch (e) {
      if (!disposed && current === version) error.value = e?.msg || e?.message || '收益加载失败，请重试'
    } finally {
      if (!disposed && current === version) { loading.value = false; active = null }
    }
  })()
  return active
}

function goDetail() { uni.navigateTo({ url: '/pages-player/earnings/detail' }) }
function goWithdraw() { uni.navigateTo({ url: '/pages-player/withdraw/index' }) }
</script>
<style lang="scss" scoped>
.earnings-page { background: #f7f8fa; min-height: var(--app-page-height, 100vh); padding: 1rpx 0 calc(32rpx + var(--app-safe-bottom, env(safe-area-inset-bottom))); box-sizing: border-box; }
.refresh-tip { color: #73777e; font-size: 24rpx; text-align: center; padding: 16rpx; }
.reward-total { display:block;font-size:24rpx;opacity:.85;margin:-12rpx 0 28rpx; }
.summary-card { background: linear-gradient(135deg, #17191c, #30343a, #30343a); color: #ffffff; font-weight:bold; margin: 24rpx; border-radius: 16rpx; padding: 40rpx 32rpx;
  .card-label { font-size: 26rpx; opacity: 0.85; display: block; }
  .total-amount { font-size: 56rpx; font-weight: bold; display: block; margin: 16rpx 0 32rpx; }
  .wallet-split { display:flex; justify-content:space-between; gap:16rpx; font-size:23rpx; opacity:.88; margin:-18rpx 0 30rpx; }
  .row { display: flex; align-items: center; }
  .col { flex: 1; text-align: center; .num { font-size: 30rpx; font-weight: bold; display: block; } .desc { font-size: 22rpx; opacity: 0.8; display: block; margin-top: 4rpx; } }
  .divider { width: 1rpx; height: 48rpx; background: rgba(255,255,255,0.3); }
}
.stat-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 20rpx; padding: 0 24rpx 24rpx;
  .stat-item { background: #fff; border: 1rpx solid #edf0f2; padding: 24rpx; border-radius: 20rpx; text-align: center;
    .stat-num { font-size: 32rpx; font-weight: bold; color: #17191c; display: block; }
    .stat-label { font-size: 24rpx; color: #73777e; display: block; margin-top: 8rpx; }
  }
}
.action-list { margin: 0 24rpx; background: #fff; border: 1rpx solid #edf0f2; border-radius: 20rpx; overflow: hidden; }
.action-row { display: flex; justify-content: space-between; align-items: center; padding: 28rpx 24rpx; border-bottom: 1rpx solid #f4f5f6; font-size: 28rpx; .arrow { color: #b9bdc4; } }
</style>
