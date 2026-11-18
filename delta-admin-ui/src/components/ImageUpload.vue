<template>
  <div class="image-upload" :class="{ 'logo-upload': logo }">
  <div v-if="logo" class="logo-image-box">
    <img v-if="modelValue" :src="modelValue" alt="平台 Logo" class="upload-image" @load="readDimensions" @error="dimensions = null" />
    <Picture v-else aria-hidden="true" />
  </div>
  <div :class="{ 'logo-upload-actions': logo }">
  <el-upload
    :http-request="handleUpload"
    :show-file-list="false"
    :before-upload="beforeUpload"
    :disabled="uploading || disabled"
    accept="image/*"
  >
    <el-button v-if="logo" :icon="Upload" :disabled="uploading || disabled">{{ uploading ? '上传中…' : modelValue ? '更换图片' : '上传图片' }}</el-button>
    <img decoding="async" v-else-if="modelValue" :src="modelValue" class="upload-image" alt="已上传图片，点击更换" @load="readDimensions" @error="dimensions = null" />
    <div v-else class="upload-icon"><el-icon><Plus /></el-icon><span>{{ uploading ? '上传中…' : '上传图片' }}</span></div>
  </el-upload>
  <div class="upload-tip">{{ uploading ? '正在上传图片…' : logo ? '建议 1:1 方图（512 × 512），最大 5MB' : '支持横图、竖图和方图，保留原比例，最大 5MB' }}</div>
  <div v-if="dimensions" class="upload-tip">{{ dimensions.width }} × {{ dimensions.height }} 像素 · {{ ratioLabel }}</div>
  <el-button v-if="modelValue" link type="primary" @click="previewVisible = true">查看原图</el-button>
  </div>
  <el-image-viewer v-if="previewVisible && modelValue" :url-list="[modelValue]" teleported @close="previewVisible = false" />
  </div>
</template>

<script setup>
import { Picture, Plus, Upload } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { uploadFile } from '@/api/business'

const props = defineProps({ modelValue: { type: String, default: '' }, disabled: Boolean, logo: Boolean })
const emit = defineEmits(['update:modelValue', 'uploading'])
const uploading = ref(false)
const previewVisible = ref(false)
const dimensions = ref(null)
const ratioLabel = computed(() => {
  if (!dimensions.value) return ''
  const { width, height } = dimensions.value
  let a = width, b = height
  while (b) [a, b] = [b, a % b]
  return `比例 ${width / a}:${height / a}`
})
watch(() => props.modelValue, () => { dimensions.value = null; previewVisible.value = false })

function readDimensions(event) {
  const { naturalWidth: width, naturalHeight: height } = event.target
  dimensions.value = width && height ? { width, height } : null
}

function beforeUpload(file) {
  const isImage = file.type.startsWith('image/')
  const isLt5M = file.size / 1024 / 1024 < 5
  if (!isImage) ElMessage.error('只能上传图片文件')
  if (!isLt5M) ElMessage.error('图片大小不能超过 5MB')
  return isImage && isLt5M
}

async function handleUpload({ file }) {
  uploading.value = true
  emit('uploading', true)
  try {
    const res = await uploadFile(file)
    emit('update:modelValue', res.data)
  } catch (e) {
    ElMessage.error('上传失败')
  } finally {
    uploading.value = false
    emit('uploading', false)
  }
}
</script>

<style scoped>
.image-upload {
  max-width: 100%;
  min-width: 0;
}
.image-upload :deep(.el-upload) {
  max-width: 100%;
  display: flex;
  justify-content: flex-start;
}
.logo-upload { display:flex; align-items:center; flex-wrap:wrap; gap:12px 20px }
.logo-upload-actions { min-width:0 }
.logo-image-box { width:108px; height:108px; display:grid; place-items:center; background:var(--el-fill-color-light); border:1px solid var(--el-border-color-light); border-radius:10px; overflow:hidden }
.logo-image-box > svg { width:27px; height:27px; color:var(--el-text-color-secondary) }
.logo-image-box .upload-image { width:100%; height:100%; border:0; border-radius:0 }
.upload-image {
  display: block;
  width: auto;
  height: auto;
  max-width: min(240px, 100%);
  max-height: 160px;
  object-fit: contain;
  border: 1px solid #e6e7e9;
  border-radius: 6px;
}
.upload-icon {
  width: 120px;
  height: 120px;
  font-size: 28px;
  color: #8c939d;
  border: 1px dashed #d9d9d9;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}
.upload-icon span { font-size: 12px; }
.upload-tip {
  margin-top: 6px;
  font-size: 12px;
  color: #73777e;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.upload-icon:hover {
  border-color: #17191c;
  color: #17191c;
}
</style>
