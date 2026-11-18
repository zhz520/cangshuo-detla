<template>
  <view class="player-list-card" :class="{ compact }" @click="open">
    <view class="avatar-wrap">
      <AppImage :width="320" :src="player.avatar || '/static/images/delta-default-avatar.png'" class="avatar" mode="aspectFill" />
      <view class="presence-dot" :class="{ online }" />
    </view>
    <view class="content">
      <view class="title-row">
        <text class="name">{{ player.nickname || '打手' }}</text>
        <text v-if="player.levelName" class="level">{{ player.levelName }}</text>
      </view>
      <view class="metrics">
        <text class="rating">★ {{ Number(player.avgRating || 0).toFixed(1) }}</text>
        <text>·</text>
        <text>{{ player.orderCount || 0 }} 单</text>
        <text v-if="player.gameLevel">· {{ player.gameLevel }}</text>
      </view>
      <view v-if="tags.length" class="tags">
        <text v-for="tag in tags" :key="tag">{{ tag }}</text>
      </view>
    </view>
    <view class="action-col">
      <text class="online-text" :class="{ online }" :aria-label="online ? '在线接单' : '暂时离线'">{{ online ? '在线' : '离线' }}</text>
      <view v-if="siteStore.orderPurchaseEnabled" class="book-btn" :class="{ disabled: !online }" :aria-label="online ? '立即点单' : '暂时离线'" @click.stop="placeOrder">
        {{ online ? '预约' : '离线' }}
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import AppImage from '@/components/AppImage.vue'
import { useSiteStore } from '@/store/site'
const siteStore = useSiteStore()

const props = defineProps({
  player: { type: Object, required: true },
  compact: { type: Boolean, default: false }
})
const online = computed(() => props.player.isOnline === 1)
const tags = computed(() => {
  const values = [props.player.skillTags, props.player.serviceTypes]
    .filter(Boolean)
    .flatMap(value => String(value).split(/[,，、|]/))
    .map(value => value.trim())
    .filter(Boolean)
  if (props.player.voiceUrl) values.push('语音介绍')
  return [...new Set(values)].slice(0, props.compact ? 2 : 3)
})
function open() {
  uni.navigateTo({ url: '/pages/players/detail?id=' + props.player.id })
}
function placeOrder() {
  if (!siteStore.orderPurchaseEnabled) return uni.showToast({ title: '购买功能已暂停', icon: 'none' })
  if (!online.value) return uni.showToast({ title: '该打手当前离线', icon: 'none' })
  uni.navigateTo({ url: '/pages/product/list?playerId=' + props.player.id })
}
</script>

<style lang="scss" scoped>
.player-list-card {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 22rpx;
  border: 0;
  border-radius: 24rpx;
  background: var(--store-surface);
  box-shadow: none;
  box-sizing: border-box;
}
.avatar-wrap {
  position: relative;
  width: 96rpx;
  height: 96rpx;
  flex-shrink: 0;
}
.avatar {
  width: 96rpx;
  height: 96rpx;
  border-radius: 50%;
  background: var(--store-line);
}
.presence-dot {
  position: absolute;
  right: 2rpx;
  bottom: 4rpx;
  width: 18rpx;
  height: 18rpx;
  border: 4rpx solid #ffffff;
  border-radius: 50%;
  background: #b4b8be;
  box-sizing: content-box;
  &.online { background: #2d9a67; }
}
.content {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 7rpx;
}
.title-row {
  display: flex;
  align-items: center;
  gap: 10rpx;
  min-width: 0;
}
.name {
  min-width: 0;
  overflow: hidden;
  color: var(--store-text);
  font-size: 25rpx;
  font-weight: 800;
  line-height: 1.25;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.level {
  flex-shrink: 0;
  padding: 3rpx 9rpx;
  border-radius: 999rpx;
  background: var(--store-soft);
  color: var(--store-regular);
  font-size: 16rpx;
  line-height: 1.4;
}
.metrics {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8rpx;
  color: #8a8f96;
  font-size: 19rpx;
  line-height: 1.35;
}
.rating { color: var(--store-regular); font-weight: 700; }
.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 7rpx;
  text {
    padding: 4rpx 10rpx;
    border-radius: 999rpx;
    background: var(--store-soft);
    color: var(--store-regular);
    font-size: 16rpx;
    line-height: 1.35;
  }
}
.action-col {
  width: 84rpx;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8rpx;
}
.online-text {
  color: #a6abb2;
  font-size: 16rpx;
  &.online { color: #2d9a67; }
}
.book-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 82rpx;
  height: 44rpx;
  border-radius: 999rpx;
  background: var(--store-primary);
  color: #ffffff;
  font-size: 19rpx;
  font-weight: 750;
  &.disabled { background: #d7dade; color: #ffffff; }
}
.compact {
  gap: 14rpx;
  padding: 14rpx;
  border-radius: 18rpx;
}
.compact .avatar-wrap,
.compact .avatar { width: 84rpx; height: 84rpx; }
.compact .name { font-size: 25rpx; }
.compact .tags { display: none; }
</style>
