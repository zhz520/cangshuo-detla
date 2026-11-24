<template>
  <view class="product-detail" v-if="product">
    <ScreenHeader class="detail-header" title="服务详情" back center>
      <view class="header-btn" role="button" aria-label="分享" @click="shareProduct">
        <image src="/static/icons/svc-share.svg" mode="aspectFit" />
      </view>
      <view class="header-btn" role="button" :aria-label="favorite ? '取消收藏' : '收藏'" @click="toggleFavorite">
        <image :src="favorite ? '/static/icons/svc-heart-filled.svg' : '/static/icons/svc-heart.svg'" mode="aspectFit" />
      </view>
    </ScreenHeader>

    <view data-delta-content="page" class="detail-body">
      <view class="banner-wrap">
        <swiper class="banner" :autoplay="!reducedMotion" :interval="4200" circular :current="swiperIdx" @change="e => swiperIdx = e.detail.current">
          <swiper-item v-for="(img, i) in images" :key="i">
            <AppImage :src="img" mode="aspectFit" fallback="/static/icons/图片.svg" class="banner-img" :data-shared-target="i === 0 ? 'product' : undefined" @click="previewImg(i)" />
          </swiper-item>
        </swiper>
        <view class="banner-dots" v-if="images.length > 1">
          <view v-for="(img, i) in images" :key="i" class="dot" :class="{ active: swiperIdx === i }" />
        </view>
        <view class="img-counter" v-if="images.length > 1">{{ swiperIdx + 1 }}/{{ images.length }}</view>
      </view>

      <view class="head-card">
        <view class="head-top">
          <view class="head-copy">
            <text class="product-name">{{ product.name }}</text>
            <text v-if="product.subtitle" class="product-subtitle">{{ product.subtitle }}</text>
            <text v-if="flashSale.active" class="flash-badge">限时秒杀 · 每账号限 1 单 · 剩余 {{ flashSale.remainingStock }} 份</text>
            <view class="rating-row" @click="openReviews">
              <text class="rating-star">★</text>
              <text class="rating-value">{{ ratingLabel }}</text>
              <text class="rating-divider">|</text>
              <text class="rating-count">{{ reviewCountLabel }}</text>
              <text v-if="reviewTotal" class="rating-arrow">›</text>
            </view>
          </view>
          <view class="head-price">
            <text class="price-yen">¥</text>
            <text class="price-int">{{ priceInt }}</text>
            <text class="price-dec">.{{ priceDec }}</text>
          </view>
        </view>
        <view v-if="limitLabel" class="limit-pill">{{ limitLabel }}</view>
      </view>

      <view class="info-card">
        <text class="card-title">服务信息</text>
        <view class="info-row">
          <image class="info-icon" src="/static/icons/svc-gamepad.svg" mode="aspectFit" />
          <text class="info-label">服务内容</text>
          <view class="info-line" />
          <text class="info-value">{{ descriptionText || '以本页服务说明为准' }}</text>
        </view>
        <view class="info-row" v-if="product.categoryName">
          <image class="info-icon" src="/static/icons/svc-tag.svg" mode="aspectFit" />
          <text class="info-label">服务分类</text>
          <view class="info-line" />
          <text class="info-value">{{ product.categoryName }}</text>
        </view>
        <view class="info-row">
          <image class="info-icon" src="/static/icons/svc-sold.svg" mode="aspectFit" />
          <text class="info-label">已售</text>
          <view class="info-line" />
          <text class="info-value">{{ Number(product.salesCount || 0) }} 单</text>
        </view>
        <view class="info-row">
          <image class="info-icon" src="/static/icons/svc-info.svg" mode="aspectFit" />
          <text class="info-label">限购规则</text>
          <view class="info-line" />
          <text class="info-value">{{ limitLabel || '不限购' }}</text>
        </view>
      </view>

      <view class="detail-card" v-if="detailHtml">
        <text class="card-title">详情与须知</text>
        <view class="rich-body"><rich-text :nodes="detailHtml" /></view>
      </view>

      <view class="review-card" id="reviews">
        <view class="review-head">
          <text class="card-title">评价<span v-if="reviewTotal" class="review-count"> ({{ reviewTotal }})</span></text>
          <text v-if="reviewTotal" class="review-more" @click="openReviews">查看全部 ›</text>
        </view>
        <view v-if="reviews.length" class="review-item">
          <AppImage class="review-avatar" :src="reviews[0].userAvatar || '/static/images/delta-default-avatar.png'" mode="aspectFill" />
          <view class="review-main">
            <view class="review-top">
              <text class="review-name">{{ reviews[0].userNickname || '匿名用户' }}</text>
              <text class="review-time">{{ formatReviewDate(reviews[0].createdAt) }}</text>
            </view>
            <text class="review-stars">{{ starText(reviews[0].rating) }}</text>
            <text class="review-text">{{ reviews[0].content }}</text>
            <view v-if="parseImages(reviews[0].images).length" class="review-images">
              <AppImage v-for="(img, i) in parseImages(reviews[0].images).slice(0, 3)" :key="i" class="review-image" :src="img" mode="aspectFill" @click="previewReviewImage(reviews[0], i)" />
            </view>
          </view>
        </view>
        <view v-else class="review-empty">暂无评价，完成订单后可发表评价</view>
      </view>

      <view class="bottom-space" />
    </view>

    <view class="bottom-bar">
      <view class="bar-action" role="button" aria-label="在线客服" @click="goChat">
        <image class="bar-icon" src="/static/icons/header-headset.svg" mode="aspectFit" />
        <text>客服</text>
      </view>
      <view class="bar-action" :class="{ active: favorite }" role="button" :aria-label="favorite ? '取消收藏' : '收藏'" @click="toggleFavorite">
        <image class="bar-icon" :src="favorite ? '/static/icons/svc-heart-filled.svg' : '/static/icons/svc-heart.svg'" mode="aspectFit" />
        <text>{{ favorite ? '已收藏' : '收藏' }}</text>
      </view>
      <view v-if="siteStore.orderPurchaseEnabled" class="buy-btn" role="button" @click="goBuy">立即购买</view>
      <view v-else-if="siteStore.orderContactPurchaseEnabled" class="buy-btn contact" role="button" @click="goChat">联系客服购买</view>
      <view v-else class="buy-btn disabled" role="button">购买已暂停</view>
    </view>

    <view v-if="showReviews" class="review-sheet-mask" @click="showReviews = false">
      <view class="review-sheet" @click.stop>
        <view class="sheet-head">
          <text class="sheet-title">全部评价<span v-if="reviewTotal"> ({{ reviewTotal }})</span></text>
          <text class="sheet-close" @click="showReviews = false">✕</text>
        </view>
        <scroll-view scroll-y class="sheet-body">
          <view v-for="review in reviews" :key="review.id" class="review-item">
            <AppImage class="review-avatar" :src="review.userAvatar || '/static/images/delta-default-avatar.png'" mode="aspectFill" />
            <view class="review-main">
              <view class="review-top">
                <text class="review-name">{{ review.userNickname || '匿名用户' }}</text>
                <text class="review-time">{{ formatReviewDate(review.createdAt) }}</text>
              </view>
              <text class="review-stars">{{ starText(review.rating) }}</text>
              <text class="review-text">{{ review.content }}</text>
              <view v-if="parseImages(review.images).length" class="review-images">
                <AppImage v-for="(img, i) in parseImages(review.images).slice(0, 3)" :key="i" class="review-image" :src="img" mode="aspectFill" @click="previewReviewImage(review, i)" />
              </view>
            </view>
          </view>
          <view v-if="!reviews.length" class="review-empty">暂无评价，完成订单后可发表评价</view>
        </scroll-view>
      </view>
    </view>
  </view>
</template>
<script setup>
import { useReducedMotion } from '@/composables/useReducedMotion'
const reducedMotion = useReducedMotion()
import AppImage from '@/components/AppImage.vue'
import ScreenHeader from '@/components/ScreenHeader.vue'
import { ref, computed } from 'vue'
import { onLoad, onShow, onShareAppMessage, onShareTimeline } from '@dcloudio/uni-app'
import { productDescriptionHtml } from '@/utils/product-content'
import { getProductRichDetail, getProductDetail, getFlashSale } from '@/api/product'
import { getReviewsByProduct } from '@/api/review'
import { openCustomerService } from '@/utils/customer-service'
import { useSiteStore } from '@/store/site'
import { useUserStore } from '@/store/user'
import { addFavorite, getFavoriteStatus, removeFavorite } from '@/api/user'

const siteStore = useSiteStore()
const userStore = useUserStore()
const designatedPlayerId = ref('')
const product = ref(null)
const flashSale = ref({ active: false })
const swiperIdx = ref(0)
const favorite = ref(false)
const favoriteBusy = ref(false)
const reviews = ref([])
const reviewTotal = ref(0)
const reviewLoading = ref(false)
const showReviews = ref(false)

onShareAppMessage(() => {
  const p = product.value
  if (!p) return { title: '服务', path: '/pages/index/index' }
  return { title: p.name + ' ¥' + Number(p.price || 0).toFixed(2), path: '/pages/product/detail?id=' + p.id, imageUrl: p.coverImage || p.image || '' }
})
onShareTimeline(() => {
  const p = product.value
  if (!p) return { title: '服务' }
  return { title: p.name + ' ¥' + Number(p.price || 0).toFixed(2), query: 'id=' + p.id, imageUrl: p.coverImage || p.image || '' }
})

const displayPrice = computed(() => flashSale.value.active ? flashSale.value.price : product.value?.price)
const priceInt = computed(() => Number(displayPrice.value || 0).toFixed(2).split('.')[0])
const priceDec = computed(() => Number(displayPrice.value || 0).toFixed(2).split('.')[1])
const ratingLabel = computed(() => Number(product.value?.avgRating || 5).toFixed(1))
const reviewCountLabel = computed(() => reviewTotal.value ? '(' + reviewTotal.value + '条评价)' : '暂无评价')
const limitLabel = computed(() => {
  const type = Number(product.value?.perUserLimitType || 0)
  const labels = { 1: '永久限购 1 次', 2: '7 天内限购 1 次', 3: '30 天内限购 1 次', 4: '24 小时内限购 1 次' }
  if (labels[type]) return labels[type]
  return product.value?.perUserLimitEnabled === 1 && product.value?.perUserLimitCount ? '限购 ' + product.value.perUserLimitCount + ' 次' : ''
})
const descriptionText = computed(() => String(product.value?.description || '').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim())
const detailHtml = computed(() => productDescriptionHtml(product.value?.detail || product.value?.description))
const images = computed(() => {
  if (!product.value) return []
  const raw = product.value.images
  if (!raw) return [product.value.coverImage || product.value.image]
  try {
    const arr = JSON.parse(raw)
    if (Array.isArray(arr)) return arr.filter(Boolean)
  } catch (_) {}
  return String(raw).split(',').filter(Boolean)
})

onLoad(async (opts) => {
  designatedPlayerId.value = opts.playerId || ''
  try {
    const res = await getProductRichDetail(opts.id)
    const d = res.data
    product.value = d.product || d
    if (d.categoryName) product.value.categoryName = d.categoryName
  } catch (_) {
    const basic = await getProductDetail(opts.id)
    product.value = basic.data
  }
  if (product.value?.id) {
    try { flashSale.value = (await getFlashSale(product.value.id)).data || { active: false } } catch { flashSale.value = { active: false } }
    await Promise.allSettled([syncFavoriteStatus(), loadReviews()])
  }
})
onShow(() => { if (product.value) syncFavoriteStatus() })

async function loadReviews() {
  if (!product.value?.id || reviewLoading.value) return
  reviewLoading.value = true
  try {
    const res = await getReviewsByProduct(product.value.id, { pageNum: 1, pageSize: 20 })
    reviews.value = res.data?.records || []
    reviewTotal.value = Number(res.data?.total ?? reviews.value.length ?? 0)
  } catch (_) {
    reviews.value = []
    reviewTotal.value = Number(product.value?.reviewCount || 0)
  } finally {
    reviewLoading.value = false
  }
}

async function syncFavoriteStatus() {
  if (!product.value?.id || !userStore.isLoggedIn) { favorite.value = false; return }
  try { favorite.value = Boolean((await getFavoriteStatus(product.value.id)).data?.favorited) } catch (_) {}
}
async function toggleFavorite() {
  if (!userStore.checkLogin() || favoriteBusy.value || !product.value?.id) return
  favoriteBusy.value = true
  try {
    if (favorite.value) await removeFavorite(product.value.id)
    else await addFavorite(product.value.id)
    favorite.value = !favorite.value
    uni.showToast({ title: favorite.value ? '已收藏' : '已取消收藏', icon: 'none' })
  } finally {
    favoriteBusy.value = false
  }
}
function previewImg(idx) { uni.previewImage({ urls: images.value, current: idx }) }
function parseImages(value) {
  if (!value) return []
  try { const arr = JSON.parse(value); if (Array.isArray(arr)) return arr.filter(Boolean) } catch (_) {}
  return String(value).split(',').map(item => item.trim()).filter(Boolean)
}
function previewReviewImage(review, idx) {
  const urls = parseImages(review.images)
  if (urls.length) uni.previewImage({ urls, current: idx })
}
function starText(rating) {
  const count = Math.max(0, Math.min(5, Math.round(Number(rating || 0))))
  return '★'.repeat(count) + '☆'.repeat(5 - count)
}
function formatReviewDate(value) {
  if (!value) return ''
  return String(value).slice(0, 10)
}
function openReviews() {
  if (!reviewTotal.value && !reviews.value.length) return
  showReviews.value = true
}
async function shareProduct() {
  // #ifdef H5
  const url = window.location.origin + window.location.pathname + '#/pages/product/detail?id=' + product.value.id
  uni.setClipboardData({ data: url, success: () => uni.showToast({ title: '链接已复制，可发送给好友', icon: 'none' }) })
  return
  // #endif
  uni.showToast({ title: '请使用右上角分享', icon: 'none' })
}
async function goChat() {
  try { await openCustomerService(product.value) }
  catch (e) { uni.showToast({ title: e?.msg || '连接客服失败', icon: 'none' }) }
}
function goBuy() {
  if (!siteStore.orderPurchaseEnabled) return uni.showToast({ title: '购买功能已暂停', icon: 'none' })
  const price = displayPrice.value || 0
  const name = encodeURIComponent(product.value.name)
  const player = flashSale.value.active ? '' : designatedPlayerId.value
  uni.navigateTo({ url: '/pages/order/create?productId=' + product.value.id + '&amount=' + price + '&productName=' + name + (player ? '&playerId=' + player : '') + (flashSale.value.active ? '&flashSale=1' : '') })
}
</script>
<style lang="scss" scoped>
.product-detail {
  min-height: var(--app-page-height, 100vh);
  background: var(--store-soft);
  color: var(--store-text);
  padding-bottom: calc(150rpx + var(--app-safe-bottom, env(safe-area-inset-bottom)));
}
.detail-header {
  position: sticky;
  top: 0;
  z-index: 30;
  background: var(--store-surface);
}
.header-btn {
  width: 64rpx;
  height: 64rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  image { display: block; width: 40rpx; height: 40rpx; }
}
.detail-body { padding: 8rpx 28rpx 0; }
.banner-wrap {
  position: relative;
  overflow: hidden;
  border-radius: 28rpx;
  background: #f1f2f3;
}
.banner { height: 460rpx; }
.banner-img { display: block; width: 100%; height: 460rpx; background: #f1f2f3; }
.banner-dots {
  position: absolute;
  bottom: 24rpx;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 12rpx;
  .dot { width: 12rpx; height: 12rpx; border-radius: 50%; background: rgba(255,255,255,.55); transition: all .25s; }
  .dot.active { width: 32rpx; border-radius: 6rpx; background: var(--store-surface); }
}
.img-counter {
  position: absolute;
  top: 22rpx;
  right: 22rpx;
  padding: 5rpx 16rpx;
  border-radius: 999rpx;
  background: rgba(0,0,0,.42);
  color: #ffffff;
  font-size: 21rpx;
}
.head-card {
  margin-top: 18rpx;
  padding: 28rpx 26rpx;
  border-radius: 26rpx;
  background: var(--store-surface);
}
.head-top { display: flex; align-items: flex-start; gap: 18rpx; }
.head-copy { flex: 1; min-width: 0; }
.product-name { display: block; color: var(--store-text); font-size: 40rpx; font-weight: 850; line-height: 1.28; letter-spacing: -0.02em; }
.product-subtitle { display: block; margin-top: 8rpx; color: var(--store-regular); font-size: 24rpx; line-height: 1.45; }
.flash-badge { display: inline-flex; margin-top: 12rpx; padding: 6rpx 14rpx; border-radius: 999rpx; background: var(--store-soft); color: var(--store-primary); font-size: 22rpx; font-weight: 700; }
.rating-row { display: flex; align-items: center; gap: 8rpx; margin-top: 16rpx; color: var(--store-regular); font-size: 22rpx; }
.rating-star { color: var(--store-text); font-size: 24rpx; }
.rating-value { color: var(--store-text); font-weight: 800; }
.rating-divider { color: #dddddd; }
.rating-count { color: var(--store-muted); }
.rating-arrow { color: var(--store-muted); font-size: 28rpx; line-height: 1; }
.head-price { flex-shrink: 0; display: flex; align-items: baseline; color: var(--store-text); padding-top: 4rpx; }
.price-yen { font-size: 28rpx; font-weight: 750; }
.price-int { font-size: 48rpx; font-weight: 850; line-height: 1; font-variant-numeric: tabular-nums; }
.price-dec { font-size: 28rpx; font-weight: 750; }
.limit-pill { display: inline-flex; margin-top: 18rpx; padding: 7rpx 16rpx; border-radius: 999rpx; background: var(--store-soft); color: var(--store-regular); font-size: 21rpx; }
.info-card, .detail-card, .review-card {
  margin-top: 18rpx;
  padding: 28rpx 26rpx;
  border-radius: 26rpx;
  background: var(--store-surface);
}
.card-title { display: block; color: var(--store-text); font-size: 31rpx; font-weight: 850; line-height: 1.3; }
.info-row {
  display: flex;
  align-items: flex-start;
  gap: 14rpx;
  padding: 22rpx 0;
  border-top: 1rpx solid #f3f3f3;
  &:first-of-type { margin-top: 12rpx; }
}
.info-icon { width: 38rpx; height: 38rpx; flex-shrink: 0; margin-top: 2rpx; opacity: .82; }
.info-label { width: 112rpx; flex-shrink: 0; color: var(--store-regular); font-size: 23rpx; line-height: 1.55; }
.info-line { width: 1rpx; height: 28rpx; flex-shrink: 0; margin-top: 5rpx; background: var(--store-soft); }
.info-value { flex: 1; min-width: 0; color: var(--store-regular); font-size: 23rpx; line-height: 1.65; overflow-wrap: anywhere; }
.rich-body { margin-top: 18rpx; color: #555555; font-size: 24rpx; line-height: 1.75; overflow-wrap: anywhere; }
.rich-body :deep(img) { display: block; max-width: 100%; height: auto; margin: 12rpx auto; border-radius: 12rpx; }
.rich-body :deep(iframe), .rich-body :deep(script) { display: none; }
.review-head { display: flex; align-items: center; justify-content: space-between; gap: 14rpx; }
.review-count { color: var(--store-muted); font-size: 24rpx; font-weight: 500; }
.review-more { color: var(--store-regular); font-size: 22rpx; }
.review-item { display: flex; gap: 16rpx; padding-top: 22rpx; }
.review-avatar { width: 64rpx; height: 64rpx; flex-shrink: 0; border-radius: 50%; }
.review-main { flex: 1; min-width: 0; }
.review-top { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; }
.review-name { color: var(--store-text); font-size: 24rpx; font-weight: 750; }
.review-time { color: var(--store-muted); font-size: 20rpx; }
.review-stars { display: block; margin-top: 6rpx; color: var(--store-text); font-size: 22rpx; letter-spacing: 2rpx; }
.review-text { display: block; margin-top: 10rpx; color: var(--store-regular); font-size: 23rpx; line-height: 1.65; overflow-wrap: anywhere; }
.review-images { display: flex; gap: 10rpx; margin-top: 14rpx; }
.review-image { width: 150rpx; height: 100rpx; border-radius: 12rpx; }
.review-empty { padding: 34rpx 0 12rpx; color: var(--store-muted); font-size: 23rpx; text-align: center; }
.bottom-space { height: 20rpx; }
.bottom-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 40;
  display: flex;
  align-items: center;
  gap: 18rpx;
  padding: 14rpx 28rpx calc(14rpx + var(--app-safe-bottom, env(safe-area-inset-bottom)));
  background: var(--store-surface);
}
.bar-action {
  width: 88rpx;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3rpx;
  color: var(--store-regular);
  font-size: 20rpx;
  &.active { color: var(--store-text); }
}
.bar-icon { width: 42rpx; height: 42rpx; }
.buy-btn {
  flex: 1;
  height: 88rpx;
  line-height: 88rpx;
  border-radius: 999rpx;
  background: var(--store-primary);
  color: #ffffff;
  font-size: 29rpx;
  font-weight: 750;
  text-align: center;
  letter-spacing: 1rpx;
  &.disabled { background: var(--store-line); color: var(--store-muted); }
}
.buy-btn.contact { background: var(--store-surface); color: var(--store-primary); border: 1px solid var(--store-primary); }
.review-sheet-mask { position: fixed; inset: 0; z-index: 60; display: flex; align-items: flex-end; background: rgba(12, 14, 17, .5); }
.review-sheet { width: 100%; max-height: 76vh; display: flex; flex-direction: column; border-radius: 28rpx 28rpx 0 0; background: var(--store-surface); overflow: hidden; }
.sheet-head { display: flex; align-items: center; justify-content: space-between; padding: 26rpx 30rpx 18rpx; }
.sheet-title { color: var(--store-text); font-size: 32rpx; font-weight: 850; }
.sheet-close { color: var(--store-muted); font-size: 34rpx; }
.sheet-body { max-height: 62vh; padding: 0 30rpx 40rpx; box-sizing: border-box; }
</style>
