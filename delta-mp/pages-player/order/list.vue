<template>
  <view class="player-order-list tab-page">
    <scroll-view scroll-x class="tabs" :show-scrollbar="false">
      <view class="tabs-inner">
        <view v-for="tab in tabs" :key="tab.value" class="tab" :class="{active:currentTab===tab.value}" @click="switchTab(tab.value)">{{tab.label}}</view>
      </view>
    </scroll-view>
    <scroll-view :key="currentTab" scroll-y class="list" :show-scrollbar="false" @scrolltolower="loadMore">
      <PageState v-if="!orders.length" :loading="loading" :error="error" :empty="!loading && !error" empty-text="暂无订单" @retry="retry" />
      <OrderCard v-for="o in orders" :key="o.id" :order="o" detail-path="/pages-player/order/detail" />
      <template v-if="orders.length">
        <PageState v-if="error" :error="error" @retry="retry" />
        <view v-else-if="loading" class="loading-tip">加载中...</view>
        <view v-else-if="finished" class="loading-tip">已全部加载</view>
        <view v-else class="loading-tip" @click="loadMore">加载更多</view>
      </template>
    </scroll-view>
<CustomTabBar :current="1" />
  </view>
</template>
<script setup>
import { onShow, onHide, onUnload, onPullDownRefresh } from '@dcloudio/uni-app'
import CustomTabBar from '@/components/CustomTabBar.vue'
import OrderCard from '@/components/OrderCard.vue'
import PageState from '@/components/PageState.vue'
import { usePagedList } from '@/composables/usePagedList'
import { getMyWork } from '@/api/player'
import { PLAYER_ORDER_TABS } from '@/utils/constants'
const tabs = PLAYER_ORDER_TABS
const {
  query: currentTab, items: orders, loading, error, finished,
  refresh, loadMore, retry, setQuery: switchTab, cancel, dispose
} = usePagedList({
  async fetchPage({ pageNum, pageSize, query }) {
    const res = await getMyWork({ pageNum, pageSize, status: query })
    return res.data
  }
})
onShow(() => {
  try { uni.hideHomeButton() } catch (_) {}
  refresh()
})
onHide(cancel)
onUnload(dispose)
onPullDownRefresh(async () => {
  try { await refresh() } finally { uni.stopPullDownRefresh() }
})
</script>
<style lang="scss" scoped>
.player-order-list { width: 100%; overflow: hidden; box-sizing: border-box; background: var(--store-surface); position: relative; }
.tabs { background: var(--store-soft); border-bottom:1rpx solid #f7f7f7; white-space:nowrap; }
.tabs-inner { display:inline-flex; padding:0 12rpx; }
.tab { padding:24rpx 24rpx; font-size:26rpx; color: var(--store-regular); flex-shrink:0; &.active { color: var(--store-text); border-bottom:4rpx solid var(--store-line); } }
.list { height: calc(var(--app-page-height, 100vh) - 190rpx - var(--app-safe-bottom, env(safe-area-inset-bottom))); padding: 20rpx 24rpx; box-sizing: border-box; overflow-x: hidden; }
.loading-tip { text-align: center; padding: 32rpx; font-size: 24rpx; color: var(--store-regular); }
</style>
