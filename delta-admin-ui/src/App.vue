<template>
  <div v-if="progressVisible" class="app-loading" role="status" aria-live="polite">
    <span class="app-loading__bar" aria-hidden="true" />
    <span class="sr-only">{{ loadingFeedback.destination ? '正在切换页面' : '正在加载数据' }}</span>
  </div>
  <div v-if="loadingFeedback.routeError" class="app-route-error" role="alert">
    <span>页面加载失败，请检查网络后重试。</span>
    <el-button size="small" type="primary" @click="retryNavigation">重试加载</el-button>
    <el-button size="small" @click="reloadDestination">刷新应用</el-button>
    <el-button size="small" text @click="loadingFeedback.routeError = null">关闭</el-button>
  </div>
  <router-view />
  <div v-if="siteStore.error" class="site-config-error" role="status">
    <span>{{ siteStore.error }}</span>
    <el-button text size="small" :loading="siteStore.loading" @click="siteStore.fetchSiteConfig(true)">重试</el-button>
  </div>
</template>

<script setup>
import { computed, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { loadingFeedback } from '@/utils/loading-feedback'
import { useSiteStore } from '@/stores/site'
const siteStore = useSiteStore()
const route = useRoute()
const router = useRouter()
const progressVisible = ref(false)
const busy = computed(() => Boolean(loadingFeedback.destination) || loadingFeedback.requests > 0)
let progressTimer
watch(busy, value => {
  clearTimeout(progressTimer)
  if (value) progressTimer = setTimeout(() => { progressVisible.value = true }, 160)
  else progressVisible.value = false
}, { immediate: true })
watch(() => [route.meta.title, siteStore.adminTitle], ([title, brand]) => {
  document.title = title ? `${title} - ${brand}` : brand
}, { immediate: true })
onUnmounted(() => clearTimeout(progressTimer))

function retryNavigation() {
  const target = loadingFeedback.routeError?.path
  if (target) router.replace(target).catch(() => {})
}
function reloadDestination() {
  window.location.assign(router.resolve(loadingFeedback.routeError?.path || route.fullPath).href)
}
siteStore.fetchSiteConfig()
</script>
