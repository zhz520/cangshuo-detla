<template>
  <view v-if="loading" class="page-state" role="status" aria-live="polite" aria-label="正在加载">
    <view v-for="n in 3" :key="n" class="state-skeleton"><view class="skeleton-line short" /><view class="skeleton-line" /><view class="skeleton-line medium" /></view>
    <text class="state-caption">正在加载，请稍候</text>
  </view>
  <view v-else-if="error" class="page-state state-message" role="alert">
    <text class="state-title">暂时未能加载</text><text class="state-caption">{{ error }}</text>
    <button class="state-retry" role="button" aria-label="重新加载" @click="$emit('retry')">重新加载</button>
  </view>
  <EmptyState v-else-if="empty" :text="emptyText" />
  <slot v-else />
</template>
<script setup>
import EmptyState from './EmptyState.vue'
defineProps({ loading: Boolean, error: { type: String, default: '' }, empty: Boolean, emptyText: { type: String, default: '暂无数据' } })
defineEmits(['retry'])
</script>
<style scoped>
.page-state { padding: 24rpx; }
.pending-state { min-height: 160rpx; }
.state-skeleton { padding: 28rpx; margin-bottom: 20rpx; border-radius: 24rpx; background: var(--store-surface); border: 0; }
.skeleton-line { height: 22rpx; margin: 14rpx 0; border-radius: 8rpx; background: #eceff2; animation: state-pulse 1.4s ease-in-out infinite; }
.short { width: 32%; } .medium { width: 65%; }
.state-caption { display: block; color: var(--store-regular); font-size: 25rpx; line-height: 1.7; text-align: center; }
.state-message { display: flex; flex-direction: column; align-items: center; padding: 64rpx 32rpx; gap: 18rpx; }
.state-title { font-size: 30rpx; font-weight: 600; color: var(--store-text); }
.state-retry { font-size: 26rpx; padding: 0 36rpx; border-radius: 999rpx; background: var(--store-primary); color: white; margin-top: 10rpx; }
@keyframes state-pulse { 50% { opacity: .45; } }
@media (prefers-reduced-motion: reduce) { .skeleton-line { animation: none; } }
</style>
