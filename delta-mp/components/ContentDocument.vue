<template>
  <view class="content-document">
    <view v-if="loading" class="content-state">正在加载…</view>
    <view v-else-if="error" class="content-state">
      <text>{{ error }}</text>
      <view class="content-retry" @click="load">重新加载</view>
    </view>
    <rich-text v-else-if="html" :nodes="html" />
    <view v-else class="content-state">内容尚未配置，请联系平台客服。</view>
  </view>
</template>
<script setup>
import { computed, ref } from 'vue'
import { getContentByKey } from '@/api/content'
import { useSiteStore } from '@/store/site'
import { resolveContentDocument } from '@/utils/content-document.mjs'
import { productDescriptionHtml } from '@/utils/product-content'

const props = defineProps({ type: { type: String, default: 'user_agreement' } })
const loading = ref(true)
const error = ref('')
const raw = ref('')
const siteStore = useSiteStore()
const html = computed(() => productDescriptionHtml(resolveContentDocument(props.type, raw.value, siteStore.fullName)))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const res = await getContentByKey(props.type)
    raw.value = res.data?.configValue || ''
  } catch (e) {
    error.value = '内容加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}
load()
</script>
<style lang="scss" scoped>
.content-document { min-height: var(--app-page-height, 100vh); padding: 32rpx 30rpx calc(48rpx + var(--app-safe-bottom, env(safe-area-inset-bottom))); background: var(--store-soft); color: #333333; font-size: 27rpx; line-height: 1.85; overflow-wrap: anywhere; }
.content-state { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 20rpx; min-height: 420rpx; color: var(--store-muted); font-size: 25rpx; text-align: center; }
.content-retry { padding: 12rpx 28rpx; border-radius: 999rpx; background: var(--store-primary); color: #ffffff; font-size: 24rpx; }
</style>
