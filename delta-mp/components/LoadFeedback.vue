<template>
  <view class="load-feedback" :class="'load-' + phase" role="status" aria-live="polite" :aria-busy="true">
    <view v-if="phase === 'initial'" aria-hidden="true" class="skeleton">
      <view v-for="width in ['44%', '84%', '66%']" :key="width" class="line" :style="{ width }" />
    </view>
    <text>{{ phase === 'more' ? '正在加载更多' : phase === 'refresh' ? '正在刷新' : '正在加载' }}</text>
  </view>
</template>
<script setup>
defineProps({ phase: { type: String, default: 'initial', validator: v => ['initial','refresh','more'].includes(v) } })
</script>
<style scoped>
.load-feedback { padding: 16rpx 24rpx; color: var(--store-regular); text-align: center; font-size: 25rpx; line-height: 1.5; }
.load-initial { padding: 28rpx; }
.skeleton { margin-bottom: 18rpx; }
.line { height: 20rpx; margin: 12rpx 0; border-radius: 6rpx; background: var(--store-line, #e0e2e4); }
/* #ifdef H5 */
@media (prefers-reduced-motion: no-preference) {
  .skeleton { animation: delta-loading-pulse var(--delta-motion-pulse) ease-in-out infinite alternate; }
}
@keyframes delta-loading-pulse { to { opacity: .56; } }
/* #endif */
</style>
