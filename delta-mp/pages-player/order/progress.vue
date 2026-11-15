<template>
  <view class="progress-page">
    <PageState v-if="!orderId" empty empty-text="缺少订单信息，请返回订单详情重试" />
    <template v-else>
      <textarea v-model="content" placeholder="描述当前进度" :maxlength="500" :disabled="submitting || submitted" />
      <view :class="{ 'submit-disabled': submitting || submitted }"><ImageUploader v-model="images" :max="6" /></view>
      <PageState v-if="error" :error="error" @retry="submit" />
      <view class="btn" :class="{ 'submit-disabled': submitting || submitted }" :aria-disabled="submitting || submitted" @click="submit">{{ submitted ? '已提交' : submitting ? '提交中...' : '提交' }}</view>
    </template>
  </view>
</template>
<script setup>
import { ref } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import ImageUploader from '@/components/ImageUploader.vue'
import PageState from '@/components/PageState.vue'
import { submitWorkProgress } from '@/api/player'
const orderId = ref(0)
const content = ref('')
const images = ref([])
const submitting = ref(false)
const submitted = ref(false)
const error = ref('')
let disposed = false, backTimer = null
onLoad((opts) => { orderId.value = opts?.orderId || 0 })
onUnload(() => { disposed = true; clearTimeout(backTimer) })
async function submit() {
  if (disposed || submitting.value || submitted.value || !orderId.value) return false
  if (!content.value.trim()) return uni.showToast({title:'请描述进度',icon:'none'})
  const payload = { content: content.value, images: images.value.join(',') }
  submitting.value = true; error.value = ''
  try {
    await submitWorkProgress(orderId.value, payload)
    if (disposed) return false
    submitted.value = true
    uni.showToast({title:'已提交'})
    backTimer = setTimeout(() => { if (!disposed) uni.navigateBack() }, 1500)
    return true
  } catch (err) {
    if (!disposed) error.value = String(err?.msg || err?.data?.msg || err?.message || '提交失败，请重试')
    return false
  } finally { if (!disposed) submitting.value = false }
}
</script>
<style lang="scss" scoped>
.progress-page { padding:24rpx; background: #ffffff; min-height: var(--app-page-height, 100vh); }
textarea { width:100%; height:240rpx; background: #f7f7f7; border-radius:12rpx; padding:24rpx; font-size:28rpx; margin-bottom:24rpx; }
.btn { height:88rpx; line-height:88rpx; text-align:center; background: #171717; color:#ffffff; font-weight:bold; font-size:32rpx; border-radius:999rpx; margin-top:40rpx; }
.submit-disabled { opacity: .6; pointer-events: none; }
</style>
