<template>
  <view class="player-list-card" :class="{ compact }" @click="open">
    <view class="media">
      <AppImage :width="320" :src="player.coverImage || player.avatar || '/static/images/default-avatar.png'" mode="aspectFill" />
      <text class="presence" :class="{ online: online }">{{ online ? '在线接单' : '暂时离线' }}</text>
      <text v-if="player.voiceUrl" class="voice">语音 {{ Number(player.voiceDuration || 0) }}″</text>
    </view>
    <view class="content">
      <view class="title-row"><text class="name">{{ player.nickname || '打手' }}</text><text class="level">{{ player.levelName || `Lv.${player.level || 1}` }}</text></view>
      <view class="metrics"><text>★ {{ Number(player.avgRating || 0).toFixed(1) }}</text><text>{{ player.orderCount || 0 }} 单</text><text v-if="player.gameLevel">{{ player.gameLevel }}</text></view>
      <text v-if="!compact && player.introduction" class="introduction">{{ player.introduction }}</text>
      <view v-if="tags.length" class="tags"><text v-for="tag in tags" :key="tag">{{ tag }}</text></view>
      <view class="actions">
        <view class="action secondary" @click.stop="open">查看主页</view>
        <view class="action primary" :class="{ disabled: !online }" @click.stop="placeOrder">{{ online ? '立即点单' : '离线' }}</view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed } from 'vue'
import AppImage from '@/components/AppImage.vue'

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
  return [...new Set(values)].slice(0, props.compact ? 2 : 4)
})
function open() {
  uni.navigateTo({ url: `/pages/players/detail?id=${props.player.id}` })
}
function placeOrder() {
  if (!online.value) return uni.showToast({ title: '该打手当前离线', icon: 'none' })
  uni.navigateTo({ url: `/pages/product/list?playerId=${props.player.id}` })
}
</script>

<style lang="scss" scoped>
.player-list-card{display:grid;grid-template-columns:210rpx minmax(0,1fr);gap:22rpx;padding:22rpx;border:1rpx solid #eceef1;border-radius:22rpx;background:#fff;box-shadow:0 8rpx 24rpx rgba(23,25,28,.045);box-sizing:border-box}
.media{position:relative;height:210rpx;overflow:hidden;border-radius:18rpx;background:#f1f2f4}.media :deep(.app-image){width:100%;height:100%}.presence{position:absolute;left:10rpx;bottom:10rpx;padding:5rpx 12rpx;border-radius:999rpx;background:rgba(23,25,28,.62);color:#fff;font-size:19rpx;line-height:1.2;&.online{background:#347a5b}}.voice{position:absolute;left:10rpx;top:10rpx;padding:5rpx 10rpx;border-radius:999rpx;background:rgba(255,255,255,.9);color:#347a5b;font-size:18rpx}
.content{min-width:0;display:flex;flex-direction:column;gap:12rpx}.title-row{display:flex;align-items:center;gap:12rpx;min-width:0}.name{min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;font-size:32rpx;font-weight:800;color:#17191c}.level{flex-shrink:0;padding:4rpx 12rpx;border-radius:999rpx;background:#17191c;color:#fff;font-size:19rpx}.metrics{display:flex;flex-wrap:wrap;gap:14rpx;color:#858992;font-size:21rpx}.introduction{display:-webkit-box;overflow:hidden;color:#555b63;font-size:24rpx;line-height:1.55;-webkit-box-orient:vertical;-webkit-line-clamp:2}.tags{display:flex;flex-wrap:wrap;gap:8rpx;text{padding:4rpx 10rpx;border-radius:999rpx;background:#f2f3f5;color:#666b73;font-size:19rpx}}.actions{display:flex;justify-content:flex-end;gap:12rpx;margin-top:auto}.action{min-width:128rpx;padding:11rpx 20rpx;border-radius:999rpx;text-align:center;font-size:23rpx;font-weight:700;&.secondary{border:1rpx solid #dfe2e6;color:#343a43;background:#fff}&.primary{background:#17191c;color:#fff}&.disabled{background:#9a9ea5}}
.compact{grid-template-columns:140rpx minmax(0,1fr);gap:16rpx;padding:16rpx;border-radius:16rpx}.compact .media{height:140rpx;border-radius:12rpx}.compact .content{gap:7rpx}.compact .name{font-size:28rpx}.compact .metrics{font-size:20rpx}.compact .action{min-width:104rpx;padding:8rpx 14rpx;font-size:21rpx}
</style>
