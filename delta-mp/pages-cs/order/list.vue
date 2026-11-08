<template>
  <view class="cs-order-list tab-page">
    <!-- 搜索栏 -->
    <view class="search-bar">
      <input v-model="keyword" placeholder="搜索订单号/用户" class="search-input" @confirm="refresh" />
    </view>
    <!-- 状态筛选 -->
    <scroll-view scroll-x class="filter-bar">
      <view class="filter-item" :class="{active: !statusFilter}" @click="filterStatus('')">全部</view>
      <view v-for="s in statusOptions" :key="s.value" class="filter-item" :class="{active: statusFilter===s.value}" @click="filterStatus(s.value)">{{ s.label }}</view>
    </scroll-view>
    <view v-if="statusFilter==='PAID'" class="priority-tip">
      <text>待指派订单优先展示</text>
      <text>点击“指派接单员”直接选择在线打手</text>
    </view>
    <scroll-view scroll-y class="list" :show-scrollbar="false" @scrolltolower="loadMore">
      <view v-if="error" class="loading-tip error" @click="refresh">{{ error }} · 点击重试</view>
      <view v-for="o in orders" :key="o.id" class="order-card" @click="goDetail(o.id)">
        <view class="card-top">
          <text class="order-no">{{ o.orderNo }}</text>
          <StatusTag :status="o.status" />
        </view>
        <view class="card-body">
          <text class="product-name">{{ o.productName }}</text>
          <view class="info-row">
            <text class="user">用户：{{ displayUserName(o) }}</text>
            <text class="amount">￥{{ Number(o.amount).toFixed(2) }}</text>
          </view>
          <view v-if="o.userPhone||o.user_phone" class="phone-row" @click.stop="callUser(o)">
            <text>客户电话：{{ o.userPhone || o.user_phone }}</text>
            <text>拨打 ›</text>
          </view>
          <text class="time">{{ o.createdAt }}</text>
        </view>
        <view v-if="o.status==='PAID' || o.status==='ASSIGNED'" class="card-actions">
          <view class="btn-detail" @click.stop="goDetail(o.id)">查看详情</view>
          <view class="btn-assign" @click.stop="goAssign(o.id)">指派接单员</view>
        </view>
      </view>
      <view v-if="loading" class="loading-tip">加载中...</view>
<EmptyState v-if="!loading && orders.length===0" text="暂无订单" image="/static/icons/暂无纪录.svg" />
    </scroll-view>
    <CustomTabBar :current="1" />
  </view>
</template>
<script setup>
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import StatusTag from '@/components/StatusTag.vue'
import EmptyState from '@/components/EmptyState.vue'
import CustomTabBar from '@/components/CustomTabBar.vue'
import { getCsOrderList } from '@/pages-cs/api/cs'

const statusOptions = [
  { value: 'PENDING_PAYMENT', label: '待支付' }, { value: 'PAID', label: '待接单' },
  { value: 'ASSIGNED', label: '已指派' }, { value: 'ACCEPTED', label: '已接单' },
  { value: 'IN_PROGRESS', label: '进行中' }, { value: 'COMPLETED', label: '待确认' },
  { value: 'CONFIRMED', label: '已完成' }, { value: 'CANCELLED', label: '已取消' },
  { value: 'REFUNDING', label: '退款中' }, { value: 'REFUNDED', label: '已退款' },
  { value: 'DISPUTED', label: '争议中' }
]
const keyword = ref('')
const statusFilter = ref('')
const orders = ref([])
const pageNum = ref(1)
const loading = ref(false)
const finished = ref(false)
const error = ref('')
let loadVersion = 0

onLoad(options => {
  const requested = String(options?.status || '').toUpperCase()
  if (statusOptions.some(item => item.value === requested)) statusFilter.value = requested
})
onShow(() => { refresh() })

function filterStatus(s) { statusFilter.value = s; refresh() }
function refresh() { pageNum.value = 1; orders.value = []; finished.value = false; loadData() }
async function loadData() {
  const version = ++loadVersion
  loading.value = true
  error.value = ''
  const params = { pageNum: pageNum.value, pageSize: 20 }
  if (keyword.value) params.keyword = keyword.value
  if (statusFilter.value) params.status = statusFilter.value
  try {
    const res = await getCsOrderList(params)
    if (version !== loadVersion) return
    const list = res.data?.records || []
    finished.value = list.length < 20
    orders.value = pageNum.value === 1 ? list : [...orders.value, ...list]
  } catch (e) {
    if (version === loadVersion) error.value = e?.msg || e?.message || '订单列表加载失败'
  } finally {
    if (version === loadVersion) loading.value = false
  }
}
function loadMore() { if (!loading.value && !finished.value) { pageNum.value++; loadData() } }
function displayUserName(o) {
  const name = o.userNickname ?? o.user_nickname
  if (name != null && String(name).trim() !== '') return name
  return 'ID: ' + (o.userId ?? '')
}
function goDetail(id) { uni.navigateTo({ url: '/pages-cs/order/detail?id=' + id }) }
function goAssign(id) { uni.navigateTo({ url: '/pages-cs/order/assign?orderId=' + id }) }
function callUser(order) {
  const phone = order.userPhone || order.user_phone
  if (!phone) return
  uni.makePhoneCall({
    phoneNumber: String(phone),
    fail: () => uni.setClipboardData({ data: String(phone), success: () => uni.showToast({ title: '号码已复制', icon: 'none' }) })
  })
}
</script>
<style lang="scss" scoped>
.cs-order-list { background: #f3f4f6; width: 100%; overflow: hidden; box-sizing: border-box; position: relative; }
.search-bar { padding: 16rpx 24rpx; background: rgba(0,0,0,0.04); }
.search-input { height: 72rpx; line-height: 72rpx; box-sizing: border-box; background: rgba(0,0,0,0.05); padding: 0 24rpx; border-radius: 999rpx; font-size: 26rpx; color: rgba(0,0,0,0.85); }
.filter-bar { white-space: nowrap; padding: 12rpx 24rpx; background: rgba(0,0,0,0.04); border-bottom: 1rpx solid rgba(0,0,0,0.04); }
.filter-item { display: inline-block; padding: 10rpx 20rpx; margin-right: 12rpx; font-size: 24rpx; color: rgba(0,0,0,0.55); background: rgba(0,0,0,0.05); border-radius: 999rpx; &.active { background: linear-gradient(135deg, #17191c, #30343a); color: #ffffff; font-weight:bold; } }
.priority-tip { display:flex; justify-content:space-between; gap:12rpx; padding:14rpx 24rpx; background:#fff; border-bottom:1rpx solid #eef0f2; color:#81878f; font-size:20rpx; }
.priority-tip text:first-child { color:#30353b; font-weight:700; }
.list { height: calc(var(--app-page-height, 100vh) - 330rpx - var(--app-safe-bottom, env(safe-area-inset-bottom))); padding: 20rpx 24rpx; box-sizing: border-box; overflow-x: hidden; }
.order-card { background: #fff; border: 1rpx solid #e8ebee; border-radius: 20rpx; padding: 24rpx; margin-bottom: 16rpx; box-shadow:0 7rpx 22rpx rgba(23,25,28,.025);
  .card-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12rpx; .order-no { font-size: 23rpx; color:#8b9198; } }
  .card-body { .product-name { font-size: 28rpx; font-weight: bold; display: block; margin-bottom: 8rpx; color: rgba(0,0,0,0.85); } .info-row { display: flex; justify-content: space-between; .user { font-size: 24rpx; color: rgba(0,0,0,0.55); } .amount { font-size: 28rpx; color: #17191c; font-weight: bold; } } .time { font-size: 22rpx; color: rgba(0,0,0,0.3); display: block; margin-top: 4rpx; } }
  .phone-row { display:flex; justify-content:space-between; margin-top:10rpx; color:#59616a; font-size:23rpx; }
  .phone-row text:last-child { color:#32704e; font-weight:600; }
  .card-actions { display: flex; justify-content: flex-end; gap:12rpx; padding-top: 16rpx; margin-top: 12rpx; border-top: 1rpx solid #eef0f2; }
  .btn-detail { padding: 10rpx 24rpx; border:1rpx solid #e1e4e7; color:#5f666e; font-size:23rpx; border-radius:999rpx; }
  .btn-assign { padding: 10rpx 28rpx; background: linear-gradient(135deg, #17191c, #30343a); color: #ffffff; font-weight:bold; font-size: 24rpx; border-radius: 999rpx; }
}
.loading-tip { text-align: center; padding: 32rpx; font-size: 24rpx; color: rgba(0,0,0,0.3); }
.loading-tip.error { color:#a34850; }
</style>
