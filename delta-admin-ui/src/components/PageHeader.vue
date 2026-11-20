<template>
  <div class="page-header" :class="{ 'is-inline': inline }">
    <div class="page-header-main">
      <div class="page-header-title">{{ title }}</div>
      <div v-if="description" class="page-header-description">{{ description }}</div>
    </div>
    <div v-if="$slots.actions" class="page-header-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, required: true },
  description: { type: String, default: '' },
  inline: { type: Boolean, default: false }
})
</script>

<style scoped>
.page-header { display:flex; align-items:center; justify-content:space-between; gap:12px; flex-wrap:wrap; }
.page-header-main { display:flex; align-items:baseline; gap:12px; flex-wrap:wrap; }
.page-header-title { font-size:15px; font-weight:600; color:var(--el-text-color-primary); }
.page-header-description { font-size:12px; color:var(--el-text-color-secondary); }
.page-header-actions { display:flex; align-items:center; gap:8px; }
.page-header:not(.is-inline) { align-items:flex-start; }
.page-header:not(.is-inline) .page-header-main { display:block; }
</style>
