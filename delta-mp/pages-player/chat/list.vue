<template>
  <view class="chat-list tab-page">
    <scroll-view data-delta-content="tab" scroll-y class="session-scroll tab-page-scroll" :show-scrollbar="false">
      <view v-for="s in sessions" :key="s.id" class="session-item" @click="goRoom(s)">
        <AppImage :width="160" v-if="s.avatar" class="avatar" :src="s.avatar" mode="aspectFill" />
        <view v-else class="avatar avatar-placeholder">{{ firstChar(s.targetName || s.nickname || '') }}</view>
        <view class="session-info">
          <view class="top-row">
            <text class="name text-ellipsis">{{ s.targetName || s.nickname || '会话 #' + s.id }}</text>
            <text class="time">{{ formatRelativeTime(s.lastMessageAt) }}</text>
          </view>
          <view class="bottom-row">
            <text class="last-msg text-ellipsis">{{ s.lastMessage || '暂无消息' }}</text>
            <view v-if="s.unreadCount > 0" class="badge">{{ s.unreadCount > 99 ? '99+' : s.unreadCount }}</view>
          </view>
        </view>
      </view>
      <PageState :loading="loading && !sessions.length" :error="error" :empty="!loading && !error && !sessions.length" empty-text="暂无会话" @retry="fetchList" />
    </scroll-view>
    <CustomTabBar :current="2" />
  </view>
</template>
<script setup>
import AppImage from '@/components/AppImage.vue'
import { ref, watch } from 'vue'
import { onShow, onHide } from '@dcloudio/uni-app'
import PageState from '@/components/PageState.vue'
import CustomTabBar from '@/components/CustomTabBar.vue'
import { getSessionList } from '@/api/chat'
import { useChatStore } from '@/store/chat'
import { useRemindStore } from '@/store/remind'
import { formatRelativeTime } from '@/utils/format'

const chatStore = useChatStore()
const remindStore = useRemindStore()
const sessions = ref([])
const loading = ref(false), error = ref('')
let visible = true
onHide(() => { visible = false })

async function fetchList() {
  if (loading.value || !visible) return
  loading.value = true; error.value = ''
  try {
    const res = await getSessionList({ ...PLAYER_CHAT_OPTS, loading: false })
    sessions.value = res.data || []
  } catch (e) { error.value = '会话加载失败，请重新加载。' }
  finally { loading.value = false }
}

const PLAYER_CHAT_OPTS = { chatRole: 'PLAYER' }

onShow(async () => {
  visible = true
  try { uni.hideHomeButton() } catch (_) {}
  chatStore.connect(PLAYER_CHAT_OPTS)
  chatStore.resetUnread()
  chatStore.fetchMessageUnreadCount()
  remindStore.fetchPlayerRemind()
  await fetchList()
})

watch(() => chatStore.newMessage, () => {
  if (chatStore.newMessage) fetchList()
})
function firstChar(str) { const s = (str && String(str).trim()) || ''; return s.length ? s[0] : '?' }
function goRoom(s) {
  uni.navigateTo({ url: '/pages-player/chat/room?sessionId=' + s.id + '&name=' + encodeURIComponent(s.targetName || s.nickname || '') })
}
</script>
<style lang="scss" scoped>
.chat-list {
  background: var(--store-soft);
  height: var(--app-page-height, 100vh);
  position: relative;
  overflow: hidden;
}
.session-scroll {
  height: calc(var(--app-page-height, 100vh) - 110rpx - var(--app-safe-bottom, env(safe-area-inset-bottom)));
  position: relative;
  z-index: 1;
  padding: 20rpx 24rpx;
  padding-bottom: 30rpx;
  box-sizing: border-box;
}
.session-item {
  display: flex;
  align-items: center;
  padding: 24rpx 28rpx;
  background: var(--store-surface);
  border: 1rpx solid var(--store-line);
  border-radius: 16rpx;
  margin-bottom: 20rpx;
  position: relative;
  z-index: 1;
  box-sizing: border-box;
}
.session-item:last-of-type { margin-bottom: 0; }
.avatar {
  width: 88rpx;
  height: 88rpx;
  border-radius: 50%;
  margin-right: 20rpx;
  flex-shrink: 0;
  border: 2rpx solid var(--store-line);
}
.avatar-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  background: #777777;
  color: #fff;
  font-size: 36rpx;
  font-weight: bold;
}
.session-info { flex: 1; overflow: hidden; }
.top-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  .name { font-size: 28rpx; color: var(--store-text); font-weight: 500; flex: 1; }
  .time { font-size: 22rpx; color: var(--store-regular); flex-shrink: 0; margin-left: 16rpx; }
}
.bottom-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 8rpx;
  .last-msg { font-size: 24rpx; color: var(--store-regular); flex: 1; }
}
.badge {
  min-width: 32rpx;
  height: 32rpx;
  line-height: 32rpx;
  padding: 0 8rpx;
  font-size: 20rpx;
  color: #fff;
  background: #d44955;
  border-radius: 32rpx;
  text-align: center;
  flex-shrink: 0;
  margin-left: 16rpx;
}
</style>
