<template>
  <view class="library-page">
    <view class="library-head">
      <view class="library-tabs">
        <view class="library-tab" :class="{ active: activeTab === 'favorites' }" @click="switchTab('favorites')">收藏商品</view>
        <view class="library-tab" :class="{ active: activeTab === 'follows' }" @click="switchTab('follows')">关注打手</view>
      </view>
    </view>
    <scroll-view scroll-y class="library-list" :show-scrollbar="false" @scrolltolower="loadMore">
      <PageState :loading="loading && !rows.length" :error="error" :empty="loaded && !rows.length" :empty-text="activeTab === 'favorites' ? '还没有收藏商品' : '还没有关注打手'" @retry="load">
        <template v-if="activeTab === 'favorites'">
          <view v-for="item in rows" :key="item.id" class="library-card" @click="openFavorite(item)">
            <AppImage class="library-cover" :width="200" :src="item.coverImage || '/static/images/delta-default-avatar.png'" mode="aspectFill" />
            <view class="library-info">
              <text class="library-name">{{ item.name }}</text>
              <text v-if="item.subtitle" class="library-sub">{{ item.subtitle }}</text>
              <view class="library-meta"><text class="library-price">¥{{ Number(item.price || 0).toFixed(2) }}</text><text class="library-status">{{ item.status === 1 ? '可下单' : '已下架' }}</text></view>
            </view>
            <text class="remove-link" @click.stop="removeFavoriteItem(item)">取消</text>
          </view>
        </template>
        <template v-else>
          <view v-for="item in rows" :key="item.id" class="library-card" @click="openPlayer(item)">
            <AppImage class="library-cover round" :width="160" :src="item.avatar || '/static/images/delta-default-avatar.png'" mode="aspectFill" />
            <view class="library-info">
              <view class="library-title-row"><text class="library-name">{{ item.nickname || '打手' }}</text><text class="online-dot" :class="{ online: item.isOnline === 1 }">{{ item.isOnline === 1 ? '在线' : '离线' }}</text></view>
              <text class="library-sub">{{ item.levelName || ('Lv.' + (item.level || 1)) }} · 完成 {{ item.orderCount || 0 }} 单</text>
              <text v-if="item.introduction" class="library-sub intro">{{ item.introduction }}</text>
            </view>
            <text class="remove-link" @click.stop="unfollowItem(item)">取消</text>
          </view>
        </template>
      </PageState>
      <view v-if="rows.length && loading" class="load-tip">加载中...</view>
      <view v-else-if="rows.length && finished" class="load-tip">已显示全部</view>
      <view class="list-bottom-space" />
    </scroll-view>
  </view>
</template>

<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import AppImage from '@/components/AppImage.vue'
import PageState from '@/components/PageState.vue'
import { getFavorites, getFollows, removeFavorite, unfollowPlayer } from '@/api/user'
import { useUserStore } from '@/store/user'

const userStore = useUserStore()
const activeTab = ref('favorites')
const rows = ref([])
const loading = ref(false), loaded = ref(false), finished = ref(false), error = ref('')
let pageNum = 1, loadToken = 0, activeRequest = null

function switchTab(tab) {
  if (activeTab.value === tab) return
  activeTab.value = tab
  rows.value = []
  loaded.value = false
  pageNum = 1
  finished.value = false
  load()
}

async function load() {
  if (activeRequest) return activeRequest
  if (!userStore.isLoggedIn) {
    loaded.value = true
    error.value = '登录后可查看收藏和关注'
    return
  }
  const token = ++loadToken
  loading.value = true
  error.value = ''
  const request = (async () => {
    try {
      const params = { pageNum, pageSize: 20 }
      const response = activeTab.value === 'favorites' ? await getFavorites(params) : await getFollows(params)
      if (token !== loadToken) return
      const page = response.data || {}
      const records = page.records || []
      rows.value = pageNum === 1 ? records : rows.value.concat(records)
      finished.value = records.length < 20 || rows.value.length >= Number(page.total || 0)
      loaded.value = true
    } catch (e) {
      if (token === loadToken) error.value = e?.msg || e?.message || '加载失败，请重试'
    } finally {
      if (token === loadToken) { loading.value = false; activeRequest = null }
    }
  })()
  activeRequest = request
  return request
}

function loadMore() {
  if (loading.value || finished.value) return
  pageNum += 1
  load()
}
function openFavorite(item) {
  if (item.status !== 1) return uni.showToast({ title: '商品已下架', icon: 'none' })
  uni.navigateTo({ url: '/pages/product/detail?id=' + item.id })
}
function openPlayer(item) { uni.navigateTo({ url: '/pages/players/detail?id=' + item.id }) }
async function removeFavoriteItem(item) {
  if (!await confirmRemove('取消收藏该商品？')) return
  await removeFavorite(item.id)
  rows.value = rows.value.filter(row => row.id !== item.id)
  uni.showToast({ title: '已取消收藏', icon: 'none' })
}
async function unfollowItem(item) {
  if (!await confirmRemove('取消关注该打手？')) return
  await unfollowPlayer(item.id)
  rows.value = rows.value.filter(row => row.id !== item.id)
  uni.showToast({ title: '已取消关注', icon: 'none' })
}
async function confirmRemove(content) {
  return new Promise(resolve => uni.showModal({ title: '提示', content, success: result => resolve(result.confirm), fail: () => resolve(false) }))
}
onShow(() => { if (!activeRequest) load() })
</script>

<style lang="scss" scoped>
.library-page { min-height: var(--app-page-height, 100vh); background: #f7f8fa; }
.library-head { padding: 20rpx 24rpx 12rpx; background: #fff; }
.library-tabs { display: flex; gap: 12rpx; padding: 6rpx; background: #f3f5f7; border-radius: 14rpx; }
.library-tab { flex: 1; padding: 18rpx 0; border-radius: 10rpx; text-align: center; font-size: 27rpx; color: #777777; }
.library-tab.active { background: #111111; color: #fff; font-weight: 700; }
.library-list { height: calc(var(--app-page-height, 100vh) - 104rpx); box-sizing: border-box; padding: 20rpx 24rpx; }
.library-card { display: flex; align-items: center; gap: 20rpx; padding: 22rpx; margin-bottom: 18rpx; border: 1rpx solid #edf0f2; border-radius: 18rpx; background: #fff; }
.library-cover { width: 128rpx; height: 128rpx; border-radius: 14rpx; flex: 0 0 auto; background: #f7f7f7; }
.library-cover.round { width: 108rpx; height: 108rpx; border-radius: 50%; }
.library-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8rpx; }
.library-title-row { display: flex; align-items: center; gap: 12rpx; }
.library-name { font-size: 29rpx; font-weight: 700; color: #111111; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.library-sub { font-size: 23rpx; color: #7b879a; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.library-sub.intro { max-width: 100%; }
.library-meta { display: flex; align-items: center; gap: 16rpx; }
.library-price { font-size: 28rpx; color: #111111; font-weight: 700; }
.library-status { font-size: 21rpx; color: #7b879a; }
.online-dot { font-size: 20rpx; color: #8b919b; }
.online-dot.online { color: #347a5b; }
.remove-link { flex: 0 0 auto; padding: 12rpx 8rpx; color: #a04b55; font-size: 23rpx; }
.load-tip { padding: 24rpx; text-align: center; color: #8b919b; font-size: 23rpx; }
.list-bottom-space { height: calc(80rpx + var(--app-safe-bottom, env(safe-area-inset-bottom))); }
</style>
