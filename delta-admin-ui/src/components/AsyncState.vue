<template>
  <div class="async-state" :class="{ 'is-fill': fill }" :style="fill ? {} : { minHeight }" :aria-busy="loading ? 'true' : 'false'">
    <slot v-if="error" name="error">
      <div class="async-state-panel is-error" role="alert">
        <el-icon class="async-state-icon"><WarningFilled /></el-icon>
        <div class="async-state-title">{{ errorTitle }}</div>
        <p class="async-state-description">{{ error }}</p>
        <el-button v-if="retryable" size="small" @click="$emit('retry')">重试</el-button>
      </div>
    </slot>
    <div v-else-if="loading" class="async-state-skeleton" role="status" aria-live="polite" :aria-label="loadingText">
      <span class="sr-only">{{ loadingText }}</span>
      <div v-for="row in rows" :key="row" class="async-state-skeleton-row" :class="'row-' + ((row - 1) % 4 + 1)"></div>
    </div>
    <slot v-else-if="empty" name="empty">
      <div class="async-state-panel is-empty">
        <el-icon class="async-state-icon"><Box /></el-icon>
        <div class="async-state-title">{{ emptyTitle }}</div>
        <p v-if="emptyDescription" class="async-state-description">{{ emptyDescription }}</p>
        <slot name="empty-action" />
      </div>
    </slot>
    <slot v-else />
  </div>
</template>

<script setup>
import { Box, WarningFilled } from '@element-plus/icons-vue'

defineProps({
  loading: { type: Boolean, default: false },
  empty: { type: Boolean, default: false },
  error: { type: String, default: '' },
  emptyTitle: { type: String, default: '暂无数据' },
  emptyDescription: { type: String, default: '' },
  errorTitle: { type: String, default: '加载失败' },
  loadingText: { type: String, default: '正在加载' },
  retryable: { type: Boolean, default: true },
  rows: { type: Number, default: 3 },
  minHeight: { type: String, default: '120px' },
  fill: { type: Boolean, default: false }
})

defineEmits(['retry'])
</script>

<style scoped>
.async-state { min-width: 0; }
.async-state.is-fill { display: flex; min-height: 100%; }
.async-state.is-fill > * { width: 100%; }
.async-state-panel { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; min-height: inherit; padding: 28px 16px; text-align: center; }
.async-state-icon { font-size: 28px; color: var(--el-text-color-placeholder); }
.async-state-panel.is-error .async-state-icon { color: var(--el-color-danger); }
.async-state-title { font-size: 14px; font-weight: 600; color: var(--el-text-color-primary); }
.async-state-description { margin: 0; max-width: 420px; font-size: 12px; line-height: 1.6; color: var(--el-text-color-secondary); }
.async-state-skeleton { display: flex; flex-direction: column; gap: 10px; width: 100%; padding: 8px 0; }
.async-state-skeleton-row { height: 14px; border-radius: 4px; background: linear-gradient(90deg, var(--el-fill-color-light) 25%, var(--el-fill-color) 37%, var(--el-fill-color-light) 63%); background-size: 400% 100%; animation: async-state-shimmer 1.4s ease infinite; }
.async-state-skeleton-row.row-1 { width: 42%; }
.async-state-skeleton-row.row-2 { width: 78%; }
.async-state-skeleton-row.row-3 { width: 64%; }
.async-state-skeleton-row.row-4 { width: 88%; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
@keyframes async-state-shimmer { 0% { background-position: 100% 50%; } 100% { background-position: 0 50%; } }
@media (prefers-reduced-motion: reduce) {
  .async-state-skeleton-row { animation: none; background: var(--el-fill-color-light); }
}
</style>

