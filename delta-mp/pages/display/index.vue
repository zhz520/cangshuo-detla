<template>
  <view class="display-page">
    <view class="tabs">
      <view class="tab" :class="{ active: tab === 'rank' }" @click="tab = 'rank'">消费排行</view>
      <view class="tab" :class="{ active: tab === 'feed' }" @click="tab = 'feed'">最近购买</view>
    </view>
    <view v-if="userStore.isLoggedIn" class="privacy-card">
      <text class="privacy-title">展示设置</text>
      <view class="privacy-row">
        <view class="privacy-copy"><text>在消费排行中展示我</text><text class="privacy-hint">仅显示脱敏昵称和本月净消费</text></view>
        <switch :checked="privacy.spendRankVisible" color="#111111" @change="changePrivacy('spendRankVisible', $event)" />
      </view>
      <view class="privacy-row">
        <view class="privacy-copy"><text>允许出现在最近购买动态</text><text class="privacy-hint">仅展示脱敏昵称、默认头像和商品名称</text></view>
        <switch :checked="privacy.purchaseFeedConsent" color="#111111" @change="changePrivacy('purchaseFeedConsent', $event)" />
      </view>
    </view>
    <PageState v-if="loading || loadError || !items.length" :loading="loading" :error="loadError"
      :empty="!items.length" :empty-text="tab === 'rank' ? '本月暂无消费排行' : '最近暂无购买动态'" @retry="load" />
    <view v-else class="list">
      <view v-for="(item, index) in items" :key="index" class="row">
        <text v-if="tab === 'rank'" class="rank">{{ index + 1 }}</text>
        <AppImage class="avatar" :src="tab === 'rank' ? item.avatar : '/static/images/delta-default-avatar.png'" mode="aspectFill" />
        <view class="content">
          <text class="name">{{ item.nickname || '匿名玩家' }}</text>
          <text v-if="tab === 'rank'" class="meta">本月净消费 ¥{{ Number(item.spend || 0).toFixed(2) }}</text>
          <text v-else class="meta">购买了 {{ item.product_name || '服务商品' }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import AppImage from '@/components/AppImage.vue'
import PageState from '@/components/PageState.vue'
import { getDisplayPrivacy, getPurchaseFeed, getSpendRanking, updateDisplayPrivacy } from '@/api/display'
import { useUserStore } from '@/store/user'

const tab = ref('rank')
const loading = ref(false)
const loadError = ref('')
let revision = 0
const rankItems = ref([])
const feedItems = ref([])
const userStore = useUserStore()
const privacy = reactive({ spendRankVisible: true, purchaseFeedConsent: false })
const items = computed(() => tab.value === 'rank' ? rankItems.value : feedItems.value)
let ready = false

async function load() {
  const current = ++revision
  const kind = tab.value
  const target = kind === 'rank' ? rankItems : feedItems
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await (kind === 'rank' ? getSpendRanking() : getPurchaseFeed())
    if (revision === current) target.value = Array.isArray(data?.items) ? data.items : []
  } catch (_) {
    if (revision === current) loadError.value = '暂时无法读取展示记录'
  } finally {
    if (revision === current) loading.value = false
  }
}

async function loadPrivacy() {
  if (!userStore.isLoggedIn) return
  try {
    const { data } = await getDisplayPrivacy()
    privacy.spendRankVisible = data?.spendRankVisible !== false
    privacy.purchaseFeedConsent = data?.purchaseFeedConsent === true
  } catch (_) {}
}

async function changePrivacy(key, event) {
  const next = Boolean(event?.detail?.value)
  const previous = privacy[key]
  privacy[key] = next
  try {
    await updateDisplayPrivacy({ [key]: next })
    uni.showToast({ title: '展示设置已保存', icon: 'none' })
    await load()
  } catch (_) {
    privacy[key] = previous
  }
}

onLoad(options => {
  if (options?.tab === 'feed') tab.value = 'feed'
})
watch(tab, () => { if (ready) load() })
onMounted(() => { ready = true; load(); loadPrivacy() })
</script>

<style lang="scss" scoped>
.display-page { min-height: var(--app-page-height, 100vh); padding: 28rpx; background: var(--store-soft); box-sizing: border-box; }
.tabs { display: flex; padding: 8rpx; border-radius: 16rpx; background: var(--store-soft); margin-bottom: 24rpx; }
.tab { flex: 1; padding: 20rpx; text-align: center; border-radius: 12rpx; color: var(--store-regular); font-size: 26rpx; }
.tab.active { background: var(--store-surface); color: var(--store-text); font-weight: 700; box-shadow: 0 4rpx 12rpx rgba(23,25,28,.06); }
.privacy-card { display: grid; gap: 18rpx; margin-bottom: 24rpx; padding: 24rpx; border-radius: 18rpx; background: var(--store-surface); }
.privacy-title { font-size: 28rpx; font-weight: 700; color: var(--store-text); }
.privacy-row { display: flex; align-items: center; justify-content: space-between; gap: 18rpx; }
.privacy-copy { display: grid; gap: 6rpx; min-width: 0; }
.privacy-copy > text:first-child { font-size: 26rpx; color: var(--store-text); }
.privacy-hint { font-size: 22rpx; line-height: 1.5; color: var(--store-regular); }
.list { display: grid; gap: 16rpx; }
.row { display: flex; align-items: center; gap: 18rpx; padding: 24rpx; border-radius: 18rpx; background: var(--store-surface); }
.rank { width: 44rpx; text-align: center; font-weight: 700; color: var(--store-regular); }
.avatar { width: 72rpx; height: 72rpx; border-radius: 50%; background: var(--store-soft); flex-shrink: 0; }
.content { flex: 1; min-width: 0; display: grid; gap: 6rpx; }
.name { font-size: 28rpx; color: var(--store-text); font-weight: 600; }
.meta { font-size: 23rpx; color: var(--store-regular); }
.empty { padding: 140rpx 0; text-align: center; color: var(--store-regular); font-size: 25rpx; }
</style>
