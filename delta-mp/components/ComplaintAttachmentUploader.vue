<template>
  <view class="complaint-attachment-uploader">
    <view v-for="item in displayItems" :key="item.key" class="preview-item">
      <AppImage v-if="item.src" :src="item.src" mode="aspectFill" @click="previewImage(item.key)" />
      <view v-else class="preview-placeholder">已上传</view>
      <view v-if="item.pending" class="upload-mask">上传中</view>
      <view v-else class="remove-btn" @click.stop="removeImage(item.token)">×</view>
    </view>
    <view v-if="displayItems.length < max && !disposed" class="add-btn" :class="{ uploading }" @click="chooseImage">
      <text class="add-icon">+</text>
      <text class="add-text">{{ uploading ? '上传中...' : '上传图片' }}</text>
    </view>
    <text v-if="uploading" class="upload-hint">附件将私有保存，仅本人与授权客服可查看</text>
  </view>
</template>

<script setup>
import AppImage from '@/components/AppImage.vue'
import { computed, onUnmounted, reactive, ref, watch } from 'vue'
import { releaseComplaintAttachmentUrl, uploadComplaintAttachment } from '@/api/complaint'

const props = defineProps({
  modelValue: { type: Array, default: () => [] },
  orderId: { type: [String, Number], required: true },
  max: { type: Number, default: 6 }
})

const emit = defineEmits(['update:modelValue', 'uploading'])
const uploading = ref(false)
const pendingItems = ref([])
const localSources = reactive({})
let pendingSequence = 0
let disposed = false

const displayItems = computed(() => [
  ...props.modelValue.map(token => ({
    key: `token:${token}`,
    token,
    src: localSources[token] || '',
    pending: false
  })),
  ...pendingItems.value.map(item => ({
    key: `pending:${item.id}`,
    token: '',
    src: item.path,
    pending: true
  }))
])

watch(() => props.modelValue, tokens => {
  const active = new Set(tokens || [])
  for (const token of Object.keys(localSources)) {
    if (!active.has(token)) {
      releaseComplaintAttachmentUrl(localSources[token])
      delete localSources[token]
    }
  }
}, { deep: true })

function setUploading(value) {
  if (disposed || uploading.value === value) return
  uploading.value = value
  emit('uploading', value)
}

function showUploadError(error) {
  if (disposed) return
  const message = String(error?.errMsg || error?.message || error?.msg || '')
  if (/cancel/i.test(message)) return
  uni.showToast({ title: message && !message.includes(':fail') ? message : '图片上传失败，请重试', icon: 'none' })
}

function chooseImage() {
  const remain = props.max - displayItems.value.length
  if (remain <= 0 || uploading.value || disposed) return
  uni.chooseImage({
    count: Math.min(9, remain),
    sizeType: ['compressed'],
    sourceType: ['album', 'camera'],
    success: result => uploadImages((result.tempFilePaths || []).slice(0, remain)),
    fail: showUploadError
  })
}

async function uploadImages(paths) {
  if (uploading.value || disposed || !paths.length) return
  const pending = paths.map(path => ({ id: ++pendingSequence, path }))
  pendingItems.value = [...pendingItems.value, ...pending]
  const nextTokens = [...props.modelValue]
  setUploading(true)
  try {
    for (const item of pending) {
      if (disposed || nextTokens.length >= props.max) break
      try {
        const token = await uploadComplaintAttachment(props.orderId, item.path)
        if (disposed) break
        if (!nextTokens.includes(token)) {
          nextTokens.push(token)
          localSources[token] = item.path
          emit('update:modelValue', [...nextTokens])
        }
      } catch (error) {
        releaseComplaintAttachmentUrl(item.path)
        showUploadError(error)
      } finally {
        pendingItems.value = pendingItems.value.filter(current => current.id !== item.id)
      }
    }
  } finally {
    setUploading(false)
  }
}

function removeImage(token) {
  if (!token) return
  releaseComplaintAttachmentUrl(localSources[token])
  delete localSources[token]
  emit('update:modelValue', props.modelValue.filter(item => item !== token))
}

function previewImage(key) {
  const ready = displayItems.value.filter(item => item.src)
  const currentIndex = ready.findIndex(item => item.key === key)
  if (currentIndex < 0) return
  uni.previewImage({ urls: ready.map(item => item.src), current: currentIndex })
}

onUnmounted(() => {
  disposed = true
  for (const source of Object.values(localSources)) releaseComplaintAttachmentUrl(source)
  for (const item of pendingItems.value) releaseComplaintAttachmentUrl(item.path)
})
</script>

<style lang="scss" scoped>
.complaint-attachment-uploader {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  width: 100%;

  .preview-item {
    position: relative;
    width: 160rpx;
    height: 160rpx;
    border-radius: 8rpx;
    overflow: hidden;

    :is(image, .app-image) {
      width: 100%;
      height: 100%;
    }

    .preview-placeholder {
      width: 100%;
      height: 100%;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--store-soft);
      color: var(--store-regular);
      font-size: 22rpx;
    }

    .upload-mask {
      position: absolute;
      inset: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      background: rgba(0, 0, 0, 0.48);
      color: #fff;
      font-size: 22rpx;
    }

    .remove-btn {
      position: absolute;
      top: 4rpx;
      right: 4rpx;
      width: 36rpx;
      height: 36rpx;
      line-height: 34rpx;
      text-align: center;
      font-size: 28rpx;
      color: #fff;
      background: rgba(0, 0, 0, 0.55);
      border-radius: 50%;
    }
  }

  .add-btn {
    width: 160rpx;
    height: 160rpx;
    flex-shrink: 0;
    box-sizing: border-box;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    border: 2rpx dashed var(--store-line);
    border-radius: 8rpx;

    &.uploading { opacity: 0.6; cursor: wait; }

    .add-icon { font-size: 48rpx; color: rgba(23, 25, 28, 0.5); }
    .add-text { font-size: 22rpx; color: var(--store-regular); margin-top: 4rpx; }
  }

  .upload-hint {
    width: 100%;
    font-size: 22rpx;
    color: var(--store-regular);
    line-height: 1.5;
  }
}
</style>
