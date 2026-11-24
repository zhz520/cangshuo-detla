<template>
  <view class="hall-page tab-page">
    <!-- 分类筛选 -->
    <scroll-view scroll-x class="filter-bar">
      <view class="filter-item" :class="{active: !categoryId}" @click="filterCategory('')">全部</view>
      <view v-for="c in categories" :key="c.id" class="filter-item" :class="{active: categoryId===c.id}" @click="filterCategory(c.id)">{{ c.name }}</view>
    </scroll-view>
    <scroll-view data-delta-content="tab" :key="categoryId" scroll-y class="list" :show-scrollbar="false" @scrolltolower="loadMore">
      <PageState v-if="!orders.length" :phase="phase" :loading="loading" :error="error" :empty="!loading && !error" empty-text="暂无可接订单" @retry="retry" />
      <view v-for="o in orders" :key="o.id" class="order-card-wrap">
        <OrderCard :order="o" detail-path="/pages-player/order/detail" embed />
        <view class="accept-bar">
          <view class="amount-block">
            <text class="income-line">到手金额：¥{{ incomeAmount(o).toFixed(2) }}</text>
            <text class="commission-hint">抽佣比例 {{ commissionPercent(o) }}%</text>
          </view>
          <view class="btn-accept" @click.stop="doAccept(o.id)">立即接单</view>
        </view>
      </view>
      <template v-if="orders.length">
        <PageState v-if="error" :error="error" @retry="retry" />
        <LoadFeedback v-else-if="loading" :phase="phase" />
        <view v-else-if="finished" class="loading-tip">已全部加载</view>
        <view v-else class="loading-tip" @click="loadMore">加载更多</view>
      </template>
    </scroll-view>
<GrabAcceptDialog ref="grabDialog" @accepted="refreshAfterAccept" />
<CustomTabBar :current="0" />
  </view>
</template>
<script setup>
import LoadFeedback from '@/components/LoadFeedback.vue'
import { ref } from 'vue'
import { onShow, onHide, onUnload, onPullDownRefresh } from '@dcloudio/uni-app'
import CustomTabBar from '@/components/CustomTabBar.vue'
import OrderCard from '@/components/OrderCard.vue'
import PageState from '@/components/PageState.vue'
import { usePagedList } from '@/composables/usePagedList'
import { getOrderHall } from '@/api/player'
import GrabAcceptDialog from '@/components/GrabAcceptDialog.vue'
const grabDialog=ref(null)
import { getAllCategories } from '@/api/category'
import { useRemindStore } from '@/store/remind'

const remindStore = useRemindStore()
/** 仅主分类，用于顶部 tab 展示 */
const categories = ref([])
/** 全量分类（含子分类），用于计算主分类下的子分类 id 列表 */
const allCategories = ref([])
let categoriesLoaded = false
let categoryRequest = null
let disposed = false
const {
  query: categoryId, items: orders, loading, phase, error, finished,
  refresh, loadMore, retry, setQuery: filterCategory, cancel, dispose
} = usePagedList({
  async fetchPage({ pageNum, pageSize, query, isCurrent }) {
    await loadCategories()
    if (!isCurrent()) return
    const params = { pageNum, pageSize }
    if (query) params.categoryIds = getCategoryIdsUnderMain(query).join(',')
    const res = await getOrderHall(params)
    return res.data
  }
})

function loadCategories() {
  if (categoriesLoaded) return Promise.resolve()
  if (categoryRequest) return categoryRequest
  categoryRequest = getAllCategories({ loading: false }).then(res => {
    if (disposed) return
    const all = res.data || []
    allCategories.value = all
    categories.value = all.filter(c => !c.parentId || c.parentId === 0 || c.parentId === '')
    categoriesLoaded = true
  }).finally(() => { categoryRequest = null })
  return categoryRequest
}

/** 获取某主分类及其所有子分类的 id 列表（含多级） */
function getCategoryIdsUnderMain(mainId) {
  const flat = allCategories.value
  const ids = [mainId]
  function collect(pid) {
    flat.filter(c => c.parentId == pid).forEach(c => {
      ids.push(c.id)
      collect(c.id)
    })
  }
  collect(mainId)
  return ids
}

onShow(() => {
  try { uni.hideHomeButton() } catch (_) {}
  remindStore.fetchPlayerRemind()
  refresh()
})

onHide(cancel)
onUnload(() => { disposed = true; dispose() })

onPullDownRefresh(async () => {
  try { await refresh() } finally { uni.stopPullDownRefresh() }
})

// Acceptance changes availability even if an earlier refresh is still in flight.
function refreshAfterAccept() { return refresh({ force: true }) }

/** 订单抽佣比例（0~1），仅用订单字段，无则按 0 */
function commissionRateForOrder(order) {
  if (order != null && (order.commissionRate === 0 || (order.commissionRate != null && order.commissionRate !== ''))) {
    return Number(order.commissionRate)
  }
  return 0
}
function commissionPercent(order) {
  return Math.round(commissionRateForOrder(order) * 100)
}
function incomeAmount(order) {
  const n = Number(order?.amount) || 0
  return n * (1 - commissionRateForOrder(order))
}
function doAccept(id) { grabDialog.value?.open(id) }
</script>
<style lang="scss" scoped>
.hall-page { background: var(--store-surface); position: relative; }
.filter-bar { white-space: nowrap; padding: 16rpx 24rpx; background: var(--store-soft); border-bottom: 1rpx solid #f7f7f7; }
.filter-item { display: inline-block; padding: 12rpx 24rpx; margin-right: 16rpx; font-size: 26rpx; color: var(--store-regular); background: var(--store-soft); border-radius: 999rpx; &.active { background: var(--store-primary); color: #ffffff; font-weight:bold; } }
.list { height: calc(var(--app-page-height, 100vh) - 190rpx - var(--app-safe-bottom, env(safe-area-inset-bottom))); padding: 20rpx 24rpx; overflow-x: hidden; box-sizing: border-box; }
.order-card-wrap { margin-bottom: 20rpx; overflow: hidden; border-radius: 16rpx; background: var(--store-soft); border: 1rpx solid #aaaaaa; }
.accept-bar { display: flex; justify-content: space-between; align-items: center; padding: 16rpx 24rpx; background: var(--store-soft); border-radius: 0 0 16rpx 16rpx; width: 100%; box-sizing: border-box; }
.amount-block { display: flex; flex-direction: column; gap: 4rpx; }
.order-amount { font-size: 28rpx; color: var(--store-text); font-weight: 600; display: block; }
.income-line { font-size: 28rpx; color: #d44955; font-weight: bold; display: block; }
.commission-hint { font-size: 22rpx; color: var(--store-regular); display: block; }
.btn-accept { padding: 12rpx 32rpx; background: var(--store-primary); color: #ffffff; font-weight:bold; font-size: 26rpx; border-radius: 999rpx; flex-shrink: 0; }
.loading-tip { text-align: center; padding: 32rpx; font-size: 24rpx; color: var(--store-regular); }
</style>
