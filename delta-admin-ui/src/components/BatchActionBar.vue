<template>
  <transition name="batch-bar">
    <div v-if="count" class="batch-action-bar" role="region" aria-label="批量操作">
      <div class="batch-summary">
        <span class="batch-count">{{ count }}</span>
        <div><strong>已选择 {{ count }} 项</strong><small>{{ hint }}</small></div>
      </div>
      <div class="batch-actions">
        <slot />
        <el-button link :disabled="disabled" @click="emit('clear')">取消选择</el-button>
      </div>
    </div>
  </transition>
</template>

<script setup>
defineProps({
  count: { type: Number, default: 0 },
  hint: { type: String, default: '批量操作只影响已勾选的数据' },
  disabled: Boolean
})
const emit = defineEmits(['clear'])
</script>

<style scoped>
.batch-action-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 0 0 16px;
  padding: 12px 14px;
  color: #f7f9fc;
  border-radius: 10px;
  background: linear-gradient(120deg, #17191c, #2b3038);
  box-shadow: 0 10px 24px rgba(23, 25, 28, .16);
}
.batch-summary { display: flex; align-items: center; gap: 10px; min-width: 0; }
.batch-count {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  border-radius: 9px;
  color: #17191c;
  background: #fff;
  font-weight: 800;
}
.batch-summary strong, .batch-summary small { display: block; }
.batch-summary strong { font-size: 13px; }
.batch-summary small { margin-top: 2px; color: #b8bdc6; font-size: 11px; }
.batch-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }
.batch-actions :deep(.el-button) { margin-left: 0; }
.batch-actions :deep(.el-button.is-link) { color: #e4e8ef; }
.batch-actions :deep(.el-button.is-link:hover) { color: #fff; }
.batch-actions :deep(.el-button--danger.is-link) { color: #ff9ca7; }
.batch-actions :deep(.el-button.is-disabled) { color: #747b87; }
.batch-bar-enter-active, .batch-bar-leave-active { transition: opacity .18s ease, transform .18s ease; }
.batch-bar-enter-from, .batch-bar-leave-to { opacity: 0; transform: translateY(-5px); }
@media (max-width: 760px) {
  .batch-action-bar { align-items: stretch; flex-direction: column; }
  .batch-actions { justify-content: flex-start; }
}
</style>
