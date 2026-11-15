<template>
  <view class="agreement-page">
    <rich-text :nodes="content" />
  </view>
</template>
<script setup>
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { getContentByKey } from '@/api/content'
import { productDescriptionHtml } from '@/utils/product-content'
const rawContent = ref('')
const content = computed(() => productDescriptionHtml(rawContent.value))
onLoad(async (opts) => { const res = await getContentByKey(opts.type||'user_agreement'); rawContent.value = res.data?.configValue || '' })
</script>
<style lang="scss" scoped>
.agreement-page { padding:24rpx; background:#f7f7f7; min-height:var(--app-page-height, 100vh); font-size:28rpx; color:#111111; line-height:1.8; }
</style>
