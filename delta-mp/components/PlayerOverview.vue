<template>
  <view class="player-overview">
    <view class="overview-header">
      <AppImage class="overview-avatar" :src="profile?.avatar || '/static/images/delta-default-avatar.png'" :width="160" eager />
      <view class="overview-identity"><text class="overview-eyebrow">接单员中心</text><text class="overview-name">{{ profile?.nickname || '接单员' }}</text></view>
      <text class="overview-tag">{{ profile?.levelName || '我的工作台' }}</text>
    </view>
    <view class="overview-income" @click="$emit('navigate', '/pages-player/earnings/index')">
      <view class="income-heading"><text>累计收入（元）</text><text class="income-link">收益明细 ›</text></view>
      <text class="income-number">{{ money(data.wallet?.totalIncome) }}</text>
      <view class="income-stats"><view><text class="stat-value">{{ data.todayCompleted || 0 }}</text><text class="stat-caption">今日完成</text></view><view><text class="stat-value">¥{{ money(data.todayIncome) }}</text><text class="stat-caption">今日收入</text></view><view><text class="stat-value">¥{{ money(data.wallet?.balance) }}</text><text class="stat-caption">可用余额</text></view></view>
    </view>
    <view class="overview-shortcuts">
      <view v-for="item in shortcuts" :key="item.path" class="action" @click="$emit('navigate', item.path)"><view class="shortcut-icon"><image :src="item.icon" mode="aspectFit" /></view><text>{{ item.label }}</text></view>
    </view>
  </view>
</template>
<script setup>
import AppImage from './AppImage.vue'
defineProps({ profile: Object, data: { type: Object, default: () => ({}) } })
defineEmits(['navigate'])
const shortcuts = [
  { path: '/pages-player/hall/index', icon: '/static/icons/店铺.svg', label: '接单大厅' },
  { path: '/pages-player/order/list', icon: '/static/icons/订单.svg', label: '我的订单' },
  { path: '/pages-player/earnings/index', icon: '/static/icons/钞票.svg', label: '我的收益' },
  { path: '/pages-player/withdraw/index', icon: '/static/icons/理财.svg', label: '申请提现' }
]
function money(value) { const number = Number(value); return Number.isFinite(number) ? number.toFixed(2) : '0.00' }
</script>
<style scoped>
.player-overview { padding: 32rpx 28rpx 0; }
.overview-header { display: flex; align-items: center; gap: 20rpx; padding: 8rpx 0 32rpx; }
.overview-avatar { width: 104rpx; height: 104rpx; border-radius: 32rpx; flex-shrink: 0; border: 4rpx solid #fff; }
.overview-identity { display: flex; flex-direction: column; gap: 6rpx; min-width: 0; flex: 1; }
.overview-eyebrow { font-size: 22rpx; color: var(--store-regular); letter-spacing: 2rpx; }
.overview-name { font-size: 36rpx; font-weight: 700; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.overview-tag { font-size: 21rpx; color: var(--store-regular); background: var(--store-soft); padding: 9rpx 15rpx; border-radius: 999rpx; max-width: 190rpx; }
.overview-income { background: #202328; color: #fff; border-radius: 28rpx; padding: 30rpx; box-shadow: 0 12rpx 28rpx rgba(23,25,28,.09); }
.income-heading { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; color: #cbd0d7; font-size: 24rpx; }
.income-link { font-size: 22rpx; }
.income-number { display: block; font-size: 64rpx; font-weight: 700; margin: 14rpx 0 28rpx; font-variant-numeric: tabular-nums; }
.income-stats { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); border-top: 1rpx solid rgba(255,255,255,.13); padding-top: 24rpx; gap: 12rpx; }
.stat-value { display: block; font-size: 29rpx; font-weight: 600; overflow-wrap: anywhere; }
.stat-caption { display: block; font-size: 21rpx; color: #b5bdc8; margin-top: 8rpx; }
.overview-shortcuts { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 12rpx; background: var(--store-surface); border: 1rpx solid var(--store-line); border-radius: 24rpx; margin-top: 24rpx; padding: 22rpx 8rpx; }
.action { display: flex; flex-direction: column; align-items: center; gap: 12rpx; font-size: 23rpx; color: var(--store-text); padding: 8rpx 0; }
.shortcut-icon { width: 72rpx; height: 72rpx; display: flex; align-items: center; justify-content: center; background: var(--store-soft); border-radius: 22rpx; }
.shortcut-icon image { width: 40rpx; height: 40rpx; filter: grayscale(1) brightness(.3); }
</style>
