<template>
  <view class="detail-page">
    <scroll-view scroll-y class="list" @scrolltolower="loadMore">
      <view v-for="item in records" :key="item.id" class="record-item">
        <view class="left">
          <text class="desc">{{ item.type === 'INCOME' ? '订单收益' : TX_TYPE_MAP[item.type] || item.type }}</text>
          <text class="time">{{ item.createdAt }}</text>
          <text v-if="String(item.type || '').startsWith('REWARD_')" class="time">来源订单 {{ item.relatedOrderId || '-' }} · {{ item.remark }}</text>
          <view v-if="item.remarkInfo" class="remark-info">
            <text>订单金额: ¥{{ item.remarkInfo.orderAmount }}</text>
            <text>抽成: {{ item.remarkInfo.commissionRate }}% (¥{{ item.remarkInfo.commissionAmount }})</text>
          </view>
        </view>
        <text class="amount" :class="{ income: Number(item.amount) > 0 }">
          {{ Number(item.amount) > 0 ? '+' : '' }}¥{{ Number(item.amount).toFixed(2) }}
        </text>
      </view>
      <PageState :loading="loading && !records.length" :error="error" :empty="!records.length" empty-text="暂无收益明细" @retry="retry" />
      <view v-if="records.length && loading" class="loading-tip">正在加载…</view>
      <view v-else-if="records.length && finished && !error" class="loading-tip">已显示全部收益明细</view>
    </scroll-view>
  </view>
</template>
<script setup>
import { onShow, onHide, onUnload } from '@dcloudio/uni-app'
import PageState from '@/components/PageState.vue'
import { getEarningsDetail } from '@/api/player'
import { usePagedList } from '@/composables/usePagedList'

const TX_TYPE_MAP = { REWARD_PAY:'打赏支出', REWARD_INCOME:'打赏收入', REWARD_REFUND:'打赏退款', REWARD_REVERSAL:'打赏冲正', PAY: '支付', REFUND: '退款', WITHDRAW: '提现', INCOME: '订单收益', RECHARGE: '充值' }
const { items: records, loading, error, finished, refresh, loadMore, retry, cancel, dispose } = usePagedList({
  pageSize: 20,
  async fetchPage({ pageNum, pageSize }) {
    const { data } = await getEarningsDetail({ pageNum, pageSize }, { loading: false, silent: true })
    if (!Array.isArray(data?.records)) throw new Error('收益明细数据异常，请重试')
    return { ...data, records: data.records.map(item => ({ ...item, remarkInfo: parseRemark(item.remark) })) }
  }
})
onShow(() => refresh())
onHide(cancel)
onUnload(dispose)

/** 解析 remark 字符串为结构化数据 */
function parseRemark(remark) {
  if (!remark) return null
  try {
    const orderAmount = remark.match(/订单金额:¥([\d.]+)/)?.[1]
    const commissionRate = remark.match(/平台抽成:([\d.]+)%/)?.[1]
    const commissionAmount = remark.match(/抽成金额:¥([\d.]+)/)?.[1]
    const income = remark.match(/实际收入:¥([\d.]+)/)?.[1]
    if (orderAmount) return { orderAmount, commissionRate, commissionAmount, income }
  } catch (e) {}
  return null
}

</script>
<style lang="scss" scoped>
.detail-page { background: #f7f8fa; min-height: var(--app-page-height, 100vh); }
.list { height: var(--app-page-height, 100vh); box-sizing: border-box; padding-bottom: var(--app-safe-bottom, env(safe-area-inset-bottom)); }
.record-item { display: flex; justify-content: space-between; align-items: center; gap: 20rpx; margin: 20rpx 24rpx 0; padding: 24rpx; background: #fff; border: 1rpx solid #edf0f2; border-radius: 20rpx;
  .left { flex: 1; .desc { font-size: 28rpx; color: #17191c; display: block; } .time { font-size: 22rpx; color: #73777e; display: block; margin-top: 4rpx; }
    .remark-info { margin-top: 8rpx; text { display: block; font-size: 22rpx; color: #666b73; line-height: 1.6; } }
  }
  .amount { font-size: 30rpx; color: #17191c; font-weight: bold; &.income { color: #347a5b; } }
}
.loading-tip { text-align: center; padding: 32rpx; font-size: 24rpx; color: #73777e; }
</style>
