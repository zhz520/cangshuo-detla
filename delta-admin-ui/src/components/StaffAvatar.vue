<template>
  <el-avatar :size="size" :src="failed ? undefined : avatarSrc" class="staff-avatar" @error="failed = true">{{ brandInitial }}</el-avatar>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useUserStore } from '@/stores/user'
import { useSiteStore } from '@/stores/site'
import { thumbnailUrl } from '@/utils/image'

const props = defineProps({
  size: { type: Number, default: 36 },
  thumbnailWidth: { type: Number, default: 0 }
})
const userStore = useUserStore()
const siteStore = useSiteStore()
const failed = ref(false)
const source = computed(() => userStore.avatar || siteStore.logo || '')
const avatarSrc = computed(() => source.value ? thumbnailUrl(source.value, props.thumbnailWidth || props.size * 2) : '')
const brandInitial = computed(() => String(siteStore.siteName || siteStore.adminTitle || '品牌').trim().slice(0, 1) || '品')
watch(avatarSrc, () => { failed.value = false })
</script>

<style scoped>
.staff-avatar { flex-shrink: 0; color: #fff; background: linear-gradient(145deg, #30343a, #121417); font-weight: 800; }
</style>
