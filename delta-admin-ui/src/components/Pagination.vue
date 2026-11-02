<template>
  <div class="pagination-wrapper">
    <el-pagination
      v-model:current-page="currentPage"
      v-model:page-size="pageSize"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      :pager-count="isNarrow ? 5 : 7"
      :layout="isNarrow ? 'total, prev, pager, next, sizes' : 'total, sizes, prev, pager, next, jumper'"
      background
      @size-change="handleSizeChange"
      @current-change="handleChange"
    />
  </div>
</template>

<script setup>
import { computed, nextTick } from 'vue'
import { useResponsive } from '@/utils/responsive'

const { isNarrow } = useResponsive()

const props = defineProps({
  total: { type: Number, default: 0 },
  page: { type: Number, default: 1 },
  limit: { type: Number, default: 10 }
})
const emit = defineEmits(['update:page', 'update:limit', 'pagination'])

const currentPage = computed({
  get: () => props.page,
  set: (val) => emit('update:page', val)
})
const pageSize = computed({
  get: () => props.limit,
  set: (val) => emit('update:limit', val)
})

let scheduled = false
function handleSizeChange() {
  emit('update:page', 1)
  handleChange()
}
function handleChange() {
  if (scheduled) return
  scheduled = true
  nextTick(() => { scheduled = false; emit('pagination') })
}
</script>

<style scoped>
.pagination-wrapper {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}
@media (max-width: 768px) {
  .pagination-wrapper { justify-content: center; }
  .pagination-wrapper :deep(.el-pagination) { margin-top: 0; }
}
</style>
