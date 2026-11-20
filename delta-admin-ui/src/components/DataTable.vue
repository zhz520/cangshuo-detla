<template>
  <el-table ref="tableRef" v-bind="$attrs" v-loading="loading" :data="data" :row-key="rowKey" :stripe="stripe" :border="border"
    :height="height" class="delta-data-table">
    <slot />
    <template #empty>
      <slot name="empty">
        <span class="delta-data-table-empty">{{ emptyText }}</span>
      </slot>
    </template>
  </el-table>
</template>

<script setup>
import { ref } from 'vue'

defineOptions({ inheritAttrs: false })

defineProps({
  data: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  rowKey: { type: [String, Function], default: undefined },
  stripe: { type: Boolean, default: true },
  border: { type: Boolean, default: false },
  emptyText: { type: String, default: '暂无数据' },
  height: { type: [String, Number], default: undefined }
})

const tableRef = ref(null)
defineExpose({ tableRef })
</script>

<style scoped>
.delta-data-table { width: 100%; }
.delta-data-table :deep(.el-table__header th) { background: var(--el-fill-color-light); color: var(--el-text-color-secondary); font-weight: 600; }
.delta-data-table :deep(.el-table__row:hover > td.el-table__cell) { background: var(--el-fill-color-lighter); }
.delta-data-table :deep(.el-table__cell) { padding: 10px 0; }
.delta-data-table :deep(.el-table__inner-wrapper::before) { background-color: var(--el-border-color-lighter); }
.delta-data-table-empty { display: block; padding: 24px 0; color: var(--el-text-color-secondary); font-size: 13px; }
</style>



