<template>
  <view class="category-page tab-page">
    <ScreenHeader title="分类">
      <view class="screen-icon-btn" role="button" aria-label="搜索商品" @click="goSearch">
        <image src="/static/icons/header-search.svg" mode="aspectFit" />
      </view>
    </ScreenHeader>
    <view class="category-body" :class="{ 'neo-body': isNeo }">
      <!-- 左侧父级分类 -->
      <scroll-view v-if="!isNeo" scroll-y class="side" :show-scrollbar="false">
        <view
          v-for="c in parentCategories"
          :key="c.id"
          class="side-item"
          :class="{ active: selectedParentId === c.id }"
          @click="selectParent(c)"
        >
          <text class="side-text">{{ c.name }}</text>
        </view>
      </scroll-view>
      <!-- 右侧内容 -->
      <scroll-view scroll-y class="main" :show-scrollbar="false" @scrolltolower="loadMoreProducts">
        <view v-if="isNeo" class="neo-category-head">
          <text class="neo-kicker">商品分类</text>
          <text class="neo-sub">按你的目标挑选合适的服务</text>
          <view class="neo-tile-grid">
            <view
              v-for="c in parentCategories"
              :key="'neo-' + c.id"
              class="neo-tile"
              :class="{ active: selectedParentId === c.id }"
              @click="selectParent(c)"
            >
              <view class="neo-tile-mark">{{ (c.name || '').slice(0, 1) }}</view>
              <text class="neo-tile-name">{{ c.name }}</text>
              <text class="neo-tile-desc">{{ (c.children || []).length }} 项服务</text>
            </view>
          </view>
        </view>
        <!-- 子分类标签 -->
        <scroll-view v-if="currentChildren.length" scroll-x class="children-wrap" :show-scrollbar="false">
          <view class="children-inner">
            <view
              class="child-tag"
              :class="{ active: selectedChildId === 0 }"
              @click="selectChild(0)"
            >全部</view>
            <view
              v-for="child in currentChildren"
              :key="child.id"
              class="child-tag"
              :class="{ active: selectedChildId === child.id }"
              @click="selectChild(child.id)"
            >
              {{ child.name }}
            </view>
          </view>
        </scroll-view>
        <view class="category-sort"><ProductSortPicker v-model="currentSort" @update:model-value="refreshProducts" /></view>
        <!-- 商品列表（横向卡片） -->
        <view class="product-section">
          <view
            v-for="p in products" :key="p.id"
            class="product-row"
            @click="goProductDetail(p.id)"
          >
            <AppImage class="product-cover" :src="p.coverImage" :width="320" mode="aspectFit" fallback="/static/icons/图片.svg" />
            <view class="product-info">
              <text class="product-name">{{ p.name }}</text>
              <text v-if="p.subtitle" class="product-subtitle">{{ p.subtitle }}</text>
              <view class="product-bottom">
                <text class="product-price">¥{{ Number(p.price || 0).toFixed(2) }}</text>
              </view>
            </view>
            <view class="row-arrow" aria-hidden="true"><image src="/static/icons/arrow-right.svg" mode="aspectFit" /></view>
          </view>
          <view v-if="productLoading" class="loading-tip">加载中...</view>
          <view v-if="!productLoading && products.length === 0" class="empty-tip">暂无商品</view>
          <view v-if="productFinished && products.length > 0" class="loading-tip">没有更多了</view>
        </view>
      </scroll-view>
    </view>
    <CustomTabBar :current="1" />
  </view>
</template>

<script setup>
import { clientStorage } from '@/utils/client-storage'
import AppImage from '@/components/AppImage.vue'
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import CustomTabBar from '@/components/CustomTabBar.vue'
import ScreenHeader from '@/components/ScreenHeader.vue'
import ProductSortPicker from '@/components/ProductSortPicker.vue'
import { useSiteStore } from '@/store/site'
import { productSortParams } from '@/utils/product-sort'
import { getCategoryTree } from '@/api/category'
import { getProductList } from '@/api/product'
const categoryTree = ref([])
const siteStore = useSiteStore()
const isNeo = computed(() => siteStore.themeId === 'neumorphism')
const selectedParentId = ref(0)
const selectedChildId = ref(0)
const loading = ref(false)

const products = ref([])
const productLoading = ref(false)
const productFinished = ref(false)
const productPageNum = ref(1)
const currentSort = ref('default')
let productGeneration = 0

const parentCategories = computed(() => categoryTree.value)

const currentChildren = computed(() => {
  const parent = categoryTree.value.find((c) => c.id === selectedParentId.value)
  return parent?.children || []
})

const queryCategoryId = computed(() => {
  if (selectedChildId.value) return selectedChildId.value
  return null
})

const queryParentCategoryId = computed(() => {
  if (!selectedChildId.value && selectedParentId.value) return selectedParentId.value
  return null
})

const inited = ref(false)

onShow(async () => {
  const storedId = clientStorage.get('selectedCategoryId')
  if (storedId) clientStorage.remove('selectedCategoryId')

  if (!inited.value) {
    loading.value = true
    try {
      const res = await getCategoryTree()
      const tree = res.data || []
      categoryTree.value = tree
      if (storedId) {
        applyStoredId(tree, Number(storedId))
      } else if (tree.length > 0) {
        selectedParentId.value = tree[0].id
      }
    } catch (e) {
      categoryTree.value = []
    }
    loading.value = false
    inited.value = true
    refreshProducts()
  } else if (storedId) {
    applyStoredId(categoryTree.value, Number(storedId))
    refreshProducts()
  }
})

function applyStoredId(tree, id) {
  const isParent = tree.some(c => c.id === id)
  if (isParent) {
    selectedParentId.value = id
    selectedChildId.value = 0
  } else {
    for (const p of tree) {
      const child = (p.children || []).find(c => c.id === id)
      if (child) {
        selectedParentId.value = p.id
        selectedChildId.value = child.id
        break
      }
    }
  }
}

function selectParent(c) {
  selectedParentId.value = c.id
  selectedChildId.value = 0
  refreshProducts()
}

function selectChild(childId) {
  selectedChildId.value = childId
  refreshProducts()
}

function refreshProducts() {
  productGeneration++
  productLoading.value = false
  productPageNum.value = 1
  products.value = []
  productFinished.value = false
  loadProducts()
}

async function loadProducts() {
  if (productLoading.value) return
  productLoading.value = true
  const generation = productGeneration, page = productPageNum.value
  try {
    const params = { pageNum: page, pageSize: 10, ...productSortParams(currentSort.value) }
    if (queryCategoryId.value) {
      params.categoryId = queryCategoryId.value
    } else if (queryParentCategoryId.value) {
      params.parentCategoryId = queryParentCategoryId.value
    }
    const res = await getProductList(params)
    if (generation !== productGeneration) return
    const list = res.data?.records || res.data?.rows || []
    if (list.length < 10) productFinished.value = true
    products.value = page === 1 ? list : [...products.value, ...list]
  } catch (e) {
    if (generation === productGeneration && page > 1) productPageNum.value = page - 1
  } finally { if (generation === productGeneration) productLoading.value = false }
}

function goProductDetail(id) {
  uni.navigateTo({ url: `/pages/product/detail?id=${id}` })
}

function goSearch() {
  uni.navigateTo({ url: '/pages/product/list?focus=1' })
}

function loadMoreProducts() {
  if (!productLoading.value && !productFinished.value) {
    productPageNum.value++
    loadProducts()
  }
}
</script>

<style lang="scss" scoped>
.category-page {
  height: var(--app-page-height, 100vh);
  min-height: var(--app-page-height, 100vh);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--store-soft);
}

.category-body {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
}

.side {
  width: 176rpx;
  height: 100%;
  flex-shrink: 0;
  box-sizing: border-box;
  overflow: hidden;
  padding-top: 10rpx;
  background: transparent;
}

.side-item {
  position: relative;
  display: flex;
  align-items: center;
  min-height: 96rpx;
  padding: 18rpx 12rpx 18rpx 24rpx;
  box-sizing: border-box;
  color: #7d8289;
  font-size: 24rpx;
  line-height: 1.35;

  .side-text {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &.active {
    background: var(--store-surface);
    color: var(--store-text);
    font-weight: 800;
  }
}

.main {
  flex: 1;
  min-width: 0;
  height: 100%;
  padding: 4rpx 22rpx 0 16rpx;
  box-sizing: border-box;
  overflow: hidden;
}

/* #ifdef H5 */
.side :deep(.uni-scroll-view::-webkit-scrollbar),
.side :deep(.uni-scroll-view-content::-webkit-scrollbar),
.main :deep(.uni-scroll-view::-webkit-scrollbar),
.main :deep(.uni-scroll-view-content::-webkit-scrollbar) {
  width: 0;
  height: 0;
  display: none;
}
/* #endif */

.children-wrap {
  height: 64rpx;
  margin-bottom: 16rpx;
  white-space: nowrap;
}
.children-inner {
  display: inline-flex;
  gap: 12rpx;
  padding-right: 16rpx;
}
.child-tag {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  padding: 13rpx 24rpx;
  border: 1rpx solid var(--store-line);
  border-radius: 999rpx;
  background: var(--store-surface);
  color: #6d7279;
  font-size: 22rpx;
  white-space: nowrap;
  box-sizing: border-box;

  &.active {
    border-color: var(--store-text);
    background: var(--store-primary);
    color: #ffffff;
    font-weight: 750;
  }
}

.category-sort {
  display: block;
  margin-bottom: 16rpx;
  :deep(.product-sort-picker) { width: 100%; }
  :deep(.sort-label) {
    justify-content: space-between;
    gap: 10rpx;
    padding: 17rpx 24rpx;
    border: 0;
    border-radius: 18rpx;
    background: var(--store-surface);
    color: var(--store-text);
    font-size: 24rpx;
  }
}

.product-section { min-height: 200rpx; overflow: hidden; }
.product-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx;
  margin-bottom: 16rpx;
  border: 0;
  border-radius: 24rpx;
  background: var(--store-surface);
  box-shadow: none;
  overflow: hidden;
}
.product-cover {
  width: 210rpx;
  height: 140rpx;
  flex-shrink: 0;
  border-radius: 18rpx;
  background: var(--store-line);
}
.product-info {
  flex: 1;
  min-width: 0;
  align-self: stretch;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 7rpx;
  overflow: hidden;
}
.product-name {
  display: -webkit-box;
  overflow: hidden;
  color: var(--store-text);
  font-size: 28rpx;
  font-weight: 800;
  line-height: 1.35;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.product-subtitle {
  display: block;
  width: 100%;
  overflow: hidden;
  color: var(--store-regular);
  font-size: 22rpx;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.product-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 2rpx;
}
.product-price {
  color: var(--store-text);
  font-size: 34rpx;
  font-weight: 850;
  font-variant-numeric: tabular-nums;
}
.row-arrow {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44rpx;
  height: 44rpx;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--store-soft);
  image { width: 24rpx; height: 24rpx; }
}
.loading-tip {
  padding: 30rpx;
  color: var(--store-regular);
  font-size: 23rpx;
  text-align: center;
}
.empty-tip {
  width: 100%;
  padding: 58rpx 20rpx;
  color: var(--store-regular);
  font-size: 24rpx;
  text-align: center;
}
</style>
