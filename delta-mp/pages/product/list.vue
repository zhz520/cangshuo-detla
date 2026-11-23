<template>
  <view class="product-list">
    <ScreenHeader class="product-list-header" title="商品列表" back center />

    <view v-if="designatedPlayer" class="designation-note">正在为 {{ designatedPlayer.nickname }} 点单，请选择服务</view>

    <view class="search-bar">
      <image class="search-icon" src="/static/icons/header-search.svg" mode="aspectFit" />
      <input
        v-model="keyword"
        class="search-input"
        placeholder="搜索商品"
        placeholder-class="search-placeholder"
        confirm-type="search"
        @confirm="doSearch"
        :focus="autoFocus"
      />
      <view v-if="keyword" class="search-clear" role="button" aria-label="清空搜索" @click="clearSearch">×</view>
    </view>

    <view class="sort-bar"><ProductSortPicker v-model="currentSort" @update:model-value="refresh" /></view>

    <scroll-view data-delta-content="page" scroll-y class="list" @scrolltolower="loadMore">
      <view v-if="products.length" class="product-grid">
        <ProductCard v-for="p in products" :key="p.id" :product="p" :designated-player-id="designatedPlayerId" :cover-height="460" cover-mode="aspectFit" />
      </view>
      <PageState v-if="loading || error" :phase="phase" :loading="loading" :error="error && products.length ? '仍显示上次结果。' + error : error" @retry="retry" />
      <view v-else-if="!loading && !products.length" class="empty-box">
        <image class="empty-icon" src="/static/icons/svc-sold.svg" mode="aspectFit" />
        <text class="empty-title">没有找到相关服务</text>
        <text class="empty-desc">换个关键词或看看其他分类</text>
      </view>
      <view v-if="!error && !loading && finished && products.length" class="loading-more">没有更多了</view>
    </scroll-view>
  </view>
</template>
<script setup>
import PageState from '@/components/PageState.vue'
import { usePagedList } from '@/composables/usePagedList'
import { ref } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import ProductCard from '@/components/ProductCard.vue'
import ProductSortPicker from '@/components/ProductSortPicker.vue'
import ScreenHeader from '@/components/ScreenHeader.vue'
import { productSortParams } from '@/utils/product-sort'
import { getProductList } from '@/api/product'
import { getPublicPlayer } from '@/api/player'

const designatedPlayerId = ref('')
const designatedPlayer = ref(null)
const keyword = ref('')
const categoryId = ref('')
const parentCategoryId = ref('')
const autoFocus = ref(false)
const currentSort = ref('default')
const { items: products, query, phase, loading, finished, error, retry, setQuery, refresh: reload, loadMore, dispose } = usePagedList({
  retainOnQueryChange: true,
  async fetchPage({ pageNum, pageSize, query }) {
    const res = await getProductList({ ...JSON.parse(query), pageNum, pageSize }, { silent: true })
    const data = res.data
    return { records: data?.records || data?.rows, total: data?.total }
  }
})
onUnload(dispose)

onLoad((opts) => {
  if (opts.playerId) {
    designatedPlayerId.value = opts.playerId
    getPublicPlayer(opts.playerId).then(res => designatedPlayer.value = res.data).catch(() => {})
  }
  if (opts.categoryId) categoryId.value = opts.categoryId
  if (opts.parentCategoryId) parentCategoryId.value = opts.parentCategoryId
  if (opts.keyword) keyword.value = opts.keyword
  if (opts.focus) autoFocus.value = true
  refresh()
})

function doSearch() { refresh() }
function clearSearch() { keyword.value = ''; refresh() }
function refresh() {
  const params = { ...productSortParams(currentSort.value) }
  if (keyword.value) params.keyword = keyword.value
  if (categoryId.value) params.categoryId = categoryId.value
  if (parentCategoryId.value) params.parentCategoryId = parentCategoryId.value
  const key = JSON.stringify(params)
  return key === query.value ? reload() : setQuery(key)
}
</script>
<style lang="scss" scoped>
.product-list {
  height: var(--app-page-height, 100vh);
  display: flex;
  flex-direction: column;
  background: var(--store-soft);
  color: var(--store-text);
  overflow: hidden;
}
.product-list-header {
  position: relative;
  flex: 0 0 auto;
  background: var(--store-surface);
}
.designation-note {
  margin: 16rpx 28rpx 0;
  padding: 18rpx 22rpx;
  border-radius: 18rpx;
  background: var(--store-surface);
  color: #555555;
  font-size: 23rpx;
}
.search-bar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 14rpx;
  height: 84rpx;
  margin: 18rpx 28rpx 0;
  padding: 0 24rpx;
  border-radius: 999rpx;
  background: var(--store-surface);
  box-sizing: border-box;
}
.search-icon { width: 38rpx; height: 38rpx; flex-shrink: 0; opacity: .78; }
.search-input {
  flex: 1;
  min-width: 0;
  height: 100%;
  padding: 0;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--store-text);
  font-size: 26rpx;
  line-height: 1;
}
.search-clear {
  width: 42rpx;
  height: 42rpx;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--store-muted);
  font-size: 34rpx;
  line-height: 1;
}
.sort-bar {
  flex: 0 0 auto;
  margin: 14rpx 28rpx 0;
  padding: 0;
  background: transparent;
  box-shadow: none;
}
.sort-bar :deep(.product-sort-picker) { width: 100%; }
.sort-bar :deep(.sort-label) {
  width: 100%;
  box-sizing: border-box;
  padding: 17rpx 24rpx;
  border: 0;
  border-radius: 18rpx;
  background: var(--store-surface);
  color: var(--store-text);
  font-size: 24rpx;
}
.sort-bar :deep(.sort-chevron) { color: var(--store-muted); }
.list {
  flex: 1;
  height: 0;
  min-height: 0;
  padding: 18rpx 28rpx calc(32rpx + var(--app-safe-bottom, env(safe-area-inset-bottom)));
  box-sizing: border-box;
}
.product-grid { display: flex; flex-direction: column; gap: 20rpx; }
.empty-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 100rpx 24rpx;
  color: var(--store-muted);
  font-size: 23rpx;
}

.empty-icon { width: 100rpx; height: 100rpx; opacity: .45; margin-bottom: 18rpx; }
.empty-title { color: var(--store-text); font-size: 28rpx; font-weight: 800; }
.empty-desc { margin-top: 8rpx; color: var(--store-muted); font-size: 22rpx; }
.loading-more { padding: 28rpx; color: var(--store-muted); font-size: 22rpx; text-align: center; }
</style>
