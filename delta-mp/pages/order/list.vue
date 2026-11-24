<template>
  <view class="order-list-page">
    <view class="order-filter-bar">
      <view
        v-for="tab in tabs"
        :key="tab.key"
        class="order-filter"
        :class="{ active: activeTab === tab.key }"
        @click="selectTab(tab.key)"
      >{{ tab.label }}</view>
    </view>
    <scroll-view data-delta-content="page" scroll-y class="list" :show-scrollbar="false" @scrolltolower="loadMore">
      <OrderCard v-for="o in visibleOrders" :key="o.id" :order="o" :owner-actions="canUseOwnerActions(o)" />
      <LoadFeedback v-if="loading" :phase="!orders.length ? 'initial' : pageNum > 1 ? 'more' : 'refresh'" />
      <view v-if="error && !loading" class="loading-tip error-tip" @click="loadData">{{ error }} · 点击重试</view>
      <EmptyState v-if="!error && !loading && visibleOrders.length===0" text="暂无订单" image="/static/icons/暂无纪录.svg" />
      <view class="order-list-bottom-space"></view>
    </scroll-view>
  </view>
</template>
<script setup>
import LoadFeedback from '@/components/LoadFeedback.vue'
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import OrderCard from '@/components/OrderCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { getOrderList } from '@/api/order'
import { useUserStore } from '@/store/user'
const userStore = useUserStore()
const orders = ref([])
const pageNum = ref(1)
const loading = ref(false)
const finished = ref(false)
const error = ref('')
const activeTab = ref('all')
const tabs = [
  { key: 'all', label: '全部' },
  { key: 'pending', label: '待支付' },
  { key: 'active', label: '进行中' },
  { key: 'completed', label: '已完成' },
  { key: 'aftersale', label: '售后' }
]
const statusGroups = {
  pending: ['PENDING_PAYMENT'],
  active: ['PAID', 'ASSIGNED', 'ACCEPTED', 'WAITING_TEAMMATE', 'IN_PROGRESS'],
  completed: ['COMPLETED', 'CONFIRMED', 'REVIEWED'],
  aftersale: ['DISPUTED', 'ARBITRATED', 'REFUNDED', 'CANCELLED']
}
const visibleOrders = computed(() => {
  if (activeTab.value === 'all') return orders.value
  const allowed = statusGroups[activeTab.value] || []
  return orders.value.filter(order => allowed.includes(order.status))
})
onLoad(options => {
  if (tabs.some(tab => tab.key === options.tab)) activeTab.value = options.tab
})
onShow(() => {
  userStore.syncSessionFromStorage?.()
  if (!userStore.isLoggedIn && !userStore.isGuest) {
    uni.redirectTo({ url: '/pages/login/index?redirect=' + encodeURIComponent('/pages/order/list') })
    return
  }
  refresh()
})

function refresh() { pageNum.value = 1; finished.value = false; loadData() }
function canUseOwnerActions(order) {
  return userStore.isLoggedIn && String(order.userId || '') === String(userStore.userInfo?.id || '')
}
async function loadData() {
  loading.value = true
  error.value = ''
  try {
    const res = await getOrderList({ pageNum: pageNum.value, pageSize: 50 }, { loading: false })
    const list = res.data?.records || []
    if (list.length < 50) finished.value = true
    orders.value = pageNum.value === 1 ? list : [...orders.value, ...list]
  } catch (e) {
    error.value = e?.msg || e?.message || '订单暂时无法加载'
  } finally {
    loading.value = false
  }
}
function loadMore() { if (!loading.value && !finished.value) { pageNum.value++; loadData() } }
function selectTab(key) {
  activeTab.value = key
  if (!orders.value.length && !loading.value) refresh()
}
</script>
<style lang="scss" scoped>
.order-list-page {
  background: var(--store-soft);
  height: var(--app-page-height, 100vh);
  position: relative;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.order-filter-bar {
  flex: 0 0 auto;
  display: flex;
  gap: 8rpx;
  padding: 16rpx 24rpx 10rpx;
  overflow-x: auto;
}
.order-filter {
  flex-shrink: 0;
  padding: 10rpx 20rpx;
  border: 1rpx solid var(--store-line);
  border-radius: 999rpx;
  background: var(--store-surface);
  color: #777c83;
  font-size: 21rpx;
  &.active { border-color: var(--store-text); background: var(--store-primary); color: #ffffff; font-weight: 750; }
}
.list {
  flex: 1;
  height: 0;
  min-height: 0;
  padding: 8rpx 24rpx 20rpx;
  position: relative;
  z-index: 1;
  box-sizing: border-box;
}
.list :deep(.order-card) {
  background: var(--store-surface);
  border: 1rpx solid var(--store-line);
}
.loading-tip {
  text-align: center;
  padding: 32rpx;
  font-size: 24rpx;
  color: var(--store-regular);
}
.error-tip { color: #c94f59; cursor: pointer; }
.order-list-bottom-space {
  height: calc(140rpx + var(--app-safe-bottom, env(safe-area-inset-bottom)));
}
</style>
