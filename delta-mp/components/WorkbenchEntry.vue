<template>
  <view class="workbench-entry" @click="open">
    <view class="workbench-copy">
      <text class="workbench-title">{{ title }}</text>
      <text class="workbench-caption">{{ caption }}</text>
    </view>
    <text v-if="pending" class="workbench-count">{{ pending > 99 ? '99+' : pending }}</text>
    <text v-if="unread" class="workbench-dot" aria-label="有未读待办" />
    <text class="workbench-arrow">›</text>
  </view>
</template>
<script setup>
import { computed } from 'vue'
import { useRemindStore } from '@/store/remind'
defineProps({ title: { type: String, default: '待办中心' } })
const store = useRemindStore()
const pending = computed(() => store.summary?.pending || 0)
const unread = computed(() => store.workbenchUnread)
const caption = computed(() => {
  if (!store.summary) return '查看需要处理的事项'
  if (!pending.value) return '暂无待处理事项'
  return store.summary.urgent ? store.summary.urgent + ' 项需要优先处理' : '查看需要处理的事项'
})
function open() { uni.navigateTo({ url: '/pages/workbench/index' }) }
</script>
<style scoped>
.workbench-entry { margin:20rpx 24rpx; padding:22rpx 24rpx; border:1rpx solid #e5e7ea; border-left:6rpx solid #202326; border-radius:14rpx; background:var(--store-surface); display:flex; align-items:center; gap:12rpx; }
.workbench-copy { flex:1; min-width:0; }
.workbench-title,.workbench-caption { display:block; }
.workbench-title { font-size:30rpx; font-weight:700; }
.workbench-caption { font-size:24rpx; color:var(--store-regular); margin-top:5rpx; }
.workbench-count { padding:3rpx 12rpx; border-radius:30rpx; background:var(--store-soft); font-weight:700; }
.workbench-dot { width:13rpx; height:13rpx; background:#c83e4b; border-radius:50%; }
.workbench-arrow { font-size:34rpx; color:var(--store-regular); }
</style>
