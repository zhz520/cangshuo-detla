<template>
  <view class="create-order">
    <view class="section">
      <view class="section-title">商品信息</view>
      <text class="product-name">{{ productName }}</text>
      <text v-if="specInfo" class="spec">{{ specInfo }}</text>
      <PriceText v-if="quoteReady" :value="amount" :size="36" />
      <text v-else class="quote-status">{{ quoteLoading ? '正在计算价格…' : quoteError || '等待确认价格' }}</text>
      <view v-if="quoteReady && Number(quote.priceIncreaseRate) > 0" class="price-breakdown">
        <text>商品原价 ¥{{ Number(quote.baseAmount).toFixed(2) }}</text>
        <text>打手涨价 {{ Number(quote.priceIncreaseRate) }}%：+¥{{ Number(quote.priceIncreaseAmount).toFixed(2) }}</text>
      </view>
    </view>
    <!-- 使用已保存信息 -->
    <view class="section saved-section" v-if="userStore.isLoggedIn && (savedList.length || formFields.length)">
      <view class="section-title">使用已保存信息</view>
      <view class="saved-tags">
        <view
          v-for="item in savedList" :key="item.id"
          class="saved-tag" :class="{ active: activeSavedId === item.id }"
          @click="applySaved(item)"
        >
          <text class="saved-label">{{ item.label || '未命名' }}</text>
        </view>
        <view class="saved-tag" :class="{ active: activeSavedId === 'manual' }" @click="applyManual">
          <text class="saved-label">手动填写</text>
        </view>
      </view>
    </view>
    <view class="section" v-if="formFields.length">
      <view class="section-title">订单信息</view>
      <view v-for="field in formFields" :key="field.id" class="form-item">
        <text class="label">{{ field.fieldLabel }}<text v-if="field.required" class="required">*</text></text>
        <!-- TEXT -->
        <input v-if="field.fieldType === 'TEXT'" v-model="extraFields[field.fieldLabel]" :placeholder="field.placeholder || ('请输入' + field.fieldLabel)" />
        <!-- TEXTAREA -->
        <textarea v-else-if="field.fieldType === 'TEXTAREA'" v-model="extraFields[field.fieldLabel]" :placeholder="field.placeholder || ('请输入' + field.fieldLabel)" :maxlength="500" />
        <!-- SELECT -->
        <picker v-else-if="field.fieldType === 'SELECT'" :range="getFieldOptions(field)" @change="extraFields[field.fieldLabel] = getFieldOptions(field)[$event.detail.value]">
          <view class="picker-value">
            <text :class="{ placeholder: !extraFields[field.fieldLabel] }">{{ extraFields[field.fieldLabel] || field.placeholder || ('请选择' + field.fieldLabel) }}</text>
            <text class="arrow">></text>
          </view>
        </picker>
      </view>
    </view>
    <view v-if="!userStore.isLoggedIn" class="section guest-section">
      <view class="section-title">游客下单凭证</view>
      <template v-if="siteStore.guestCheckoutEnabled && guestPlatformSupported && guestPaymentAvailable">
        <text class="guest-tip">无需注册即可下单。请填写手机号和查单码并妥善保存；后续凭这两项查询订单。</text>
        <view class="guest-field">
          <text class="label">手机号<text class="required">*</text></text>
          <input v-model.trim="guestPhone" type="number" maxlength="11" placeholder="用于联系和查单" />
        </view>
        <view class="guest-field">
          <text class="label">查单码<text class="required">*</text></text>
          <input v-model.trim="guestLookupCode" maxlength="12" placeholder="自定义 6 至 12 位数字或字母" />
        </view>
        <view class="privacy-row" @click="guestPrivacyAccepted = !guestPrivacyAccepted">
          <view class="privacy-check" :class="{ checked: guestPrivacyAccepted }">{{ guestPrivacyAccepted ? '✓' : '' }}</view>
          <text>我已阅读并同意</text>
          <text class="privacy-link" @click.stop="goAgreement('user')">用户协议</text>
          <text>和</text>
          <text class="privacy-link" @click.stop="goAgreement('privacy')">隐私政策</text>
        </view>
        <view class="guest-actions"><text @click="goLookup">已有游客订单，去查单</text><text @click="goLogin">已有账号，去登录</text></view>
      </template>
      <template v-else>
        <text class="guest-tip">{{ siteStore.guestCheckoutEnabled && guestPlatformSupported ? '游客在线支付暂未启用，请登录后继续下单。' : '游客下单当前未开放，请登录后继续下单。' }}</text>
        <view class="guest-login-button" @click="goLogin">去登录</view>
      </template>
    </view>
    <view class="section">
      <view class="section-title">接单打手</view>
      <view class="player-options">
        <view class="player-option" :class="{active:selectionMode==='auto'}" @click="selectionMode='auto'">自动安排</view>
        <view class="player-option" :class="{active:wantDesignate}" @click="selectionMode='designated'">指定打手</view>
        <view v-for="level in playerLevels" :key="level.id" class="player-option" :class="{active:selectionMode==='level:'+level.id}" @click="selectionMode='level:'+level.id">
          <text>{{ level.name }}</text><text class="option-rate">{{ Number(level.priceIncreaseRate)>0 ? '涨价 '+Number(level.priceIncreaseRate)+'%' : '不涨价' }}</text>
        </view>
      </view>
      <view v-if="wantDesignate" class="player-select" @click="showPlayerPicker = true">
        <text v-if="selectedPlayer" class="selected-player">{{ selectedPlayer.nickname }}{{ Number(selectedPlayer.priceIncreaseRate)>0 ? '（涨价 '+Number(selectedPlayer.priceIncreaseRate)+'%）' : '' }}</text>
        <text v-else class="placeholder">点击选择接单员</text>
        <text class="arrow">></text>
      </view>
      <text v-if="quoteError" class="quote-error" @click="refreshQuote">{{ quoteError }}，点击重试</text>
    </view>
    <view class="bottom-bar">
      <view class="total">合计：<PriceText v-if="quoteReady" :value="amount" :size="36" /><text v-else>待确认</text></view>
      <view class="btn-submit" :class="{disabled:!canSubmit || submitting}" @click="submitOrder">{{ submitting?'提交中…':(siteStore.orderPurchaseEnabled?'提交订单':'购买已暂停') }}</view>
    </view>
    <!-- 接单员选择弹窗（底部滑出） -->
    <view v-if="showPlayerPicker" class="modal-mask" @click="showPlayerPicker = false">
      <view class="player-picker" :class="{ 'slide-up': showPlayerPicker }" @click.stop>
        <view class="picker-handle"><view class="handle-bar" /></view>
        <view class="picker-header">
          <text class="picker-title">选择接单员</text>
          <text class="picker-close" @click="showPlayerPicker = false">✕</text>
        </view>
        <view class="limit-tip">
          接单员最多同时接 <text class="gold">{{ maxConcurrent }}</text> 个订单
        </view>
        <view class="picker-search">
          <input v-model="playerKeyword" placeholder="搜索接单员昵称" @confirm="searchPlayers" />
          <view class="search-btn" @click="searchPlayers">搜索</view>
        </view>
        <scroll-view scroll-y class="picker-list">
          <view
            v-for="p in playerList" :key="p.id"
            class="picker-item"
            :class="{ selected: selectedPlayer && selectedPlayer.id === p.id, full: isFull(p), offline: !isOnline(p) }"
            @click="pickPlayer(p)"
          >
            <AppImage :width="160" :src="p.avatar || '/static/images/default-avatar.png'" class="picker-avatar" mode="aspectFill" />
            <view class="picker-info">
              <view class="picker-name-row">
                <text class="picker-name">{{ p.nickname || '-' }}</text>
                <text v-if="isOnline(p)" class="online-badge">在线</text>
                <text v-else class="offline-badge">离线</text>
                <text v-if="isFull(p)" class="full-badge">已满载</text>
              </view>
              <view class="picker-tags">
                <text v-if="p.levelName" class="picker-tag">{{ p.levelName }}</text>
                <text class="picker-tag">{{ Number(p.priceIncreaseRate)>0 ? '涨价 '+Number(p.priceIncreaseRate)+'%' : '不涨价' }}</text>
                <text v-if="p.avgRating" class="picker-tag">⭐{{ Number(p.avgRating).toFixed(1) }}</text>
                <text class="picker-tag done">完成 {{ p.completedOrders || 0 }}</text>
                <text class="picker-tag" :class="(p.activeOrders||0) > 0 ? 'active-tag' : ''">进行中 {{ p.activeOrders || 0 }}/{{ maxConcurrent }}</text>
              </view>
            </view>
            <text v-if="isOnline(p) && selectedPlayer && selectedPlayer.id === p.id" class="picker-check">✓</text>
          </view>
          <view v-if="playerList.length === 0" class="picker-empty">暂无可用接单员</view>
        </scroll-view>
        <view class="picker-footer">
          <view class="picker-btn cancel" @click="showPlayerPicker = false">取消</view>
          <view class="picker-btn confirm" @click="confirmPickPlayer">确定</view>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup>
import AppImage from '@/components/AppImage.vue'
import { ref, reactive, watch, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import PriceText from '@/components/PriceText.vue'
import { createOrder, getAvailablePlayers, getOrderDetail, getOrderQuote } from '@/api/order'
import { getProductDetail, getCategoryFormFields } from '@/api/product'
import { getSavedInfoByCategory, saveDynamicInfo } from '@/api/user'
import { useUserStore } from '@/store/user'
import { useSiteStore } from '@/store/site'
import { createGuestOrder } from '@/api/guest'
import { getPublicPlayer, getPlayerCategories } from '@/api/player'

const userStore = useUserStore()
const siteStore = useSiteStore()
const guestPlatformSupported = ref(false)
// Guest shadow checkout is enabled on H5 only; mini-program payment requires a verified openid.
// #ifdef H5
guestPlatformSupported.value = true
// #endif
const productId = ref(0)
const specInfo = ref('')
const amount = ref(0)
const productName = ref('')
const quote = ref(null), quoteLoading = ref(false), quoteError = ref(''), submitting = ref(false)
const selectionMode = ref('auto'), playerLevels = ref([])
const guestPhone = ref('')
const guestLookupCode = ref('')
const guestPrivacyAccepted = ref(false)
const wantDesignate = computed(() => selectionMode.value === 'designated')
const playerLevelId = computed(() => selectionMode.value.startsWith('level:') ? Number(selectionMode.value.slice(6)) : undefined)
const quoteReady = computed(() => !!quote.value && !quoteLoading.value && !quoteError.value && (!wantDesignate.value || !!selectedPlayer.value))
const guestPaymentAvailable = computed(() => siteStore.wxPayEnabled && (siteStore.wxPayH5Enabled || siteStore.wxPayOfficialEnabled))
const canSubmit = computed(() => siteStore.orderPurchaseEnabled && quoteReady.value
  && (userStore.isLoggedIn || (siteStore.guestCheckoutEnabled && guestPlatformSupported.value
    && guestPaymentAvailable.value
    && /^1[0-9]{10}$/.test(guestPhone.value)
    && /^[A-Za-z0-9]{6,12}$/.test(guestLookupCode.value)
    && guestPrivacyAccepted.value)))
let quoteVersion = 0

async function refreshQuote() {
  const version = ++quoteVersion
  quote.value = null
  quoteError.value = ''
  if (!productId.value || (wantDesignate.value && !selectedPlayer.value)) { quoteLoading.value = false; return }
  quoteLoading.value = true
  try {
    const { data } = await getOrderQuote({ productId: productId.value, designatedPlayerId: wantDesignate.value ? selectedPlayer.value.id : undefined, playerLevelId: playerLevelId.value })
    if (version !== quoteVersion) return
    quote.value = data
    amount.value = data.amount
    productName.value = data.productName
  } catch (e) {
    if (version === quoteVersion) quoteError.value = e?.msg || e?.message || '价格获取失败'
  } finally { if (version === quoteVersion) quoteLoading.value = false }
}

// 动态表单
const formFields = ref([])
const extraFields = reactive({})
const currentCategoryId = ref(null)

// 已保存的下单信息
const savedList = ref([])
const activeSavedId = ref(null)

function getFieldOptions(field) {
  if (!field.options) return []
  return field.options.split(',').map(s => s.trim()).filter(Boolean)
}

async function loadFormFields(pid, initialFields = null) {
  try {
    const prodRes = await getProductDetail(pid)
    const categoryId = prodRes.data?.categoryId
    if (!categoryId) return
    currentCategoryId.value = categoryId
    const res = await getCategoryFormFields(categoryId)
    const fields = res.data || []
    if (initialFields) Object.entries(initialFields).forEach(([key, value]) => { extraFields[key] = value })
    formFields.value = fields
    // 加载已保存的下单信息
    if (userStore.isLoggedIn) loadSavedList(categoryId)
  } catch (e) {
    formFields.value = []
  }
}

async function loadSavedList(categoryId) {
  try {
    const res = await getSavedInfoByCategory(categoryId)
    savedList.value = res.data || []
  } catch (e) {
    savedList.value = []
  }
}

function applySaved(item) {
  activeSavedId.value = item.id
  let fields = {}
  if (item.savedFields) {
    try {
      fields = typeof item.savedFields === 'string' ? JSON.parse(item.savedFields) : item.savedFields
    } catch (e) { /* ignore */ }
  }
  Object.keys(extraFields).forEach(k => { extraFields[k] = '' })
  Object.entries(fields).forEach(([k, v]) => { extraFields[k] = v })
}

function applyManual() {
  activeSavedId.value = 'manual'
  Object.keys(extraFields).forEach(k => { extraFields[k] = '' })
}

const showPlayerPicker = ref(false)
const selectedPlayer = ref(null)
const playerKeyword = ref('')
const playerList = ref([])
const maxConcurrent = ref(5)

watch(wantDesignate, (val) => {
  if (val) searchPlayers()
})
watch([selectionMode, selectedPlayer], refreshQuote, { flush: 'sync' })

function isFull(p) {
  return (p.activeOrders || 0) >= maxConcurrent.value
}
function isOnline(p) {
  return p.isOnline === 1
}

async function searchPlayers() {
  try {
    const res = await getAvailablePlayers({ pageNum: 1, pageSize: 50, keyword: playerKeyword.value })
    const data = res.data || {}
    const page = data.players || {}
    playerList.value = page.records || []
    if (data.maxConcurrent != null) maxConcurrent.value = data.maxConcurrent
  } catch (e) {
    playerList.value = []
  }
}

function pickPlayer(p) {
  if (!isOnline(p)) {
    uni.showModal({ title: '无法选择', content: `${p.nickname} 当前处于离线状态，无法指定`, showCancel: false })
    return
  }
  if (isFull(p)) {
    uni.showModal({
      title: '无法选择',
      content: `该接单员当前已有 ${p.activeOrders || 0} 个进行中订单，已达最大接单数 ${maxConcurrent.value}`,
      showCancel: false
    })
    return
  }
  selectedPlayer.value = p
}

function confirmPickPlayer() {
  showPlayerPicker.value = false
}

async function loadRepeatOrder(repeatOrderId) {
  try {
    const { data: source } = await getOrderDetail(repeatOrderId)
    if (!source?.productId) throw new Error('原订单商品不存在')
    productId.value = source.productId
    productName.value = source.productName || '服务'
    specInfo.value = source.specInfo || ''
    let repeatFields = null
    if (source.extraFields) {
      try {
        repeatFields = typeof source.extraFields === 'string' ? JSON.parse(source.extraFields) : source.extraFields
      } catch (_) {}
    }
    await loadFormFields(source.productId, repeatFields)
    if (source.requestedPlayerLevelId) selectionMode.value = 'level:' + source.requestedPlayerLevelId
    if (source.designatedPlayerId) {
      try {
        const { data: playerData } = await getPublicPlayer(source.designatedPlayerId)
        if (playerData?.canOrder) { selectionMode.value = 'designated'; selectedPlayer.value = playerData }
        else uni.showToast({ title: '原指定打手暂不可接单，可重新选择', icon: 'none' })
      } catch (_) { uni.showToast({ title: '原指定打手暂不可用，可重新选择', icon: 'none' }) }
    }
    await refreshQuote()
  } catch (e) {
    uni.showModal({ title: '无法再次下单', content: e?.msg || e?.message || '商品已下架或原订单不可用', showCancel: false, success: () => uni.navigateBack() })
  }
}

onLoad(async (opts) => {
  getPlayerCategories().then(({data}) => { playerLevels.value=data||[] }).catch(() => {})
  if (opts.repeatOrderId) {
    if (!userStore.isLoggedIn) return uni.showToast({ title: '请登录后再次下单', icon: 'none' })
    await loadRepeatOrder(opts.repeatOrderId)
    return
  }
  productId.value = opts.productId
  specInfo.value = decodeURIComponent(opts.specCombination || opts.specInfo || '')
  productName.value = decodeURIComponent(opts.productName || '服务')
  await loadFormFields(opts.productId)
  if(opts.playerId){
    selectionMode.value='designated'
    try{const {data}=await getPublicPlayer(opts.playerId);if(!data.canOrder){uni.showToast({title:data.orderUnavailableReason||'打手暂不可接单',icon:'none'});return}selectedPlayer.value=data}
    catch(_){uni.showToast({title:'指定打手暂不可用，请重新选择',icon:'none'})}
  } else await refreshQuote()
})

async function submitOrder() {
  if (submitting.value) return
  if (!siteStore.orderPurchaseEnabled) return uni.showToast({ title: '前台购买功能已暂停', icon: 'none' })
  if (!userStore.isLoggedIn && (!siteStore.guestCheckoutEnabled || !guestPlatformSupported.value || !guestPaymentAvailable.value)) {
    const message = !guestPlatformSupported.value ? '当前端下单请先登录'
      : !siteStore.guestCheckoutEnabled ? '游客下单暂未开放，请登录后下单'
      : '游客在线支付暂未启用，请登录后下单'
    return uni.showToast({ title: message, icon: 'none' })
  }
  if (!userStore.isLoggedIn) {
    if (!/^1[0-9]{10}$/.test(guestPhone.value)) return uni.showToast({ title: '请输入正确的手机号', icon: 'none' })
    if (!/^[A-Za-z0-9]{6,12}$/.test(guestLookupCode.value)) return uni.showToast({ title: '查单码须为6至12位数字或字母', icon: 'none' })
    if (!guestPrivacyAccepted.value) return uni.showToast({ title: '请先同意用户协议和隐私政策', icon: 'none' })
  }
  if(wantDesignate.value&&!selectedPlayer.value) return uni.showToast({title:'请选择可接单的打手，或关闭指定接单员',icon:'none'})
  if(!quoteReady.value) return uni.showToast({title:'请先确认下单价格',icon:'none'})
  // 校验必填动态字段
  for (const field of formFields.value) {
    if (field.required && !extraFields[field.fieldLabel]?.trim()) {
      return uni.showToast({ title: `请填写${field.fieldLabel}`, icon: 'none' })
    }
  }
  submitting.value = true
  try {
    const fieldsToSubmit = { ...extraFields }
    const orderPayload = {
      productId: productId.value,
      specInfo: specInfo.value,
      amount: amount.value,
      extraFields: fieldsToSubmit,
      designatedPlayerId: wantDesignate.value ? selectedPlayer.value?.id : undefined,
      playerLevelId: playerLevelId.value
    }
    if (!userStore.isLoggedIn) {
      const res = await createGuestOrder({
        phone: guestPhone.value,
        lookupCode: guestLookupCode.value,
        privacyAccepted: true,
        order: orderPayload
      })
      userStore.setGuestSession(res.data)
      uni.showModal({
        title: '游客订单已创建',
        content: '请妥善保存手机号和查单码，后续可凭这两项查询订单。',
        showCancel: false,
        success: () => uni.redirectTo({ url: '/pages/order/pay?orderId=' + res.data.orderId + '&amount=' + amount.value + '&guest=1' })
      })
      return
    }
    const res = await createOrder(orderPayload)
    // 自动保存本次填写的信息（最多3条，超出覆盖最旧）
    if (currentCategoryId.value && formFields.value.length) {
      const vals = Object.values(fieldsToSubmit).filter(v => v && v.trim())
      if (vals.length) {
        const label = vals.slice(0, 2).join(' / ').substring(0, 30)
        try {
          await saveDynamicInfo({
            categoryId: currentCategoryId.value,
            savedFields: fieldsToSubmit,
            label
          })
        } catch (e) {
          console.error('保存下单信息失败', e)
        }
      }
    }
    uni.redirectTo({ url: '/pages/order/pay?orderId=' + res.data.id + '&amount=' + res.data.amount })
  } catch (e) {
    console.error('create order failed', e)
    const msg = e?.msg || e?.message || '下单失败，请稍后重试'
    uni.showToast({ title: msg, icon: 'none' })
    await refreshQuote()
  } finally { submitting.value = false }
}

function goLogin() { uni.navigateTo({ url: '/pages/login/index' }) }
function goLookup() { uni.navigateTo({ url: '/pages/order/lookup' }) }
function goAgreement(type) { uni.navigateTo({ url: type === 'privacy' ? '/pages/agreement/privacy' : '/pages/agreement/user' }) }
</script>

<style lang="scss" scoped>
.create-order { background: #f7f8f9; min-height: var(--app-page-height, 100vh); padding-bottom: 120rpx; }
.section { background: #ffffff; padding: 24rpx; margin: 20rpx 24rpx; border-radius: 16rpx; box-shadow: 0 4rpx 20rpx rgba(23, 25, 28, 0.04); }
.section-title { font-size: 30rpx; font-weight: bold; margin-bottom: 20rpx; color: #17191c; }
.product-name { font-size: 28rpx; font-weight: bold; color: #17191c; display: block; margin-bottom: 8rpx; }
.spec { font-size: 24rpx; color: #666b73; display: block; margin-bottom: 12rpx; }
.required { color: #17191c; margin-left: 4rpx; font-size: 24rpx; }
.form-item { margin-bottom: 24rpx; .label { font-size: 26rpx; color: #666b73; margin-bottom: 12rpx; display: block; }
  input { height: 72rpx; background: #f7f8f9; border: 1rpx solid #e6e7e9; border-radius: 12rpx; padding: 0 24rpx; font-size: 28rpx; color: #17191c; }
  textarea { width: 100%; height: 160rpx; background: #f7f8f9; border: 1rpx solid #e6e7e9; border-radius: 12rpx; padding: 20rpx 24rpx; font-size: 28rpx; color: #17191c; box-sizing: border-box; }
}
.guest-section { border: 2rpx solid #dfe3e8; }
.guest-tip { display: block; color: #73777e; font-size: 25rpx; line-height: 1.7; margin-bottom: 22rpx; }
.guest-field { margin-bottom: 22rpx;
  .label { display: block; color: #555b63; font-size: 26rpx; margin-bottom: 10rpx; }
  input { width: 100%; height: 76rpx; padding: 0 22rpx; box-sizing: border-box; border: 1rpx solid #e2e5e9; border-radius: 12rpx; background: #f7f8f9; font-size: 27rpx; }
}
.privacy-row { display: flex; align-items: center; flex-wrap: wrap; gap: 8rpx; color: #666b73; font-size: 24rpx; line-height: 1.7; }
.privacy-check { width: 32rpx; height: 32rpx; line-height: 32rpx; text-align: center; border: 2rpx solid #b9bdc4; border-radius: 8rpx; color: #fff; font-size: 22rpx; }
.privacy-check.checked { border-color: #17191c; background: #17191c; }
.privacy-link { color: #17191c; text-decoration: underline; }
.guest-actions { display: flex; justify-content: space-between; margin-top: 24rpx; color: #4a5568; font-size: 25rpx; }
.guest-login-button { height: 76rpx; margin-top: 20rpx; border: 1rpx solid #17191c; border-radius: 999rpx; color: #17191c; font-size: 27rpx; line-height: 76rpx; text-align: center; }
.picker-value { display: flex; align-items: center; justify-content: space-between; height: 72rpx; background: #f7f8f9; border: 1rpx solid #e6e7e9; border-radius: 12rpx; padding: 0 24rpx; font-size: 28rpx; color: #17191c;
  .placeholder { color: #73777e; }
  .arrow { color: #73777e; font-size: 24rpx; }
}
.designate-toggle { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16rpx; font-size: 28rpx; color: #17191c; }
.player-select { display: flex; align-items: center; justify-content: space-between; padding: 24rpx; background: #f7f8f9; border: 1rpx solid #e6e7e9; border-radius: 12rpx; font-size: 28rpx; color: #17191c; }
.player-select .placeholder { color: #73777e; }
.player-select .arrow { color: #73777e; font-size: 24rpx; }
.selected-player { font-weight: 500; color: #17191c; }
.player-options { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:16rpx; margin-bottom:20rpx; }
.player-option { display:flex; flex-direction:column; justify-content:center; align-items:center; min-height:84rpx; padding:12rpx 16rpx; background:#f2f3f5; border-radius:12rpx; font-size:26rpx; color:#666b73; text-align:center; box-sizing:border-box; }
.player-option.active { color:#fff; background:#17191c; }
.option-rate { font-size:22rpx; margin-top:4rpx; }
.price-breakdown { display:flex; flex-direction:column; gap:8rpx; margin-top:12rpx; font-size:24rpx; color:#73777e; }
.quote-status { color:#73777e; font-size:26rpx; }
.quote-error { display:block; color:#ac3e49; font-size:24rpx; margin-top:16rpx; }
.btn-submit.disabled { opacity:0.5; }

.modal-mask {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.6); z-index: 999;
  display: flex; align-items: flex-end; justify-content: center;
}

.player-picker {
  width: 100%; max-height: 80vh; background: #ffffff;
  border-radius: 24rpx 24rpx 0 0; padding: 0 24rpx 24rpx;
  padding-bottom: calc(24rpx + var(--app-safe-bottom, env(safe-area-inset-bottom)));
  animation: slideUp 0.3s ease-out;
}

@keyframes slideUp {
  from { transform: translateY(100%); }
  to { transform: translateY(0); }
}

.picker-handle {
  display: flex; justify-content: center; padding: 16rpx 0;
  .handle-bar { width: 64rpx; height: 8rpx; background: #e6e7e9; border-radius: 4rpx; }
}

.picker-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 16rpx;
}
.picker-title { font-size: 32rpx; font-weight: bold; color: #17191c; }
.picker-close { font-size: 36rpx; color: #73777e; padding: 0 8rpx; }

.limit-tip {
  padding: 12rpx 24rpx; font-size: 24rpx; color: rgba(0,0,0,0.45);
  background: rgba(0,0,0,0.04); text-align: center;
  border-radius: 8rpx; margin-bottom: 16rpx;
  .gold { color: #17191c; font-weight: bold; }
}

.picker-search {
  display: flex; gap: 16rpx; margin-bottom: 16rpx;
  input {
    flex: 1; height: 72rpx; background: #f7f8f9;
    padding: 0 24rpx; border-radius: 999rpx;
    font-size: 26rpx; color: #17191c; box-sizing: border-box;
  }
  .search-btn {
    padding: 0 32rpx; height: 72rpx; line-height: 72rpx;
    background: linear-gradient(135deg, #17191c, #30343a);
    color: #ffffff; font-weight: bold; border-radius: 999rpx;
    font-size: 26rpx; flex-shrink: 0;
  }
}

.picker-list { max-height: 480rpx; }

.picker-item {
  display: flex; align-items: center; padding: 20rpx;
  background: rgba(0,0,0,0.02); border-radius: 12rpx; margin-bottom: 12rpx;
  &.selected { background: rgba(23, 25, 28, 0.08); }
  &.full { opacity: 0.5; }
  &.offline { opacity: 0.5; }
}
.picker-avatar { width: 80rpx; height: 80rpx; border-radius: 50%; flex-shrink: 0; margin-right: 20rpx; }
.picker-info { flex: 1; overflow: hidden; }
.picker-name-row { display: flex; align-items: center; gap: 8rpx; }
.picker-name { font-size: 28rpx; color: #17191c; font-weight: 500; }
.online-badge {
  font-size: 20rpx; color: #347a5b; background: rgba(34,197,94,0.15);
  padding: 2rpx 10rpx; border-radius: 4rpx;
}
.offline-badge {
  font-size: 20rpx; color: #73777e; background: rgba(148,163,184,0.15);
  padding: 2rpx 10rpx; border-radius: 4rpx;
}
.full-badge {
  font-size: 20rpx; color: #17191c; background: rgba(23, 25, 28, 0.15);
  padding: 2rpx 10rpx; border-radius: 4rpx;
}
.picker-tags { display: flex; gap: 8rpx; margin-top: 6rpx; flex-wrap: wrap; }
.picker-tag {
  font-size: 22rpx; color: rgba(0,0,0,0.55);
  background: rgba(0,0,0,0.05); padding: 4rpx 12rpx; border-radius: 4rpx;
  &.done { color: #347a5b; }
  &.active-tag { color: #17191c; }
}
.picker-check { font-size: 28rpx; color: #17191c; font-weight: bold; margin-left: 12rpx; }
.picker-empty { text-align: center; padding: 40rpx; font-size: 26rpx; color: #73777e; }

.picker-footer {
  display: flex; gap: 20rpx; margin-top: 20rpx;
  padding-top: 16rpx; border-top: 1rpx solid #e6e7e9;
}
.picker-btn {
  flex: 1; height: 80rpx; line-height: 80rpx;
  text-align: center; font-size: 28rpx; border-radius: 999rpx;
  &.cancel { border: 1rpx solid #e6e7e9; color: #666b73; }
  &.confirm { background: linear-gradient(135deg, #17191c, #30343a); color: #ffffff; font-weight: bold; }
}

/* 已保存的下单信息标签 */
.saved-section { padding: 24rpx 24rpx 20rpx !important; }
.saved-tags { display: flex; gap: 16rpx; flex-wrap: wrap; }
.saved-tag {
  display: flex; align-items: center; justify-content: center;
  padding: 16rpx 28rpx; background: #ffffff;
  border: 2rpx solid #e6e7e9; border-radius: 8rpx;
  font-size: 26rpx; color: #17191c;
  &.active { border-color: #17191c; color: #17191c; }
}
.saved-label { max-width: 280rpx; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.bottom-bar { position: fixed; bottom: 0; left: 0; right: 0; display: flex; height: 100rpx; background: rgba(255,255,255,0.95); border-top: 1rpx solid #e6e7e9; align-items: center; padding: 0 24rpx; padding-bottom: var(--app-safe-bottom, env(safe-area-inset-bottom));
  .total { flex: 1; font-size: 28rpx; color: #17191c; }
  .btn-submit { width: 240rpx; height: 76rpx; line-height: 76rpx; text-align: center; background: linear-gradient(135deg, #17191c, #30343a); color: #ffffff; font-weight: bold; font-size: 30rpx; border-radius: 999rpx; }
}
</style>
