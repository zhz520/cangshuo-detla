<template>
  <image ref="imageRef" :src="displaySource" :mode="mode" :lazy-load="!eager" class="app-image" :class="{ 'image-loading': !loaded, 'image-failed': failed, 'icon-image': isVector }" @load="onLoad" @error="onError" />
</template>
<script setup>
import { computed, nextTick, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { imageRecoveryUrl, thumbnailUrl } from '@/utils/image'
const props = defineProps({
  src: { type: String, default: '' },
  mode: { type: String, default: 'aspectFill' },
  width: { type: Number, default: 640 },
  fallback: { type: String, default: '/static/icons/图片.svg' },
  eager: Boolean
})
const emit = defineEmits(['load', 'error'])
const imageRef = ref(null), loaded = ref(false), failed = ref(false), original = ref(false), recovered = ref('')
const visible = ref(true)
let observer
// #ifdef H5
visible.value = props.eager || typeof IntersectionObserver === 'undefined'
// #endif
const criticalSource = computed(() => {
  const source = String(props.src || '').trim()
  return Boolean(props.eager) || source.startsWith('/static/') || /^(data|blob):/i.test(source)
})
const isVector = computed(() => /\.svg(?:\?|$)/i.test(String(props.src || '')))
const displaySource = computed(() => {
  if (!String(props.src || '').trim() || failed.value) return props.fallback
  if (!visible.value && !criticalSource.value) return ''
  return recovered.value || (original.value ? props.src : thumbnailUrl(props.src, props.width))
})
function hints() {
  // #ifdef H5
  const element = imageRef.value?.$el || imageRef.value
  const img = element?.querySelector?.('img')
  if (img) { img.decoding = 'async'; img.fetchPriority = props.eager ? 'high' : 'auto' }
  // #endif
}
watch([() => props.src, () => props.width], () => {
  loaded.value = false
  failed.value = false
  original.value = false
  recovered.value = ''
  nextTick(hints)
})
function onLoad(event) { loaded.value = true; hints(); emit('load', event) }
function onError(event) {
  if (failed.value || !displaySource.value) return
  const source = String(props.src || '').trim()
  if (source && !original.value && displaySource.value !== source) { original.value = true; return }
  // #ifdef H5
  if (!recovered.value && source) {
    const retry = imageRecoveryUrl(source, window.location.origin, Date.now())
    if (retry) { recovered.value = retry; return }
  }
  // #endif
  failed.value = true
  loaded.value = true
  emit('error', event)
}
onMounted(() => {
  // #ifdef H5
  if (!visible.value) {
    observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) { visible.value = true; observer.disconnect(); nextTick(hints) }
    }, { rootMargin: '160px' })
    observer.observe(imageRef.value?.$el || imageRef.value)
  }
  // #endif
  nextTick(hints)
})
onBeforeUnmount(() => observer?.disconnect())
</script>
<style scoped>
.app-image { background-color: #f0f1f3; transition: opacity 160ms ease; }
.image-loading { background-color: #f0f1f3; }
.image-failed { background-color: #f0f1f3; }
.app-image.icon-image { background-color: transparent !important; }
</style>
