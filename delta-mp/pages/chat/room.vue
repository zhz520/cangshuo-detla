<template>
  <view class="chat-room" :class="{ 'toolbar-open': showToolbar, 'keyboard-open': keyboardHeight > 0 }" :style="{ '--chat-keyboard-height': keyboardHeight + 'px' }">
    <view v-if="targetType === 'CS'" class="service-state">
      <view class="service-state-copy">
        <text class="service-state-title">{{ serviceStateTitle }}</text>
        <text class="service-state-desc">{{ serviceStateDesc }}</text>
      </view>
      <view v-if="sessionStatus === 'ACTIVE'" class="service-state-btn" @click="closeSession">结束会话</view>
      <view v-else-if="canRate && !sessionRating" class="service-state-btn primary" @click="openRating">评价服务</view>
      <view v-else-if="sessionRating" class="service-state-score">{{ sessionRating }}.0 ★</view>
    </view>
    <view ref="messagePane" class="message-pane">
    <scroll-view scroll-y class="msg-list" :scroll-into-view="scrollAnchor" @scroll="onScroll">
      <view v-for="(m, idx) in messages" :key="m.id">
        <view v-if="showTimeSep(idx)" class="time-separator">
          <text>{{ formatMsgTime(m.createdAt || m.timestamp) }}</text>
        </view>
        <ChatBubble :msg="m" :isSelf="isSelf(m)" :avatar="otherAvatar" :selfAvatar="selfAvatar" :name="otherName" :selfName="selfName" />
      </view>
      <view id="msg-end-a" style="height:2rpx;" />
      <view id="msg-end-b" style="height:2rpx;" />
    </scroll-view>
    <!-- 回到底部按钮 -->
    <view v-if="showBottomBtn" class="scroll-bottom-btn" @click="scrollToBottom">↓</view>
    </view>

    <!-- 扩展工具栏 - 固定显示 -->
    <view v-if="showToolbar" class="toolbar">
      <view class="toolbar-item" @click="sendImage">
        <image class="toolbar-icon-img" src="/static/icons/图片.svg" mode="aspectFit" />
        <text class="toolbar-label">图片</text>
      </view>
      <view class="toolbar-item" @click="openProductPicker">
        <image class="toolbar-icon-img" src="/static/icons/商品.svg" mode="aspectFit" />
        <text class="toolbar-label">商品</text>
      </view>
      <view class="toolbar-item" @click="openOrderPicker">
        <image class="toolbar-icon-img" src="/static/icons/订单.svg" mode="aspectFit" />
        <text class="toolbar-label">订单</text>
      </view>
      <view v-if="canSendSmsReminder" class="toolbar-item" @click="sendSmsReminder">
        <image class="toolbar-icon-img" src="/static/icons/短信.svg" mode="aspectFit" />
        <text class="toolbar-label">短信提醒</text>
      </view>
    </view>

    <view v-if="sessionStatus === 'CLOSED'" class="closed-input">会话已结束，如有新问题请重新发起客服会话</view>
    <view v-else class="input-bar">
      <view class="plus-btn" @mousedown.prevent @click="toggleToolbar">＋</view>
      <input v-model="inputText" placeholder="输入消息" confirm-type="send" :confirm-hold="true" :adjust-position="false" @confirm="send" @focus="onInputFocus" @blur="onInputBlur" @keyboardheightchange="onKeyboardHeightChange" />
      <view class="send-btn" @mousedown.prevent @touchend.prevent="send" @click="send">发送</view>
    </view>

    <!-- 商品选择弹窗 -->
    <view v-if="showProductPopup" class="picker-mask" @click="showProductPopup = false">
      <view class="picker-popup" @click.stop>
        <view class="picker-header">
          <text class="picker-title">选择商品发送</text>
          <text class="picker-close" @click="showProductPopup = false">✕</text>
        </view>
        <scroll-view scroll-y class="picker-list">
          <view v-for="p in productList" :key="p.id" class="picker-item" @click="doSendProduct(p)">
            <AppImage class="picker-cover" :src="p.coverImage || p.image" mode="aspectFit" />
            <view class="picker-info">
              <text class="picker-name text-ellipsis">{{ p.name }}</text>
              <text class="picker-price">¥{{ Number(p.price || 0).toFixed(2) }}</text>
            </view>
          </view>
          <view v-if="productList.length === 0" class="picker-empty">暂无商品</view>
        </scroll-view>
      </view>
    </view>

    <!-- 订单选择弹窗 -->
    <view v-if="showOrderPopup" class="picker-mask" @click="showOrderPopup = false">
      <view class="picker-popup" @click.stop>
        <view class="picker-header">
          <text class="picker-title">选择订单发送</text>
          <text class="picker-close" @click="showOrderPopup = false">✕</text>
        </view>
        <scroll-view scroll-y class="picker-list">
          <view v-for="o in orderList" :key="o.id" class="picker-item" @click="doSendOrder(o)">
            <view class="picker-info" style="flex:1">
              <text class="picker-name text-ellipsis">{{ o.productName }}</text>
              <view class="picker-meta">
                <text class="picker-price">¥{{ Number(o.amount || 0).toFixed(2) }}</text>
                <text class="picker-status">{{ ORDER_STATUS_TEXT[o.status] || o.status }}</text>
              </view>
              <text class="picker-sub">{{ o.orderNo }}</text>
            </view>
          </view>
          <view v-if="orderList.length === 0" class="picker-empty">暂无订单</view>
        </scroll-view>
      </view>
    </view>

    <view v-if="ratingVisible" class="rating-mask" @click="ratingVisible = false">
      <view class="rating-panel" @click.stop>
        <view class="rating-head">
          <text class="rating-title">评价本次客服服务</text>
          <text class="rating-close" @click="ratingVisible = false">✕</text>
        </view>
        <text class="rating-tip">你的评价会用于客服服务质量统计，每个会话只能评价一次。</text>
        <view class="rating-stars">
          <text
            v-for="star in 5"
            :key="star"
            class="rating-star"
            :class="{ active: star <= ratingValue }"
            @click="ratingValue = star"
          >★</text>
        </view>
        <textarea v-model="ratingComment" class="rating-textarea" maxlength="500" placeholder="可以补充本次服务的体验（选填）" />
        <view class="rating-actions">
          <view class="rating-cancel" @click="ratingVisible = false">取消</view>
          <view class="rating-submit" :class="{ disabled: ratingSubmitting }" @click="submitRating">{{ ratingSubmitting ? '提交中…' : '提交评价' }}</view>
        </view>
      </view>
    </view>
  </view>
</template>
<script setup>
import { useRemindStore } from '@/store/remind'
import AppImage from '@/components/AppImage.vue'
import { ref, computed, watch, nextTick, getCurrentInstance, onMounted, onUnmounted } from 'vue'
import { onLoad, onShow, onHide, onUnload } from '@dcloudio/uni-app'
import ChatBubble from '@/components/ChatBubble.vue'
import { closeChatSession, getChatMessageList, getSessionByOrderId, getSessionDetail, markMessageRead, rateChatSession, sendChatSmsReminder } from '@/api/chat'
import { getProductList } from '@/api/product'
import { getOrderList } from '@/api/order'
import { useChatStore } from '@/store/chat'
import { useUserStore } from '@/store/user'
import { usePlayerStore } from '@/store/player'
import { useAppStore } from '@/store/app'
import { chooseAndUpload } from '@/api/file'
import { sendMessage as wsSend } from '@/utils/websocket'
import { formatDateTime } from '@/utils/format'
import { ORDER_STATUS_TEXT } from '@/utils/constants'
import { USER_CHAT_SMS_REMINDERS, chooseChatSmsReminder } from '@/utils/chatSmsReminder'

const chatStore = useChatStore()
const userStore = useUserStore()
const playerStore = usePlayerStore()
const appStore = useAppStore()
const sessionId = ref(0)
const messages = ref([])
const inputText = ref('')
const scrollAnchor = ref('')
const showBottomBtn = ref(false)
const sessionReady = ref(false)
const showToolbar = ref(false)
let _isNearBottom = true
const mySenderType = computed(() => (appStore.role === 'player' ? 'PLAYER' : 'USER'))
const USER_CHAT_OPTS = { chatRole: 'USER' }
const mySenderId = computed(() =>
  appStore.role === 'player' ? playerStore.playerInfo?.id : (userStore.userInfo?.id || userStore.guestInfo?.guestUserId)
)
const selfAvatar = computed(() =>
  appStore.role === 'player' ? (playerStore.playerInfo?.avatar || '') : (userStore.avatar || userStore.userInfo?.avatar || '')
)
const selfName = computed(() =>
  appStore.role === 'player' ? (playerStore.playerInfo?.nickname || '接单员') : (userStore.userInfo?.nickname || userStore.guestInfo?.nickname || '游客')
)
const otherAvatar = ref('')
const otherName = ref('')
const targetType = ref('')
const sessionStatus = ref('ACTIVE')
const sessionRating = ref(0)
const ratingVisible = ref(false)
const ratingValue = ref(5)
const ratingComment = ref('')
const ratingSubmitting = ref(false)
const canRate = computed(() => targetType.value === 'CS' && appStore.role !== 'player')
const serviceStateTitle = computed(() => {
  if (sessionRating.value) return '本次服务已完成'
  if (sessionStatus.value === 'CLOSED') return '本次客服会话已结束'
  return '客服会话进行中'
})
const serviceStateDesc = computed(() => {
  if (sessionRating.value) return '感谢你的评价，我们会持续改进服务'
  if (sessionStatus.value === 'CLOSED') return canRate.value ? '可以为本次服务评分，评价后不可修改' : '会话已结束'
  return '问题解决后可结束会话并评价客服'
})
const canSendSmsReminder = computed(() =>
  appStore.role === 'player' ? targetType.value === 'USER' : targetType.value === 'PLAYER'
)


const pageInstance = getCurrentInstance()
const messagePane = ref(null)
const keyboardHeight = ref(0)
let messageListHeight = 0
let scrollTimer
let pageActive = true
let chatResizeObserver

// 只测量消息区，不另算 H5 视口，避免与全局 visualViewport 适配重复顶起。
function measureChatLayout() {
  nextTick(() => {
    if (!pageActive) return
    uni.createSelectorQuery().in(pageInstance.proxy).select('.msg-list').boundingClientRect(rect => {
      if (!pageActive || !rect || !rect.height) return
      messageListHeight = rect.height
      if (!showBottomBtn.value) scrollToBottom()
    }).exec()
  })
}

function onInputFocus(event) {
  showToolbar.value = false
  // #ifndef H5
  keyboardHeight.value = Math.max(0, Number(event.detail?.height) || 0)
  // #endif
  measureChatLayout()
  scrollToBottom()
}

function onInputBlur() {
  // #ifndef H5
  keyboardHeight.value = 0
  measureChatLayout()
  // #endif
}

function onKeyboardHeightChange(event) {
  // #ifndef H5
  keyboardHeight.value = Math.max(0, Number(event.detail?.height) || 0)
  measureChatLayout()
  // #endif
}

function toggleToolbar() {
  showToolbar.value = !showToolbar.value
  uni.hideKeyboard()
}

watch(showToolbar, measureChatLayout, { flush: 'post' })

onMounted(() => {
  measureChatLayout()
  // #ifdef H5
  const element = messagePane.value?.$el || messagePane.value
  if (element && typeof ResizeObserver !== 'undefined') {
    chatResizeObserver = new ResizeObserver(measureChatLayout)
    chatResizeObserver.observe(element)
  }
  window.addEventListener('resize', measureChatLayout)
  window.visualViewport?.addEventListener('resize', measureChatLayout)
  // #endif
})

onHide(() => {
  pageActive = false
  keyboardHeight.value = 0
  clearTimeout(scrollTimer)
})

onUnmounted(() => {
  pageActive = false
  clearTimeout(scrollTimer)
  // #ifdef H5
  chatResizeObserver?.disconnect()
  window.removeEventListener('resize', measureChatLayout)
  window.visualViewport?.removeEventListener('resize', measureChatLayout)
  // #endif
})

onShow(() => {
  pageActive = true
  unlockH5TabbarPageScroll()
  measureChatLayout()
})

function unlockH5TabbarPageScroll() {
  // #ifdef H5
  document.documentElement.classList.remove('tabbar-page-locked')
  document.body.classList.remove('tabbar-page-locked')
  // #endif
}

function isSelf(m) {
  if (!m) return false
  const typeMatch = m.senderType === mySenderType.value || (mySenderType.value === 'CS' && (m.senderType === 'CS' || m.senderType === 'ADMIN'))
  return typeMatch && String(m.senderId) === String(mySenderId.value)
}

const showProductPopup = ref(false)
const showOrderPopup = ref(false)
const productList = ref([])
const orderList = ref([])

function scrollToBottom() {
  clearTimeout(scrollTimer)
  scrollTimer = setTimeout(() => {
    if (!pageActive) return
    scrollAnchor.value = scrollAnchor.value === 'msg-end-a' ? 'msg-end-b' : 'msg-end-a'
    showBottomBtn.value = false
    _isNearBottom = true
  }, 150)
}

function onScroll(e) {
  const { scrollTop, scrollHeight } = e.detail
  _isNearBottom = scrollHeight - scrollTop < (e.detail.clientHeight || messageListHeight || 600) + 300
  showBottomBtn.value = !_isNearBottom
}

function normalizeDate(str) {
  if (!str) return 0
  if (typeof str === 'string' && str.includes(' ')) {
    return new Date(str.replace(' ', 'T')).getTime()
  }
  return new Date(str).getTime()
}

function showTimeSep(idx) {
  if (idx === 0) return true
  const cur = normalizeDate(messages.value[idx].createdAt || messages.value[idx].timestamp || 0)
  const prev = normalizeDate(messages.value[idx - 1].createdAt || messages.value[idx - 1].timestamp || 0)
  return cur - prev > 300000
}

function formatMsgTime(t) {
  if (!t) return ''
  const str = typeof t === 'string' && t.includes(' ') ? t.replace(' ', 'T') : t
  return formatDateTime(str)
}

onLoad(async (opts) => {
  let sid = opts.sessionId
  let sessionData = null
  if (!sid && opts.orderId) {
    try {
      const sRes = await getSessionByOrderId(opts.orderId, USER_CHAT_OPTS)
      sessionData = sRes.data
      sid = sessionData?.id
    } catch (e) {
      uni.showToast({ title: e?.data?.msg || '无法发起聊天', icon: 'none' })
      return
    }
  }
  if (!sid) {
    uni.showToast({ title: '聊天会话不存在', icon: 'none' })
    return
  }
  if (!sessionData && sid) {
    try {
      const dRes = await getSessionDetail(sid, USER_CHAT_OPTS)
      sessionData = dRes.data
    } catch (_) {}
  }
  if (sessionData) {
    otherAvatar.value = sessionData.avatar || ''
    otherName.value = sessionData.targetName || ''
    targetType.value = resolveEncodedType(sessionData.targetId)
    sessionStatus.value = sessionData.status || 'ACTIVE'
    sessionRating.value = Number(sessionData.rating || 0)
    if (sessionData.targetName) uni.setNavigationBarTitle({ title: sessionData.targetName })
  } else if (opts.name) {
    otherName.value = decodeURIComponent(opts.name)
    uni.setNavigationBarTitle({ title: otherName.value })
  }

  sessionId.value = sid
  sessionReady.value = true
  if (appStore.role === 'player' && !playerStore.playerInfo) await playerStore.fetchProfile()
  chatStore.setCurrentSession(sid)
  await loadMessages()
  markMessageRead(sid, USER_CHAT_OPTS).then(() => useRemindStore().changed()).catch(() => {})
  // 确保 WebSocket 已连接（App.vue 启动时已连，这里是兜底）
  chatStore.connect(USER_CHAT_OPTS)
})

onUnload(() => {
  // 只清除当前会话标记，不断开 WebSocket（全局保持连接）
  chatStore.clearCurrentSession()
})

// 通过 store 的 newMessage 接收 WebSocket 推送的消息
watch(() => chatStore.newMessage, (msg) => {
  if (!msg || String(msg.sessionId) !== String(sessionId.value)) return
  const exists = messages.value.some(m =>
    String(m.id) === String(msg.id) ||
    (m.type === msg.type && m.content === msg.content &&
      Math.abs(new Date(m.createdAt || 0).getTime() - new Date(msg.createdAt || 0).getTime()) < 5000)
  )
  if (!exists) {
    messages.value.push(msg)
    scrollToBottom()
  }
})

async function loadMessages() {
  const res = await getChatMessageList({ sessionId: sessionId.value, pageNum: 1, pageSize: 50, ...USER_CHAT_OPTS })
  messages.value = (res.data?.records || []).reverse()
  scrollToBottom()
}

function sendViaWs(type, content) {
  const data = {
    action: 'send',
    sessionId: sessionId.value,
    type,
    content,
    chatRole: mySenderType.value
  }
  const sent = wsSend(data)
  if (!sent) {
    uni.showToast({ title: 'WebSocket未连接', icon: 'none' })
    return false
  }
  messages.value.push({
    id: Date.now(),
    sessionId: sessionId.value,
    senderId: mySenderId.value,
    senderType: mySenderType.value,
    type,
    content,
    createdAt: new Date().toISOString()
  })
  scrollToBottom()
  return true
}

function send() {
  if (!inputText.value.trim() || !sessionReady.value) return
  const content = inputText.value
  inputText.value = ''
  sendViaWs('TEXT', content)
}

async function closeSession() {
  if (sessionStatus.value !== 'ACTIVE') return
  try {
    await new Promise((resolve, reject) => {
      uni.showModal({
        title: '结束客服会话',
        content: '结束后消息记录仍可查看，也可以评价本次客服服务。',
        confirmText: '结束会话',
        success: result => result.confirm ? resolve() : reject(new Error('cancel')),
        fail: reject
      })
    })
    await closeChatSession(sessionId.value, USER_CHAT_OPTS)
    sessionStatus.value = 'CLOSED'
    showToolbar.value = false
    uni.showToast({ title: '会话已结束，可以评价客服', icon: 'none' })
  } catch (e) {
    if (e?.message !== 'cancel' && !e?.errMsg?.includes('cancel')) {
      uni.showToast({ title: e?.msg || '结束会话失败，请重试', icon: 'none' })
    }
  }
}

function openRating() {
  if (!canRate.value || sessionRating.value || sessionStatus.value !== 'CLOSED') return
  ratingValue.value = 5
  ratingComment.value = ''
  ratingVisible.value = true
}

async function submitRating() {
  if (ratingSubmitting.value) return
  ratingSubmitting.value = true
  try {
    await rateChatSession(sessionId.value, { rating: ratingValue.value, comment: ratingComment.value.trim() }, USER_CHAT_OPTS)
    sessionRating.value = ratingValue.value
    ratingVisible.value = false
    uni.showToast({ title: '评价已提交', icon: 'none' })
  } catch (e) {
    uni.showToast({ title: e?.msg || '评价提交失败，请重试', icon: 'none' })
  } finally {
    ratingSubmitting.value = false
  }
}

async function sendSmsReminder() {
  if (!sessionReady.value) return
  showToolbar.value = false
  try {
    const selected = await chooseChatSmsReminder(USER_CHAT_SMS_REMINDERS)
    await sendChatSmsReminder(
      { sessionId: sessionId.value, reminderCode: selected.code },
      USER_CHAT_OPTS
    )
    uni.showToast({ title: `已发送${selected.label}`, icon: 'none' })
  } catch (e) {
    if (e?.errMsg?.includes('cancel')) return
  }
}

async function sendImage() {
  if (!sessionReady.value) return
  showToolbar.value = false
  try {
    const urls = await chooseAndUpload(1)
    if (urls.length) {
      sendViaWs('IMAGE', urls[0])
    }
  } catch (e) { /* cancel */ }
}

async function openProductPicker() {
  showToolbar.value = false
  showProductPopup.value = true
  try {
    const res = await getProductList({ pageNum: 1, pageSize: 20, status: 1 })
    productList.value = res.data?.records || res.data || []
  } catch (e) { productList.value = [] }
}

async function openOrderPicker() {
  showToolbar.value = false
  showOrderPopup.value = true
  try {
    const res = await getOrderList({ pageNum: 1, pageSize: 20 })
    orderList.value = res.data?.records || res.data || []
  } catch (e) { orderList.value = [] }
}

function doSendProduct(p) {
  showProductPopup.value = false
  const content = JSON.stringify({ id: p.id, name: p.name, price: p.price, coverImage: p.coverImage || p.image })
  sendViaWs('PRODUCT', content)
}

function doSendOrder(o) {
  showOrderPopup.value = false
  const content = JSON.stringify({ id: o.id, orderNo: o.orderNo, productName: o.productName, amount: o.amount, status: o.status })
  sendViaWs('ORDER', content)
}

function resolveEncodedType(encodedId) {
  const numeric = Number(encodedId || 0)
  const type = Math.floor(numeric / 1000000000)
  if (type === 2) return 'PLAYER'
  if (type === 3) return 'CS'
  return 'USER'
}
</script>
<style lang="scss" scoped>
.chat-room { background: #f7f7f7; position: relative; overflow: hidden; }
.msg-list { background: #f7f7f7; box-sizing: border-box; }
.service-state {
  display: flex;
  align-items: center;
  gap: 16rpx;
  flex-shrink: 0;
  padding: 16rpx 24rpx;
  border-bottom: 1rpx solid #eeeeee;
  background: #ffffff;
}
.service-state-copy { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3rpx; }
.service-state-title { color: #111111; font-size: 24rpx; font-weight: 750; }
.service-state-desc { color: #999999; font-size: 20rpx; line-height: 1.35; }
.service-state-btn {
  flex-shrink: 0;
  padding: 10rpx 18rpx;
  border: 1rpx solid #dedfe1;
  border-radius: 999rpx;
  color: #555555;
  font-size: 22rpx;
}
.service-state-btn.primary { border-color: #171717; background: #171717; color: #ffffff; }
.service-state-score { flex-shrink: 0; color: #c58a22; font-size: 22rpx; font-weight: 700; }
.closed-input { flex-shrink: 0; padding: 24rpx 28rpx calc(24rpx + env(safe-area-inset-bottom)); border-top: 1rpx solid #eeeeee; background: #ffffff; color: #999999; font-size: 22rpx; text-align: center; }

.scroll-bottom-btn {
  position: absolute;
  right: 32rpx;
  bottom: 180rpx;
  width: 72rpx;
  height: 72rpx;
  line-height: 72rpx;
  text-align: center;
  font-size: 36rpx;
  color: #111111;
  background: rgba(255,255,255,0.9);
  border: 1rpx solid rgba(23, 25, 28, 0.15);
  border-radius: 50%;
  z-index: 10;
  box-shadow: 0 4rpx 12rpx rgba(0,0,0,0.4);
}
.time-separator { text-align: center; padding: 16rpx 0; text { font-size: 22rpx; color: #777777; background: #f3f4f5; padding: 4rpx 16rpx; border-radius: 8rpx; } }

/* 工具栏 */
.toolbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: calc(104rpx + env(safe-area-inset-bottom));
  z-index: 19;
  display: flex;
  justify-content: space-around;
  gap: 12rpx;
  padding: 8rpx 32rpx;
  background: #ffffff;
  border-top: 1rpx solid #eeeeee;
  box-sizing: border-box;
}
.toolbar-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4rpx;
  min-width: 80rpx;
}
.toolbar-icon-img { width: 36rpx; height: 36rpx; }
.toolbar-label { font-size: 18rpx; color: #777777; }

.input-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));
  background: rgba(255,255,255,0.95);
  border-top: 1rpx solid #eeeeee;
  box-sizing: border-box;

  input { flex: 1; height: 72rpx; background: #f7f7f7; border: 1rpx solid #eeeeee; border-radius: 999rpx; padding: 0 24rpx; font-size: 28rpx; color: #111111; }
  .send-btn { margin-left: 16rpx; padding: 0 32rpx; height: 72rpx; line-height: 72rpx; background: #171717; color: #ffffff; font-weight: bold; font-size: 28rpx; border-radius: 999rpx; }
}
.plus-btn {
  width: 64rpx;
  height: 64rpx;
  line-height: 60rpx;
  text-align: center;
  font-size: 40rpx;
  color: #111111;
  border: 1rpx solid rgba(23, 25, 28, 0.15);
  border-radius: 50%;
  margin-right: 16rpx;
  flex-shrink: 0;
}

/* 弹窗选择器 */
.picker-mask {
  position: fixed;
  top: 0; left: 0; right: 0; bottom: 0;
  background: rgba(0,0,0,0.7);
  display: flex;
  align-items: flex-end;
  z-index: 999;
}
.picker-popup {
  width: 100%;
  max-height: 45vh;
  background: #ffffff;
  border-top-left-radius: 24rpx;
  border-top-right-radius: 24rpx;
  display: flex;
  flex-direction: column;
}
.picker-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 32rpx;
  border-bottom: 1rpx solid #eeeeee;
}
.picker-title { font-size: 30rpx; font-weight: bold; color: #111111; }
.picker-close { font-size: 36rpx; color: #777777; padding: 0 8rpx; }
.picker-list { flex: 1; max-height: 38vh; }
.picker-item {
  display: flex;
  align-items: center;
  padding: 20rpx 32rpx;
  border-bottom: 1rpx solid #eeeeee;
}
.picker-cover { width: 100rpx; height: 100rpx; border-radius: 8rpx; flex-shrink: 0; margin-right: 20rpx; }
.picker-info { flex: 1; overflow: hidden; }
.picker-name { font-size: 28rpx; color: #111111; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; display: block; }
.picker-price { font-size: 28rpx; color: #111111; font-weight: bold; }
.picker-meta { display: flex; align-items: center; gap: 12rpx; margin-top: 8rpx; }
.picker-status { font-size: 22rpx; color: #777777; }
.picker-sub { font-size: 22rpx; color: #777777; display: block; margin-top: 4rpx; }
.picker-empty { text-align: center; padding: 80rpx 0; color: #777777; font-size: 28rpx; }

.rating-mask { position: fixed; inset: 0; z-index: 1200; display: flex; align-items: center; justify-content: center; padding: 32rpx; background: rgba(0,0,0,.45); box-sizing: border-box; }
.rating-panel { width: 100%; max-width: 620rpx; padding: 28rpx; border-radius: 28rpx; background: #ffffff; box-sizing: border-box; }
.rating-head { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; }
.rating-title { color: #111111; font-size: 30rpx; font-weight: 800; }
.rating-close { color: #999999; font-size: 32rpx; }
.rating-tip { display: block; margin-top: 12rpx; color: #888888; font-size: 21rpx; line-height: 1.55; }
.rating-stars { display: flex; justify-content: center; gap: 18rpx; padding: 28rpx 0 20rpx; }
.rating-star { color: #d9dadd; font-size: 56rpx; line-height: 1; }
.rating-star.active { color: #c58a22; }
.rating-textarea { width: 100%; height: 150rpx; padding: 18rpx; border-radius: 16rpx; background: #f7f7f7; box-sizing: border-box; color: #111111; font-size: 24rpx; line-height: 1.55; }
.rating-actions { display: flex; gap: 16rpx; margin-top: 22rpx; }
.rating-cancel, .rating-submit { flex: 1; height: 76rpx; display: flex; align-items: center; justify-content: center; border-radius: 999rpx; font-size: 25rpx; font-weight: 700; }
.rating-cancel { background: #f2f3f4; color: #666666; }
.rating-submit { background: #171717; color: #ffffff; }
.rating-submit.disabled { opacity: .55; }

/* 消息区占满剩余空间，工具栏和输入栏按实际高度参与布局。 */
.chat-room {
  --chat-safe-bottom: env(safe-area-inset-bottom);
  display: flex;
  flex-direction: column;
  height: calc(var(--app-page-height, 100vh) - var(--chat-keyboard-height, 0px));
  min-height: 0;
  box-sizing: border-box;
}
.chat-room.keyboard-open { --chat-safe-bottom: 0px; }
.message-pane { position: relative; flex: 1; min-height: 0; overflow: hidden; }
.msg-list { height: 100%; padding: 20rpx 0; }
.scroll-bottom-btn { bottom: 24rpx; }
.toolbar, .input-bar { position: relative; left: auto; right: auto; bottom: auto; width: 100%; flex-shrink: 0; }
.toolbar { flex-wrap: wrap; }
.toolbar-item { flex-shrink: 0; }
.input-bar { padding-bottom: calc(16rpx + var(--chat-safe-bottom)); }
.input-bar input { min-width: 0; width: 0; }
.send-btn { flex-shrink: 0; white-space: nowrap; }
.picker-popup { min-height: 0; padding-bottom: var(--chat-safe-bottom); box-sizing: border-box; }
.picker-header { flex-shrink: 0; }
.picker-list { min-height: 0; }

/* #ifdef H5 */
.chat-room {
  --chat-safe-bottom: var(--app-safe-bottom, env(safe-area-inset-bottom));
  height: var(--app-visible-page-height, calc(var(--app-page-height, calc(var(--app-viewport-height, 100vh) - var(--window-top, 0px))) - var(--app-viewport-bottom, 0px)));
}
.msg-list { overscroll-behavior-y: contain; }
.input-bar input, .input-bar :deep(.uni-input-input) { font-size: 16px; }
.plus-btn, .send-btn { touch-action: manipulation; }
.picker-mask {
  left: var(--app-content-left, 0px);
  right: auto;
  width: var(--app-content-width, 100%);
  bottom: var(--app-viewport-bottom, 0px);
}
.picker-popup { max-height: min(45vh, var(--app-visible-page-height, 45vh)); }
.picker-list { max-height: calc(var(--app-visible-page-height, 100vh) - 120rpx - var(--chat-safe-bottom)); }
/* #endif */
</style>
