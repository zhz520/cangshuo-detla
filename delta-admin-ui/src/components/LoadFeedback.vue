<template>
  <div v-if="error || visible" class="load-feedback" :class="{ 'load-feedback--error': error }" :role="error ? 'alert' : 'status'">
    <i v-if="loading && !error" class="load-feedback__pulse" aria-hidden="true" />
    <span>{{ error ? `${label}加载失败：${error}` : `正在加载${label}…` }}</span>
    <el-button v-if="error" size="small" :loading="loading" @click="$emit('retry')">{{ retryLabel }}</el-button>
  </div>
</template>

<script setup>
import { useDelayedBusy } from '@/composables/useDelayedBusy'
const props = defineProps({ loading: Boolean, error: String, label: { type: String, default: '数据' }, retryLabel: { type: String, default: '重试' } })
defineEmits(['retry'])
const visible = useDelayedBusy(() => props.loading)
</script>

<style scoped>
.load-feedback { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 42px; margin-bottom: 12px; padding: 8px 12px; border: 1px solid var(--app-border); border-radius: 8px; color: var(--app-text-regular); background: var(--app-surface-soft); }
.load-feedback__pulse { width: 8px; height: 8px; flex: none; border-radius: 50%; background: var(--app-primary); animation: feedback-pulse var(--delta-motion-pulse) var(--delta-motion-ease) infinite alternate; }
@keyframes feedback-pulse { to { opacity: .35; } }
@media (prefers-reduced-motion: reduce) { .load-feedback__pulse { animation: none; } }
.load-feedback > span { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.load-feedback .el-button { flex-shrink: 0; }
.load-feedback--error { border-color: var(--el-color-danger-light-8); background: var(--el-color-danger-light-9); color: var(--el-color-danger); }
</style>
