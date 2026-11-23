<template>
  <LoadFeedback v-if="loading && phase === 'initial'" />
  <view v-else-if="error" class="page-state state-message" role="alert">
    <text class="state-title">暂时未能加载</text><text class="state-caption">{{ error }}</text>
    <button class="state-retry" role="button" aria-label="重新加载" @click="$emit('retry')">重新加载</button>
  </view>
  <EmptyState v-else-if="empty && !loading" :text="emptyText" />
  <slot v-else />
  <LoadFeedback v-if="loading && phase !== 'initial'" :phase="phase" />
</template>
<script setup>
import LoadFeedback from './LoadFeedback.vue'
import EmptyState from './EmptyState.vue'
defineProps({ loading: Boolean, phase: { type: String, default: 'initial' }, error: { type: String, default: '' }, empty: Boolean, emptyText: { type: String, default: '暂无数据' } })
defineEmits(['retry'])
</script>
<style scoped>
.page-state { padding: 24rpx; }
.state-caption { display: block; color: var(--store-regular); font-size: 25rpx; line-height: 1.7; text-align: center; }
.state-message { display: flex; flex-direction: column; align-items: center; padding: 64rpx 32rpx; gap: 18rpx; }
.state-title { font-size: 30rpx; font-weight: 600; color: var(--store-text); }
.state-retry { font-size: 26rpx; padding: 0 36rpx; border-radius: 999rpx; background: var(--store-primary); color: white; margin-top: 10rpx; }
</style>
