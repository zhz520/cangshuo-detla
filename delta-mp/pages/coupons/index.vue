<template>
  <view class="coupon-page">
    <view class="tabs">
      <view class="tab" :class="{ active: tab === 'available' }" @click="tab = 'available'">可领取</view>
      <view class="tab" :class="{ active: tab === 'mine' }" @click="tab = 'mine'">我的优惠券</view>
    </view>
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!items.length" class="empty">{{ tab === 'available' ? '暂无可领取优惠券' : '暂无优惠券' }}</view>
    <view v-else class="list">
      <view v-for="item in items" :key="item.id" class="coupon-card">
        <view class="value">
          <text v-if="item.type === 'DISCOUNT'">{{ (Number(item.coupon_value || item.value) * 10).toFixed(1).replace('.0','') }}折</text>
          <text v-else>¥{{ Number(item.coupon_value || item.value || 0).toFixed(2) }}</text>
        </view>
        <view class="info">
          <text class="name">{{ item.name }}</text>
          <text class="meta">满 {{ Number(item.min_spend || 0).toFixed(2) }} 可用</text>
          <text v-if="item.status" class="status">{{ statusText(item.status) }}</text>
        </view>
        <button v-if="tab === 'available'" class="claim" :disabled="claiming === item.id" @click="claim(item)">{{ claiming === item.id ? '领取中' : '领取' }}</button>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { claimCoupon, getAvailableCoupons, getMyCoupons } from '@/api/coupon'

const tab = ref('available')
const available = ref([])
const mine = ref([])
const loading = ref(false)
const claiming = ref('')
const items = computed(() => tab.value === 'available' ? available.value : mine.value)

function statusText(status) {
  return ({ AVAILABLE: '可使用', LOCKED: '待支付', USED: '已使用', EXPIRED: '已过期' })[status] || status
}

async function load() {
  loading.value = true
  try {
    const [a, m] = await Promise.all([getAvailableCoupons(), getMyCoupons()])
    available.value = Array.isArray(a.data) ? a.data : []
    mine.value = Array.isArray(m.data) ? m.data : []
  } catch (_) {
    available.value = []
    mine.value = []
  } finally {
    loading.value = false
  }
}

async function claim(item) {
  if (claiming.value) return
  claiming.value = item.id
  try {
    await claimCoupon(item.id)
    uni.showToast({ title: '领取成功', icon: 'none' })
    await load()
  } catch (_) {
  } finally {
    claiming.value = ''
  }
}

onMounted(load)
</script>

<style lang="scss" scoped>
.coupon-page { min-height: var(--app-page-height, 100vh); padding: 28rpx; background: var(--store-soft); box-sizing: border-box; }
.tabs { display: flex; padding: 8rpx; border-radius: 16rpx; background: var(--store-soft); margin-bottom: 24rpx; }
.tab { flex: 1; padding: 20rpx; text-align: center; border-radius: 12rpx; color: var(--store-regular); font-size: 26rpx; }
.tab.active { background: var(--store-surface); color: var(--store-text); font-weight: 700; }
.list { display: grid; gap: 16rpx; }
.coupon-card { display: flex; align-items: center; gap: 20rpx; padding: 26rpx; border-radius: 18rpx; background: var(--store-surface); }
.value { width: 150rpx; text-align: center; font-size: 36rpx; font-weight: 700; color: var(--store-text); }
.info { flex: 1; min-width: 0; display: grid; gap: 6rpx; }
.name { font-size: 27rpx; color: var(--store-text); font-weight: 600; }
.meta, .status { font-size: 23rpx; color: var(--store-regular); }
.claim { width: 120rpx; margin: 0; padding: 0; height: 64rpx; line-height: 64rpx; border-radius: 32rpx; background: var(--store-primary); color: #fff; font-size: 24rpx; }
.empty { padding: 150rpx 0; text-align: center; color: var(--store-regular); font-size: 25rpx; }
</style>
