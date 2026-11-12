<template>
  <view class="display-page">
    <view class="tabs">
      <view class="tab" :class="{ active: tab === 'rank' }" @click="tab = 'rank'">消费排行</view>
      <view class="tab" :class="{ active: tab === 'feed' }" @click="tab = 'feed'">最近购买</view>
    </view>
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!items.length" class="empty">{{ tab === 'rank' ? '本月暂无消费排行' : '最近暂无购买动态' }}</view>
    <view v-else class="list">
      <view v-for="(item, index) in items" :key="index" class="row">
        <text v-if="tab === 'rank'" class="rank">{{ index + 1 }}</text>
        <AppImage class="avatar" :src="item.avatar || ''" mode="aspectFill" />
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
import { computed, onMounted, ref, watch } from 'vue'
import AppImage from '@/components/AppImage.vue'
import { getPurchaseFeed, getSpendRanking } from '@/api/display'

const tab = ref('rank')
const loading = ref(false)
const rankItems = ref([])
const feedItems = ref([])
const items = computed(() => tab.value === 'rank' ? rankItems.value : feedItems.value)

async function load() {
  loading.value = true
  try {
    const [rank, feed] = await Promise.all([getSpendRanking(), getPurchaseFeed()])
    rankItems.value = Array.isArray(rank.data?.items) ? rank.data.items : []
    feedItems.value = Array.isArray(feed.data?.items) ? feed.data.items : []
  } catch (_) {
    rankItems.value = []
    feedItems.value = []
  } finally {
    loading.value = false
  }
}

watch(tab, load)
onMounted(load)
</script>

<style lang="scss" scoped>
.display-page { min-height: var(--app-page-height, 100vh); padding: 28rpx; background: #f7f8f9; box-sizing: border-box; }
.tabs { display: flex; padding: 8rpx; border-radius: 16rpx; background: #eceef0; margin-bottom: 24rpx; }
.tab { flex: 1; padding: 20rpx; text-align: center; border-radius: 12rpx; color: #73777e; font-size: 26rpx; }
.tab.active { background: #fff; color: #17191c; font-weight: 700; box-shadow: 0 4rpx 12rpx rgba(23,25,28,.06); }
.list { display: grid; gap: 16rpx; }
.row { display: flex; align-items: center; gap: 18rpx; padding: 24rpx; border-radius: 18rpx; background: #fff; }
.rank { width: 44rpx; text-align: center; font-weight: 700; color: #73777e; }
.avatar { width: 72rpx; height: 72rpx; border-radius: 50%; background: #eceef0; flex-shrink: 0; }
.content { flex: 1; min-width: 0; display: grid; gap: 6rpx; }
.name { font-size: 28rpx; color: #17191c; font-weight: 600; }
.meta { font-size: 23rpx; color: #73777e; }
.empty { padding: 140rpx 0; text-align: center; color: #73777e; font-size: 25rpx; }
</style>
