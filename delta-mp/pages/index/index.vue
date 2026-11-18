<template>
  <view class="home-page tab-page">
    <ScreenHeader title="首页">
      <view
        v-if="siteStore.customerServiceHomeIconVisible"
        class="screen-icon-btn"
        role="button"
        aria-label="在线客服"
        @click="goCustomerService"
      >
        <image src="/static/icons/header-headset.svg" mode="aspectFit" />
      </view>
      <view class="screen-icon-btn" role="button" aria-label="消息通知" @click="goMessages">
        <image src="/static/icons/header-bell.svg" mode="aspectFit" />
        <view v-if="unreadCount > 0" class="screen-dot" />
      </view>
    </ScreenHeader>

    <scroll-view scroll-y class="home-scroll" :show-scrollbar="false">
      <view class="home-content">
        <view v-if="isNeo" class="neo-home-head">
          <text class="neo-home-kicker">PLAY BEYOND LIMITS</text>
          <text class="neo-home-title">{{ siteStore.siteName }}</text>
          <text class="neo-home-sub">{{ siteStore.subtitle }}</text>
        </view>

        <swiper
          v-if="banners.length"
          class="hero-card hero-swiper"
          :class="{ banner: isNeo }"
          :autoplay="banners.length > 1"
          :interval="4500"
          :circular="banners.length > 1"
          :indicator-dots="banners.length > 1"
          indicator-color="rgba(17,17,17,.25)"
          indicator-active-color="#171717"
        >
          <swiper-item v-for="(item, index) in banners" :key="item.id">
            <view class="hero-slide" :class="{ 'hero-feature': isStorefrontBanner(item) }" @click="onBannerClick(item)">
              <AppImage class="hero-bg" :src="item.imageUrl" :width="1080" :mode="isStorefrontBanner(item) ? 'aspectFill' : 'aspectFit'" :eager="index === 0" />
              <view v-if="!isStorefrontBanner(item) && hasHeroText(item)" class="hero-shade" />
              <view v-if="hasHeroText(item)" class="hero-copy" :class="{ 'hero-copy-dark': !isStorefrontBanner(item) }">
                <text v-if="heroCopy(item).title" class="hero-title">{{ heroCopy(item).title }}</text>
                <text v-if="heroCopy(item).subtitle" class="hero-sub">{{ heroCopy(item).subtitle }}</text>
                <view v-if="heroCopy(item).button" class="hero-action">{{ heroCopy(item).button }} <text class="hero-arrow">›</text></view>
              </view>
            </view>
          </swiper-item>
        </swiper>

        <view v-else-if="!isNeo" class="hero-card hero-feature" @click="goCategoryPage">
          <AppImage class="hero-bg" :src="storefrontBanner" mode="aspectFill" eager />
          <view class="hero-copy">
            <text class="hero-title">{{ heroTitle }}</text>
            <text class="hero-sub">{{ heroSubtitle }}</text>
          </view>
        </view>

        <view class="search-bar" @click="goSearch">
          <view class="search-inner">
            <image class="search-icon" src="/static/icons/header-search.svg" mode="aspectFit" />
            <text class="search-placeholder">搜索你需要的服务</text>
          </view>
        </view>

        <view v-if="notices.length" class="notice-bar" @click="showNoticeList = true">
          <view class="notice-label">
            <image class="notice-label-icon" src="/static/icons/header-megaphone.svg" mode="aspectFit" />
            <text class="notice-label-text">公告</text>
          </view>
          <swiper class="notice-swiper" vertical :autoplay="notices.length > 1" :interval="3500" :duration="500" :circular="notices.length > 1" :disable-touch="true">
            <swiper-item v-for="item in notices" :key="item.id">
              <view class="notice-swiper-item">
                <text class="notice-title">{{ item.title }}</text>
              </view>
            </swiper-item>
          </swiper>
          <text class="notice-more">{{ notices.length }}条 ›</text>
        </view>

        <view class="notice-list-mask" v-if="showNoticeList" @click="showNoticeList = false">
          <view class="notice-list-popup" @click.stop>
            <view class="notice-list-header">
              <text class="notice-list-title">平台公告</text>
              <text class="notice-list-close" @click="showNoticeList = false">✕</text>
            </view>
            <scroll-view scroll-y class="notice-list-body">
              <view
                v-for="item in notices"
                :key="item.id"
                class="notice-list-item"
                @click="openNoticeDetail(item); showNoticeList = false"
              >
                <view class="notice-list-dot"></view>
                <view class="notice-list-info">
                  <text class="notice-list-name">{{ item.title }}</text>
                  <text v-if="item.createdAt || item.createTime" class="notice-list-time">{{ formatTime(item.createdAt || item.createTime) }}</text>
                </view>
                <text class="notice-list-arrow">›</text>
              </view>
            </scroll-view>
          </view>
        </view>

        <view v-if="categories.length" class="category-grid">
          <view
            v-for="cat in categories"
            :key="cat.id"
            class="category-item"
            @click="goCategory(cat.id)"
          >
            <view class="category-icon-wrap">
              <AppImage
                :width="160"
                class="category-icon"
                :class="{ 'bundled-category-icon': !cat.icon || cat.icon.startsWith('/static/icons/') }"
                :style="{ width: (Number(cat.iconSize) || 56) + 'rpx', height: (Number(cat.iconSize) || 56) + 'rpx' }"
                :src="cat.icon || '/static/icons/分类.svg'"
                mode="aspectFit"
              />
            </view>
            <text class="category-name">{{ cat.name }}</text>
          </view>
        </view>

        <view class="section">
          <view class="section-header">
            <scroll-view scroll-x class="recommend-tabs" :show-scrollbar="false" scroll-with-animation>
              <view
                v-for="tab in recommendTabs"
                :key="tab.id"
                class="recommend-tab"
                :class="{ active: currentRecommendCategoryId === tab.id }"
                @click="switchRecommendCategory(tab.id)"
              >
                {{ tab.name }}
              </view>
            </scroll-view>
            <view class="section-actions">
              <view class="section-more" @click="showHomeActions = true">更多 <view class="section-more-chevron" /></view>
            </view>
          </view>
          <view class="product-grid">
            <ProductCard v-for="p in recommendProducts" :key="p.id" :product="p" cover-mode="aspectFit" />
          </view>
          <PageState v-if="recommendLoading || recommendError" :loading="recommendLoading" :error="recommendError" @retry="loadRecommendProducts" />
          <view v-else-if="!recommendProducts.length" class="recommend-empty" @click="goCategoryPage">
            <text>暂时没有推荐商品</text>
            <text class="recommend-empty-link">去分类页看看 ›</text>
          </view>
        </view>

        <view class="tab-page-bottom-spacer" :class="{ 'ticker-spacer': purchaseFeedEnabled && purchaseFeed.length }" />
      </view>
    </scroll-view>

    <CustomTabBar :current="0" />

    <RecentPurchaseTicker
      v-if="purchaseFeedEnabled"
      :items="purchaseFeed"
      @more="goDisplay('feed')"
    />

    <view v-if="showHomeActions" class="home-sheet-mask" @click="showHomeActions = false">
      <view class="home-sheet" @click.stop>
        <view class="home-sheet-head">
          <text>商品排序</text>
          <text class="home-sheet-close" @click="showHomeActions = false">✕</text>
        </view>
        <view
          v-for="option in homeSortOptions"
          :key="option.value"
          class="home-sheet-option"
          :class="{ active: currentSort === option.value }"
          @click="chooseHomeSort(option.value)"
        >
          <text>{{ option.label }}</text>
          <text v-if="currentSort === option.value" class="home-sheet-check">✓</text>
        </view>
        <view class="home-sheet-all" @click="goCategoryPage">查看全部商品 <text>›</text></view>
      </view>
    </view>

    <view class="notice-popup" v-if="showNoticePopup" @click="closeNoticePopup">
      <view class="popup-content" @click.stop>
        <view class="popup-header">
          <text class="popup-title">公告（{{ popupCurrentIndex + 1 }}/{{ popupNotices.length }}）</text>
          <text class="popup-close" @click="closeNoticePopup">✕</text>
        </view>
        <scroll-view scroll-y class="popup-body">
          <text class="popup-notice-title">{{ popupNotices[popupCurrentIndex]?.title }}</text>
          <rich-text v-if="noticeHtml" :nodes="noticeHtml" />
        </scroll-view>
        <view class="popup-footer">
          <view class="popup-nav" v-if="popupNotices.length > 1">
            <view
              class="popup-nav-btn"
              :class="{ disabled: popupCurrentIndex === 0 }"
              @click="popupCurrentIndex > 0 && popupCurrentIndex--"
            >上一条</view>
            <view
              class="popup-nav-btn"
              :class="{ disabled: popupCurrentIndex === popupNotices.length - 1 }"
              @click="popupCurrentIndex < popupNotices.length - 1 && popupCurrentIndex++"
            >下一条</view>
          </view>
          <view class="popup-btn" @click="closeNoticePopup">我知道了</view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import { clientStorage } from '@/utils/client-storage'
import AppImage from '@/components/AppImage.vue'
import { computed, ref } from 'vue'
import { onShow, onPullDownRefresh, onShareAppMessage, onShareTimeline } from '@dcloudio/uni-app'
import ProductCard from '@/components/ProductCard.vue'
import CustomTabBar from '@/components/CustomTabBar.vue'
import RecentPurchaseTicker from '@/components/RecentPurchaseTicker.vue'
import ScreenHeader from '@/components/ScreenHeader.vue'
import PageState from '@/components/PageState.vue'
import { productSortOptions, productSortParams } from '@/utils/product-sort'
import { productDescriptionHtml } from '@/utils/product-content'
import { bannerAction } from '@/utils/banner'
import { getRecommendProducts, getRecommendCategories } from '@/api/product'
import { getActiveBanners } from '@/api/banner'
import { getActiveNotices } from '@/api/notice'
import { getCategoryTree } from '@/api/category'
import { getPurchaseFeed } from '@/api/display'
import { openCustomerService } from '@/utils/customer-service'
import { useUserStore } from '@/store/user'
import { useRemindStore } from '@/store/remind'
import { useSiteStore } from '@/store/site'
const userStore = useUserStore()
const siteStore = useSiteStore()
const remindStore = useRemindStore()
const isNeo = computed(() => siteStore.themeId === 'neumorphism')
const unreadCount = computed(() => Number(remindStore.systemUnread || 0) + Number(remindStore.messageUnread || 0))
const heroTitle = computed(() => siteStore.siteName || '')
const heroSubtitle = computed(() => siteStore.subtitle || '')
const storefrontBanner = '/static/images/home-hero-operator.jpg'
function heroCopy(item) {
  return {
    title: item?.title || '',
    subtitle: item?.subtitle || '',
    button: item?.buttonText || ''
  }
}
function hasHeroText(item) {
  const copy = heroCopy(item)
  return Boolean(copy.title || copy.subtitle || copy.button)
}
function isStorefrontBanner(item) {
  if (isNeo.value) return false
  if (item.imageUrl === storefrontBanner) return true
  // #ifdef H5
  try {
    const url = new URL(item.imageUrl, window.location.origin)
    return url.origin === window.location.origin && url.pathname === storefrontBanner
  } catch {}
  // #endif
  return false
}

// 分享给好友
onShareAppMessage(() => ({
  title: siteStore.siteName + ' - ' + siteStore.subtitle,
  path: '/pages/index/index'
}))
// 分享到朋友圈
onShareTimeline(() => ({
  title: siteStore.siteName + ' - ' + siteStore.subtitle
}))
const banners = ref([])
const categories = ref([])
const notices = ref([])
const purchaseFeed = ref([])
const purchaseFeedEnabled = ref(false)
const recommendProducts = ref([])
const recommendLoading = ref(true)
const recommendError = ref('')
/** 热门推荐 Tab：全部 + 接口返回的分类（护航专区推荐、热门推荐等） */
const recommendTabs = ref([])
const currentRecommendCategoryId = ref('')
const currentSort = ref('default')
const homeSortOptions = productSortOptions
const showHomeActions = ref(false)
let recommendRequest = 0
const showNoticePopup = ref(false)
const popupNotices = ref([])
const popupCurrentIndex = ref(0)
const noticeHtml = computed(() => productDescriptionHtml(popupNotices.value[popupCurrentIndex.value]?.content || ''))
const showNoticeList = ref(false)

function noticeRevision(notice) {
  // A new admin revision (content, status or popup toggle) gets a new acknowledgement key.
  return `${notice.id}:${notice.updatedAt || notice.createdAt || ''}`
}

function formatTime(t) {
  if (!t) return ''
  const d = new Date(t)
  return `${d.getMonth() + 1}-${d.getDate()}`
}

async function loadData() {
  const config = siteStore.fetchSiteConfig()
  const opts = { loading: false, silent: true }
  const bannerLoad = getActiveBanners(opts).then(res => {
    const list = res.data?.records || res.data
    banners.value = Array.isArray(list) ? list : []
  })
  const noticeLoad = getActiveNotices(opts).then(async res => {
    const list = res.data?.records || res.data
    notices.value = Array.isArray(list) ? list : []
    // Wait for the current public setting before deciding whether a dismissed
    // notice should be shown again on this page entry.
    await config
    if (notices.value.length) {
      // 筛选出需要弹窗展示的公告
      const popupList = notices.value.filter(n => n.popupDisplay === 1)
      if (popupList.length > 0) {
        const seenIds = clientStorage.get('seenNoticeIds') || []
        const unseenList = popupList.filter(n => !seenIds.includes(noticeRevision(n)))
        // In every-refresh mode one JS session may render the home tab many times;
        // switching tabs must not reopen it. A real browser refresh recreates the store.
        const displayList = siteStore.noticePopupEveryVisit
          ? (siteStore.hasShownNoticePopupThisSession() ? [] : popupList)
          : unseenList
        if (displayList.length > 0) {
          siteStore.markNoticePopupShownThisSession()
          popupNotices.value = displayList
          popupCurrentIndex.value = 0
          showNoticePopup.value = true
        }
      }
    }
  })
  const categoryLoad = getCategoryTree(opts).then(res => {
    categories.value = Array.isArray(res.data) ? res.data.slice(0, 8) : []
  })
  const recommendLoad = getRecommendCategories(opts).then(res => {
    if (Array.isArray(res.data)) {
      recommendTabs.value = res.data.map(c => ({ id: String(c.id), name: c.name }))
      if (!recommendTabs.value.some(tab => tab.id === currentRecommendCategoryId.value))
        currentRecommendCategoryId.value = recommendTabs.value[0]?.id || ''
    }
  }).catch(() => {}).then(loadRecommendProducts)
  await Promise.allSettled([bannerLoad, noticeLoad, categoryLoad, recommendLoad])
}

onShow(() => {
  loadData()
  loadPurchaseFeed()
  remindStore.refreshBadges()
})

async function loadPurchaseFeed() {
  try {
    const res = await getPurchaseFeed({ loading: false })
    purchaseFeedEnabled.value = res.data?.enabled === true
    purchaseFeed.value = Array.isArray(res.data?.items) ? res.data.items.slice(0, 20) : []
  } catch (_) {
    purchaseFeedEnabled.value = false
    purchaseFeed.value = []
  }
}

onPullDownRefresh(async () => {
  await loadData()
  uni.stopPullDownRefresh()
})

function switchRecommendCategory(id) {
  if (currentRecommendCategoryId.value === id) return
  currentRecommendCategoryId.value = id
  loadRecommendProducts()
}

function chooseHomeSort(value) {
  currentSort.value = value
  showHomeActions.value = false
  loadRecommendProducts()
}

async function loadRecommendProducts() {
  const request = ++recommendRequest
  recommendLoading.value = !recommendProducts.value.length
  recommendError.value = ''
  const params = { ...productSortParams(currentSort.value) }
  if (currentRecommendCategoryId.value) params.categoryId = currentRecommendCategoryId.value
  try {
    const res = await getRecommendProducts(params, { loading: false, silent: true })
    if (request === recommendRequest) recommendProducts.value = res.data?.records || res.data || []
  } catch {
    if (request === recommendRequest) {
      recommendProducts.value = []
      recommendError.value = '推荐商品加载失败，请重试。'
    }
  } finally {
    if (request === recommendRequest) recommendLoading.value = false
  }
}

function goSearch() {
  uni.navigateTo({ url: '/pages/product/list?focus=1' })
}

function goCategoryPage() {
  showHomeActions.value = false
  uni.switchTab({ url: '/pages/category/index' })
}

function goMessages() {
  uni.switchTab({ url: '/pages/chat/list' })
}

function onBannerClick(item) {
  const action = bannerAction(item)
  if (action.type === 'preview') {
    if (action.url) uni.previewImage({ current: action.url, urls: [action.url] })
  } else if (action.type === 'page') uni.navigateTo({ url: action.url })
  else if (action.type === 'tab') uni.switchTab({ url: action.url })
  else if (action.type === 'external') {
    // #ifdef H5
    window.location.assign(action.url)
    return
    // #endif
    uni.setClipboardData({ data: action.url, success: () => uni.showToast({ title: '链接已复制，请在浏览器打开', icon: 'none' }) })
  } else uni.showToast({ title: '跳转链接无效，请联系客服', icon: 'none' })
}

function goCategory(id) {
  if (id) clientStorage.set('selectedCategoryId', id)
  uni.switchTab({ url: '/pages/category/index' })
}

function goDisplay(tab = 'rank') {
  uni.navigateTo({ url: `/pages/display/index?tab=${encodeURIComponent(tab)}` })
}

function openNoticeDetail(item) {
  popupNotices.value = [item]
  popupCurrentIndex.value = 0
  showNoticePopup.value = true
}

function closeNoticePopup() {
  showNoticePopup.value = false
  // 记录已看过的弹窗公告ID
  const seenIds = clientStorage.get('seenNoticeIds') || []
  popupNotices.value.forEach(n => {
    const revision = noticeRevision(n)
    if (n.id && !seenIds.includes(revision)) {
      seenIds.push(revision)
    }
  })
  clientStorage.set('seenNoticeIds', seenIds.slice(-100))
}

async function goCustomerService() {
  try {
    await openCustomerService()
  } catch (e) {
    uni.showToast({ title: e?.msg || '连接客服失败', icon: 'none' })
  }
}
</script>

<style lang="scss" scoped>
.home-page {
  height: var(--app-page-height, 100vh);
  min-height: var(--app-page-height, 100vh);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--store-soft);
}
.home-scroll {
  flex: 1;
  height: 0;
  min-height: 0;
  width: 100%;
}
.home-content {
  padding: 10rpx 28rpx calc(42rpx + var(--app-safe-bottom, env(safe-area-inset-bottom)));
}
.ticker-spacer { height: 148rpx; }

.hero-card {
  position: relative;
  height: 338rpx;
  overflow: hidden;
  border-radius: 26rpx;
  background: #f1f1f1;
}
.hero-swiper { width: 100%; }
.hero-slide { position: relative; width: 100%; height: 100%; overflow: hidden; }
.hero-bg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}
.hero-feature {
  background: var(--store-soft);
}
.hero-feature .hero-bg {
  left: 0;
  background-color: transparent;
  filter: grayscale(1) brightness(1.55);
  -webkit-mask-image: linear-gradient(90deg, transparent 38%, #000 75%);
  mask-image: linear-gradient(90deg, transparent 38%, #000 75%);
}
.hero-copy {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  height: 100%;
  width: 66%;
  padding: 16rpx 14rpx 16rpx 18rpx;
  box-sizing: border-box;
}
.hero-copy-dark {
  .hero-title { color: #ffffff; }
  .hero-sub { color: rgba(255,255,255,.76); }
  .hero-action { border-color: rgba(255,255,255,.22); background: rgba(17,17,17,.86); color: #fff; }
}
.hero-title {
  display: block;
  color: var(--store-text);
  font-size: 48rpx;
  font-weight: 800;
  line-height: 1.22;
  overflow-wrap: anywhere;
}
.hero-sub {
  display: block;
  margin-top: 10rpx;
  color: var(--store-regular);
  font-size: 25rpx;
  line-height: 1.45;
}
.hero-action {
  display: inline-flex;
  align-items: center;
  gap: 10rpx;
  margin-top: 24rpx;
  min-height: 66rpx;
  padding: 0 26rpx;
  border-radius: 999rpx;
  background: var(--store-primary);
  color: #ffffff;
  font-size: 25rpx;
  font-weight: 650;
}
.hero-arrow { font-size: 28rpx; line-height: 1; }

.search-bar {
  margin: 20rpx 0 0;
}
.search-inner {
  display: flex;
  align-items: center;
  gap: 16rpx;
  height: 84rpx;
  padding: 0 26rpx;
  border: 0;
  border-radius: 999rpx;
  background: var(--store-surface);
  box-shadow: none;
}
.search-icon { width: 38rpx; height: 38rpx; flex-shrink: 0; opacity: 0.78; }
.search-placeholder { color: var(--store-regular); font-size: 26rpx; }

.notice-bar {
  display: flex;
  align-items: center;
  margin: 14rpx 0 0;
  padding: 0 20rpx;
  height: 72rpx;
  background: var(--store-surface);
  border: 0;
  border-radius: 18rpx;
  overflow: hidden;
}
.notice-label {
  display: flex;
  align-items: center;
  gap: 8rpx;
  flex-shrink: 0;
  margin-right: 16rpx;
  padding-right: 16rpx;
  border-right: 1rpx solid var(--store-line);
  .notice-label-icon { width: 30rpx; height: 30rpx; filter: grayscale(1) brightness(0.3); }
  .notice-label-text { font-size: 24rpx; color: var(--store-text); font-weight: 750; }
}
.notice-swiper { flex: 1; min-width: 0; height: 100%; }
.notice-swiper-item { display: flex; align-items: center; height: 100%; min-width: 0; }
.notice-title {
  min-width: 0;
  font-size: 24rpx;
  color: var(--store-regular);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.notice-more { flex-shrink: 0; font-size: 22rpx; color: var(--store-muted); margin-left: 12rpx; }

.category-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 24rpx 8rpx;
  padding: 24rpx 8rpx;
  background: var(--store-surface);
  margin: 16rpx 0 0;
  border: 0;
  border-radius: 24rpx;
  box-shadow: none;
}
.category-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
  &:active { opacity: 0.78; }
}
.category-icon-wrap {
  width: auto;
  height: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 0;
  background: transparent;
}
.category-icon { width: 60rpx; height: 60rpx; }
.category-name {
  max-width: 100%;
  color: var(--store-text);
  font-size: 24rpx;
  font-weight: 650;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.section { padding: 26rpx 0 0; }
.section-header {
  display: flex;
  align-items: center;
  gap: 14rpx;
  min-width: 0;
}
.recommend-tabs { flex: 1; min-width: 0; white-space: nowrap; height: 64rpx; }
.recommend-tab {
  display: inline-flex;
  align-items: center;
  min-height: 62rpx;
  padding: 0 20rpx;
  font-size: 24rpx;
  color: var(--store-regular);
  background: transparent;
  border-radius: 999rpx;
  margin-right: 4rpx;
  &.active { color: #ffffff; background: var(--store-primary); font-weight: 750; }
}
.section-actions {
  display: flex;
  align-items: center;
  gap: 4rpx;
  flex-shrink: 0;
}
.section-more {
  display: inline-flex;
  align-items: center;
  gap: 10rpx;
  min-height: 62rpx;
  padding: 0 18rpx;
  background: var(--store-surface);
  border: 0;
  border-radius: 999rpx;
  color: var(--store-regular);
  font-size: 23rpx;
  white-space: nowrap;
}
.section-more-chevron {
  width: 9rpx;
  height: 9rpx;
  border: solid currentColor;
  border-width: 0 2rpx 2rpx 0;
  transform: translateY(-2rpx) rotate(45deg);
}
.product-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20rpx;
  margin-top: 18rpx;
}
.recommend-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
  padding: 54rpx 20rpx;
  margin-top: 18rpx;
  border: 1rpx dashed #dddddd;
  border-radius: 24rpx;
  color: var(--store-muted);
  font-size: 23rpx;
  background: var(--store-surface);
}
.recommend-empty-link { color: var(--store-text); font-weight: 700; }

.home-sheet-mask {
  position: fixed;
  inset: 0;
  z-index: 998;
  display: flex;
  align-items: flex-end;
  background: rgba(8, 10, 13, 0.52);
}
.home-sheet {
  width: 100%;
  padding: 8rpx 28rpx calc(28rpx + var(--app-safe-bottom, env(safe-area-inset-bottom)));
  border-radius: 28rpx 28rpx 0 0;
  background: var(--store-surface);
  box-sizing: border-box;
}
.home-sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx 4rpx 14rpx;
  color: var(--store-text);
  font-size: 30rpx;
  font-weight: 800;
}
.home-sheet-close { color: var(--store-muted); font-size: 30rpx; font-weight: 400; }
.home-sheet-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 82rpx;
  padding: 0 4rpx;
  border-top: 1rpx solid var(--store-line);
  color: var(--store-regular);
  font-size: 25rpx;
  &.active { color: var(--store-text); font-weight: 750; }
}
.home-sheet-check { color: var(--store-text); font-size: 26rpx; }
.home-sheet-all {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6rpx;
  min-height: 88rpx;
  margin-top: 10rpx;
  border-radius: 14rpx;
  background: var(--store-primary);
  color: #ffffff;
  font-size: 25rpx;
  font-weight: 750;
  text { font-size: 28rpx; }
}

.notice-list-mask {
  position: fixed;
  inset: 0;
  z-index: 999;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: rgba(13, 15, 18, 0.56);
}
.notice-list-popup {
  width: 100%;
  max-height: 70vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--store-surface);
  border-radius: 30rpx 30rpx 0 0;
}
.notice-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 30rpx 34rpx 20rpx;
  border-bottom: 1rpx solid var(--store-line);
  .notice-list-title { font-size: 33rpx; font-weight: 800; color: var(--store-text); }
  .notice-list-close { font-size: 34rpx; color: var(--store-regular); padding: 0 8rpx; }
}
.notice-list-body { height: 50vh; padding: 8rpx 0; }
.notice-list-item {
  display: flex;
  align-items: center;
  padding: 26rpx 34rpx;
  border-bottom: 1rpx solid var(--store-line);
}
.notice-list-dot {
  width: 10rpx;
  height: 10rpx;
  border-radius: 50%;
  background: var(--store-primary);
  flex-shrink: 0;
  margin-right: 18rpx;
}
.notice-list-info { flex: 1; overflow: hidden; }
.notice-list-name {
  display: block;
  color: var(--store-text);
  font-size: 27rpx;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.notice-list-time { display: block; margin-top: 6rpx; color: var(--store-muted); font-size: 21rpx; }
.notice-list-arrow { flex-shrink: 0; margin-left: 12rpx; color: var(--store-muted); font-size: 30rpx; }

.notice-popup {
  position: fixed;
  inset: 0;
  z-index: 999;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(13, 15, 18, 0.62);
  .popup-content {
    width: min(620rpx, calc(100% - 48rpx));
    max-height: 70vh;
    display: flex;
    flex-direction: column;
    overflow: hidden;
    background: var(--store-surface);
    border-radius: 28rpx;
    box-shadow: 0 16rpx 48rpx rgba(0, 0, 0, 0.12);
  }
  .popup-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 28rpx 32rpx;
    border-bottom: 1rpx solid var(--store-line);
    .popup-title { font-size: 31rpx; font-weight: 800; color: var(--store-text); }
    .popup-close { font-size: 34rpx; color: var(--store-regular); padding: 0 8rpx; }
  }
  .popup-body {
    min-height: 0;
    max-height: 50vh;
    padding: 32rpx 32rpx 44rpx;
    .popup-notice-title { display: block; margin-bottom: 18rpx; color: var(--store-text); font-size: 29rpx; font-weight: 800; }
  }
  .popup-footer {
    flex-shrink: 0;
    padding: 32rpx 32rpx calc(28rpx + var(--app-safe-bottom, env(safe-area-inset-bottom)));
    border-top: 1rpx solid var(--store-line);
    background: var(--store-surface);
    box-shadow: 0 -6rpx 20rpx rgba(0, 0, 0, 0.03);
    .popup-nav { display: flex; justify-content: center; gap: 28rpx; margin-bottom: 18rpx; }
    .popup-nav-btn {
      padding: 8rpx 22rpx;
      border: 1rpx solid var(--store-line);
      border-radius: 999rpx;
      color: var(--store-text);
      font-size: 24rpx;
      &.disabled { color: var(--store-muted); border-color: var(--store-line); }
    }
    .popup-btn {
      height: 78rpx;
      line-height: 78rpx;
      text-align: center;
      border-radius: 999rpx;
      background: var(--store-primary);
      color: #ffffff;
      font-size: 27rpx;
      font-weight: 750;
    }
  }
}
</style>
