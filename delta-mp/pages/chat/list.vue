<template>
  <view class="chat-list tab-page">
    <ScreenHeader title="消息" subtitle="服务沟通 · 订单通知 · 平台动态">
      <view class="screen-icon-btn" role="button" aria-label="在线客服" @click="goCustomerService">
        <image src="/static/icons/header-headset.svg" mode="aspectFit" />
      </view>
      <view class="screen-icon-btn" role="button" aria-label="系统通知" @click="goNotifications()">
        <image src="/static/icons/header-bell.svg" mode="aspectFit" />
        <view v-if="systemUnread > 0" class="screen-dot" />
      </view>
    </ScreenHeader>
    <scroll-view scroll-y class="session-scroll tab-page-scroll" :show-scrollbar="false">
      <view class="session-content">
        <view class="message-shortcuts">
          <view class="notice-card" @click="goNotifications()">
            <view class="notice-icon">
              <image src="/static/icons/header-bell.svg" mode="aspectFit" />
              <text v-if="systemUnread > 0" class="notice-dot" />
            </view>
            <view class="notice-copy">
              <view class="notice-line">
                <text class="notice-title">系统通知</text>
                <text class="notice-time">{{ systemUnread > 0 ? systemUnread + '条' : '通知' }}</text>
              </view>
              <text class="notice-desc">订单、服务与账号提醒</text>
              <view class="notice-foot">
                <text>查看通知</text>
                <text class="notice-arrow">›</text>
              </view>
            </view>
          </view>
          <view class="notice-card" @click="goNotifications('announcements')">
            <view class="notice-icon"><image src="/static/icons/header-megaphone.svg" mode="aspectFit" /></view>
            <view class="notice-copy">
              <view class="notice-line">
                <text class="notice-title">平台公告</text>
                <text class="notice-time">{{ latestNoticeTime || '公告' }}</text>
              </view>
              <text class="notice-desc">{{ latestNotice?.title || '规则更新与重要通知' }}</text>
              <view class="notice-foot">
                <text>查看公告</text>
                <text class="notice-arrow">›</text>
              </view>
            </view>
          </view>
        </view>
        <view class="section-row">
          <text class="section-title">最近会话</text>
          <view class="section-actions">
            <text class="section-count">{{ sessions.length }} 个</text>
            <text v-if="sessions.length" class="mark-read" @click="markAllSessionsRead">✓ 全部已读</text>
          </view>
        </view>
        <PageState v-if="!sessions.length && (loading || error)" :loading="loading" :error="error" @retry="fetchList" />
        <view v-else-if="!sessions.length" class="empty-session">
          <image class="empty-icon" src="/static/icons/empty-chat.svg" mode="aspectFit" />
          <text class="empty-title">暂无会话</text>
          <text class="empty-desc">下单后可在这里与客服或接单员沟通</text>
        </view>
        <view v-else class="session-list">
        <view v-for="s in sessions" :key="s.id" class="session-item" @click="goRoom(s)">
          <AppImage :width="160" class="avatar" :src="s.avatar" fallback="/static/images/delta-default-avatar.png" mode="aspectFill" />
          <view class="session-info">
            <view class="top-row">
              <text class="name text-ellipsis">{{ s.targetName || s.nickname || '会话 #' + s.id }}</text>
              <text v-if="s.status === 'CLOSED' && !s.rating" class="rating-tag">待评价</text>
              <text class="time">{{ sessionTime(s.lastMessageAt) }}</text>
            </view>
            <view class="bottom-row">
              <text class="last-msg text-ellipsis">{{ s.lastMessage || '暂无消息' }}</text>
              <view v-if="s.unreadCount > 0" class="badge">{{ s.unreadCount > 99 ? '99+' : s.unreadCount }}</view>
              <text class="session-arrow">›</text>
            </view>
          </view>
        </view>
        </view>
      </view>
    </scroll-view>
    <CustomTabBar :current="3" />
  </view>
</template>
<script setup>
import AppImage from '@/components/AppImage.vue'
import { computed, ref, watch } from 'vue'
import { onShow, onHide } from '@dcloudio/uni-app'
import PageState from '@/components/PageState.vue'
import CustomTabBar from '@/components/CustomTabBar.vue'
import ScreenHeader from '@/components/ScreenHeader.vue'
import { getSessionList } from '@/api/chat'
import { markMessageRead } from '@/api/chat'
import { getActiveNotices } from '@/api/notice'
import { openCustomerService } from '@/utils/customer-service'
import { useChatStore } from '@/store/chat'
import { useRemindStore } from '@/store/remind'
const chatStore = useChatStore()
const remindStore = useRemindStore()
const systemUnread = computed(() => remindStore.systemUnread)
const sessions = ref([])
const loading = ref(false), error = ref('')
const notices = ref([])
const latestNotice = computed(() => notices.value[0] || null)
const latestNoticeTime = computed(() => {
  const value = latestNotice.value?.createdAt || latestNotice.value?.createTime
  if (!value) return ''
  const d = new Date(String(value).replace(' ', 'T'))
  return Number.isNaN(d.getTime()) ? '' : (d.getMonth() + 1) + '-' + d.getDate()
})
let visible = true
onHide(() => { visible = false })

const USER_CHAT_OPTS = { chatRole: 'USER' }

async function fetchList() {
  if (loading.value || !visible) return
  loading.value = true; error.value = ''
  try {
    const res = await getSessionList({ ...USER_CHAT_OPTS, loading: false })
    sessions.value = res.data || []
  } catch (e) { error.value = '会话加载失败，请重新加载。' }
  finally { loading.value = false }
}

onShow(async () => {
  visible = true
  chatStore.connect(USER_CHAT_OPTS)
  remindStore.refreshBadges()
  loadNotices()
  await fetchList()
})

async function loadNotices() {
  try {
    const res = await getActiveNotices({ loading: false, silent: true })
    const list = res.data?.records || res.data
    notices.value = Array.isArray(list) ? list : []
  } catch (_) {
    notices.value = []
  }
}

let markingSessions = false
async function markAllSessionsRead() {
  if (markingSessions) return
  const unread = sessions.value.filter(item => Number(item.unreadCount || 0) > 0)
  if (!unread.length) {
    uni.showToast({ title: '暂无未读消息', icon: 'none' })
    return
  }
  markingSessions = true
  try {
    const results = await Promise.allSettled(unread.map(async item => {
      const count = Number(item.unreadCount) || 0
      await markMessageRead(item.id, { ...USER_CHAT_OPTS, silent: true, loading: false })
      item.unreadCount = 0
      return count
    }))
    const read = results.reduce((total, result) => total + (result.status === 'fulfilled' ? result.value : 0), 0)
    remindStore.readConfirmed('chatUnread', Math.max(0, remindStore.messageUnread - read))
    uni.showToast({ title: results.every(result => result.status === 'fulfilled') ? '已全部标为已读' : '部分会话未标记，请重试', icon: 'none' })
  } finally { markingSessions = false }
}

watch(() => chatStore.newMessage, () => {
  if (chatStore.newMessage) fetchList()
})
function sessionTime(value) {
  if (!value) return ''
  const date = new Date(String(value).replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return ''
  const now = new Date()
  const sameDay = date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate()
  if (sameDay) return String(date.getHours()).padStart(2, '0') + ':' + String(date.getMinutes()).padStart(2, '0')
  const yesterday = new Date(now.getFullYear(), now.getMonth(), now.getDate() - 1)
  if (date.getFullYear() === yesterday.getFullYear()
    && date.getMonth() === yesterday.getMonth()
    && date.getDate() === yesterday.getDate()) return '昨天'
  return String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0')
}
function goRoom(s) { uni.navigateTo({ url: '/pages/chat/room?sessionId=' + s.id + '&name=' + encodeURIComponent(s.targetName || s.nickname || '') }) }
function goNotifications(section) {
  uni.navigateTo({ url: '/pages/message/index' + (section === 'announcements' ? '?section=announcements' : '') })
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
.chat-list {
  height: var(--app-page-height, 100vh);
  min-height: var(--app-page-height, 100vh);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--store-soft);
}
.session-scroll {
  flex: 1;
  height: 0;
  min-height: 0;
  padding: 2rpx 24rpx 0;
  box-sizing: border-box;
}
.session-content { padding-bottom: 30rpx; }
.message-shortcuts {
  display: grid;
  gap: 12rpx;
  margin-bottom: 24rpx;
}
.notice-card {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 18rpx;
  border: 0;
  border-radius: 22rpx;
  background: var(--store-surface);
  box-shadow: none;
}
.notice-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 68rpx;
  height: 68rpx;
  flex-shrink: 0;
  border-radius: 20rpx;
  background: var(--store-soft);
  image { width: 36rpx; height: 36rpx; }
  image { filter: grayscale(1) brightness(0.25); }
}
.notice-copy {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5rpx;
}
.notice-title { color: var(--store-text); font-size: 26rpx; font-weight: 800; line-height: 1.3; }
.notice-desc { color: #8a8f96; font-size: 20rpx; line-height: 1.4; }
.notice-time { flex-shrink: 0; color: #a0a5ac; font-size: 18rpx; }
.notice-arrow { flex-shrink: 0; color: var(--store-muted); font-size: 30rpx; line-height: 1; }
.section-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4rpx 2rpx 12rpx;
}
.section-title { color: var(--store-text); font-size: 28rpx; font-weight: 800; }
.section-count { color: var(--store-muted); font-size: 19rpx; }
.session-item {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 18rpx;
  margin-bottom: 0;
  border: 0;
  border-bottom: 0;
  border-radius: 0;
  background: var(--store-surface);
  box-shadow: none;
  box-sizing: border-box;
}
.session-item:last-child { border-bottom: 0; }
.session-list {
  overflow: hidden;
  border: 0;
  border-radius: 22rpx;
  background: var(--store-surface);
}
.avatar {
  width: 88rpx;
  height: 88rpx;
  margin-right: 0;
  flex-shrink: 0;
  border: 0;
  border-radius: 50%;
}
.avatar-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--store-line);
  color: #6b7077;
  font-size: 32rpx;
  font-weight: 800;
}
.session-info { flex: 1; min-width: 0; overflow: hidden; }
.top-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
  .name { flex: 1; color: var(--store-text); font-size: 27rpx; font-weight: 750; }
  .time { flex-shrink: 0; color: var(--store-muted); font-size: 18rpx; }
}
.bottom-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12rpx;
  margin-top: 7rpx;
  .last-msg { flex: 1; color: var(--store-regular); font-size: 21rpx; }
}
.badge {
  min-width: 30rpx;
  height: 30rpx;
  padding: 0 7rpx;
  border-radius: 999rpx;
  background: #e14c4c;
  color: #ffffff;
  font-size: 18rpx;
  line-height: 30rpx;
  text-align: center;
  flex-shrink: 0;
}
</style>
<style lang="scss" scoped>
.chat-list { height: var(--app-page-height, 100vh); min-height: var(--app-page-height, 100vh); display: flex; flex-direction: column; overflow: hidden; background: var(--store-soft); }
.session-scroll { flex: 1; height: 0; min-height: 0; width: 100%; padding: 0; }
.session-content { padding: 8rpx 28rpx calc(190rpx + var(--app-safe-bottom, env(safe-area-inset-bottom))); box-sizing: border-box; }
.message-shortcuts { display: flex; flex-direction: column; overflow: hidden; border-radius: 24rpx; background: var(--store-surface); margin-bottom: 28rpx; }
.notice-card { display: flex; align-items: center; gap: 16rpx; min-width: 0; min-height: 88rpx; padding: 16rpx 22rpx; box-sizing: border-box; }
.notice-card + .notice-card { border-top: 1rpx solid var(--store-line); }
.notice-icon { position: relative; display: flex; align-items: center; justify-content: center; width: 48rpx; height: 48rpx; flex: 0 0 48rpx; background: transparent; }
.notice-icon image { display: block; width: 42rpx; height: 42rpx; filter: none; }
.notice-copy { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8rpx; }
.notice-line { display: flex; align-items: center; justify-content: space-between; gap: 8rpx; min-width: 0; }
.notice-dot { position: absolute; top: 0; right: 0; width: 12rpx; height: 12rpx; border: 2rpx solid #ffffff; border-radius: 50%; background: #e24d4d; box-sizing: content-box; }
.notice-title { min-width: 0; overflow: hidden; color: var(--store-text); font-size: 26rpx; font-weight: 800; line-height: 1.25; white-space: nowrap; text-overflow: ellipsis; }
.notice-time { flex-shrink: 0; color: #a0a5ac; font-size: 18rpx; line-height: 1.25; }
.notice-desc { min-height: 54rpx; color: #8a8f96; font-size: 21rpx; line-height: 1.45; display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.notice-foot { display: flex; align-items: center; justify-content: space-between; color: #a0a5ac; font-size: 19rpx; }
.notice-arrow { flex-shrink: 0; color: #bbbbbb; font-size: 28rpx; line-height: 1; }
.section-row { display: flex; align-items: center; justify-content: space-between; gap: 16rpx; padding: 0 4rpx 18rpx; }
.section-title { color: var(--store-text); font-size: 30rpx; font-weight: 850; letter-spacing: -0.02em; }
.section-actions { display: flex; align-items: center; gap: 16rpx; flex-shrink: 0; }
.section-count { color: var(--store-muted); font-size: 22rpx; }
.mark-read { color: var(--store-regular); font-size: 22rpx; }
.session-list { background: transparent; border: 0; overflow: visible; }
.session-item { display: flex; align-items: center; gap: 18rpx; padding: 22rpx; margin-bottom: 14rpx; border: 0; border-radius: 24rpx; background: var(--store-surface); box-shadow: none; box-sizing: border-box; }
.avatar { width: 96rpx; height: 96rpx; flex-shrink: 0; border: 0; border-radius: 20rpx; background: var(--store-soft); }
.avatar-placeholder { display: flex; align-items: center; justify-content: center; background: var(--store-soft); color: #6b7077; font-size: 34rpx; font-weight: 800; }
.session-info { flex: 1; min-width: 0; overflow: hidden; }
.top-row { display: flex; align-items: center; justify-content: space-between; gap: 12rpx; }
.top-row .name { flex: 1; min-width: 0; color: var(--store-text); font-size: 27rpx; font-weight: 800; }
.top-row .time { flex-shrink: 0; color: var(--store-muted); font-size: 20rpx; }
.rating-tag { flex-shrink: 0; padding: 3rpx 10rpx; border-radius: 999rpx; background: #f7eee0; color: #9a6b2d; font-size: 18rpx; line-height: 1.3; }
.bottom-row { display: flex; align-items: center; gap: 12rpx; margin-top: 10rpx; }
.bottom-row .last-msg { flex: 1; min-width: 0; color: var(--store-regular); font-size: 22rpx; line-height: 1.45; display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.badge { min-width: 32rpx; height: 32rpx; padding: 0 8rpx; border-radius: 999rpx; background: #e14c4c; color: #ffffff; font-size: 19rpx; line-height: 32rpx; text-align: center; flex-shrink: 0; }
.session-arrow { flex-shrink: 0; color: #c0c0c0; font-size: 34rpx; line-height: 1; }
.empty-session { display: flex; flex-direction: column; align-items: center; padding: 84rpx 24rpx; border-radius: 24rpx; background: var(--store-surface); }
.empty-icon { width: 120rpx; height: 120rpx; opacity: .48; }
.empty-title { margin-top: 18rpx; color: var(--store-text); font-size: 30rpx; font-weight: 800; }
.empty-desc { margin-top: 8rpx; color: var(--store-muted); font-size: 22rpx; }
</style>
