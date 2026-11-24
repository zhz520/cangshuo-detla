<template>
  <view class="list-page">
    <scroll-view scroll-y class="list" @scrolltolower="loadMore">
      <view v-for="item in records" :key="item.id" class="record-item" @click="goDetail(item.id)">
        <view class="top-row">
          <text class="amount">¥{{ Number(item.amount).toFixed(2) }}</text>
          <StatusTag :status="item.status" :text-map="WITHDRAW_STATUS_TEXT" :color-map="WITHDRAW_STATUS_COLOR" />
        </view>
        <view class="info-row">
          <text class="account">{{ accountTypeText(item.accountTypeSnapshot || item.accountType) }} {{ item.accountNoSnapshot || item.accountNo || '账号未记录' }}</text>
          <text class="time">{{ item.createdAt }}</text>
        </view>
      </view>
      <PageState :phase="phase" :loading="loading && !records.length" :error="error" :empty="!records.length" empty-text="暂无提现记录" @retry="retry" />
      <LoadFeedback v-if="records.length && loading" :phase="phase" />
      <view v-else-if="records.length && finished && !error" class="loading-tip">已显示全部提现记录</view>
    </scroll-view>
  </view>
</template>
<script setup>
import LoadFeedback from '@/components/LoadFeedback.vue'
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import PageState from '@/components/PageState.vue'
import StatusTag from '@/components/StatusTag.vue'
import { getWithdrawList } from '@/api/player'
import { usePagedList } from '@/composables/usePagedList'

const WITHDRAW_STATUS_TEXT = { PENDING: '审核中', APPROVED: '已通过', REJECTED: '已拒绝', COMPLETED: '已到账', CANCELLED: '已取消' }
const WITHDRAW_STATUS_COLOR = { PENDING: '#996b30', APPROVED: '#347a5b', REJECTED: '#b84b55', COMPLETED: '#347a5b', CANCELLED: '#777777' }
const { items: records, loading, phase, error, finished, refresh, loadMore, retry, cancel, dispose } = usePagedList({
  pageSize: 20,
  async fetchPage({ pageNum, pageSize }) {
    const { data } = await getWithdrawList({ pageNum, pageSize }, { loading: false, silent: true })
    if (!Array.isArray(data?.records)) throw new Error('提现记录数据异常，请重试')
    return data
  }
})
onShow(() => refresh())
onHide(cancel)
onUnload(dispose)
function accountTypeText(type) { return { ALIPAY: '支付宝', WECHAT: '微信', BANK: '银行卡' }[type] || '收款账户' }
function goDetail(id) { if (id != null) uni.navigateTo({ url: '/pages-player/withdraw/detail?id=' + encodeURIComponent(id) }) }
</script>
<style lang="scss" scoped>
.list-page { background: var(--store-soft); min-height: var(--app-page-height, 100vh); }
.list { height: var(--app-page-height, 100vh); box-sizing: border-box; padding-bottom: var(--app-safe-bottom, env(safe-area-inset-bottom)); }
.record-item { margin: 20rpx 24rpx 0; padding: 24rpx; background: var(--store-surface); border: 1rpx solid var(--store-line); border-radius: 20rpx;
  .top-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12rpx; }
  .amount { font-size: 34rpx; font-weight: bold; color: var(--store-text); }
  .info-row { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 12rpx; .account { font-size: 24rpx; color: var(--store-regular); word-break: break-all; } .time { font-size: 22rpx; color: var(--store-regular); } }
}
.loading-tip { text-align: center; padding: 32rpx; font-size: 24rpx; color: var(--store-muted); }
</style>
