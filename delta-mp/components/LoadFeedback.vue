<template>
  <view class="load-feedback" :class="'load-' + phase" role="status" aria-live="polite" :aria-busy="true" :aria-label="phase === 'more' ? '正在加载更多' : phase === 'refresh' ? '正在刷新' : '正在加载'">
    <view v-if="phase === 'initial'" aria-hidden="true" class="skeleton">
      <view v-for="index in 2" :key="index" class="skeleton-card">
        <view class="skeleton-media" />
        <view class="skeleton-copy">
          <view class="line" style="width: 68%" />
          <view class="line" style="width: 92%" />
          <view class="line" style="width: 46%" />
        </view>
      </view>
    </view>
    <view v-else class="load-inline"><view class="load-dot" /><text>{{ phase === 'more' ? '加载更多' : '刷新中' }}</text></view>
  </view>
</template>
<script setup>
defineProps({ phase: { type: String, default: 'initial', validator: v => ['initial','refresh','more'].includes(v) } })
</script>
<style scoped>
.load-feedback { padding: 16rpx 24rpx; color: var(--store-regular); text-align: center; font-size: 24rpx; line-height: 1.5; }
.load-initial { padding: 20rpx 24rpx 28rpx; }
.skeleton { display: flex; flex-direction: column; gap: 18rpx; }
.skeleton-card { display: flex; gap: 20rpx; padding: 20rpx; border-radius: 22rpx; background: var(--store-surface, #fff); border: 1rpx solid var(--store-line, #e8eaee); }
.skeleton-media, .line { background: linear-gradient(100deg, #eef0f2 28%, #f8f9fa 46%, #eef0f2 64%); background-size: 220% 100%; }
.skeleton-media { width: 176rpx; height: 176rpx; flex-shrink: 0; border-radius: 16rpx; }
.skeleton-copy { flex: 1; min-width: 0; display: flex; flex-direction: column; justify-content: center; gap: 16rpx; }
.line { height: 22rpx; border-radius: 8rpx; }
.load-inline { display: inline-flex; align-items: center; gap: 10rpx; padding: 8rpx 18rpx; border-radius: 999rpx; background: var(--store-soft, #f2f3f4); }
.load-dot { width: 10rpx; height: 10rpx; border-radius: 50%; background: currentColor; opacity: .55; }
@media (prefers-reduced-motion: no-preference) {
  .skeleton-media, .line { animation: load-shimmer 1.25s linear infinite; }
  .load-dot { animation: load-dot 900ms ease-in-out infinite alternate; }
}
@keyframes load-shimmer { to { background-position: -220% 0; } }
@keyframes load-dot { to { opacity: .2; transform: scale(.7); } }
</style>
