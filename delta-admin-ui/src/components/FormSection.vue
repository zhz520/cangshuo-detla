<template>
  <section class="form-section" :class="{ 'is-compact': compact, 'is-divided': divided }">
    <header v-if="title || description || $slots.actions" class="form-section-header">
      <div class="form-section-heading">
        <h3 v-if="title" class="form-section-title">{{ title }}</h3>
        <p v-if="description" class="form-section-description">{{ description }}</p>
      </div>
      <div v-if="$slots.actions" class="form-section-actions">
        <slot name="actions" />
      </div>
    </header>
    <div class="form-section-body">
      <slot />
    </div>
  </section>
</template>

<script setup>
defineProps({
  title: { type: String, default: '' },
  description: { type: String, default: '' },
  compact: { type: Boolean, default: false },
  divided: { type: Boolean, default: true }
})
</script>

<style scoped>
.form-section { padding: 18px 0; }
.form-section.is-compact { padding: 12px 0; }
.form-section.is-divided + .form-section.is-divided { border-top: 1px solid var(--el-border-color-lighter); }
.form-section:first-child { padding-top: 0; }
.form-section:last-child { padding-bottom: 0; }
.form-section-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 14px; }
.form-section-heading { min-width: 0; }
.form-section-title { margin: 0; font-size: 14px; line-height: 1.5; font-weight: 600; color: var(--el-text-color-primary); }
.form-section-description { margin: 4px 0 0; font-size: 12px; line-height: 1.6; color: var(--el-text-color-secondary); }
.form-section-actions { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.form-section-body { min-width: 0; }
</style>

