<template>
  <picker class="product-sort-picker" :range="options" range-key="label" :value="selectedIndex" @change="change">
    <view class="sort-label"><text>{{ label || options[selectedIndex].label }}</text><text class="sort-chevron">⌄</text></view>
  </picker>
</template>
<script setup>
import { computed } from 'vue'
import { productSortOptions } from '@/utils/product-sort'
const props = defineProps({
  modelValue: { type: String, default: 'default' },
  label: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])
const options = productSortOptions
const selectedIndex = computed(() => Math.max(0, options.findIndex(item => item.value === props.modelValue)))
function change(event) { emit('update:modelValue', options[Number(event.detail.value)]?.value || 'default') }
</script>
<style scoped>
.product-sort-picker { flex-shrink: 0; min-width: 0; }
.sort-label { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; padding: 17rpx 24rpx; background: #fff; border: 0; border-radius: 18rpx; color: #111111; font-size: 24rpx; }
.sort-chevron { color: #999999; }
</style>
