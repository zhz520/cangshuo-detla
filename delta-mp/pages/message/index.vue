<template>
  <view class="message-page">
    <ScreenHeader :title="announcements ? '平台公告' : '系统通知'" back center>
      <view v-if="!announcements" class="read-all" @click="markAllReadClick">全部已读</view>
    </ScreenHeader>
    <scroll-view v-if="announcements" scroll-y class="list">
      <PageState v-if="loading || noticeError" :loading="loading" :error="noticeError" @retry="loadNotices" />
      <view v-else-if="!notices.length" class="empty-page">
        <image class="empty-page-icon" src="/static/icons/header-megaphone.svg" mode="aspectFit" />
        <text class="empty-page-title">暂无平台公告</text>
        <text class="empty-page-desc">平台公告发布后会显示在这里</text>
      </view>
      <view v-else>
        <view v-for="notice in notices" :key="notice.id" class="msg-item">
          <view class="msg-header" role="button" :aria-expanded="openNoticeId === notice.id" @click="openNoticeId = openNoticeId === notice.id ? null : notice.id">
            <text class="msg-title">{{ notice.title }}</text>
            <text class="notice-expand">{{ openNoticeId === notice.id ? '收起' : '展开' }}</text>
          </view>
          <text v-if="notice.createdAt || notice.createTime" class="msg-time">{{ notice.createdAt || notice.createTime }}</text>
          <rich-text v-if="openNoticeId === notice.id" class="msg-content" :nodes="productDescriptionHtml(notice.content || '')" />
        </view>
      </view>
    </scroll-view>
    <scroll-view v-else scroll-y class="list" @scrolltolower="loadMore">
      <view v-for="m in messages" :key="m.id" class="msg-item" @click="onTap(m)">
        <view class="msg-header">
          <text class="msg-title text-ellipsis">{{ m.title }}</text>
          <text v-if="!m.isRead" class="dot"></text>
        </view>
        <text class="msg-content text-ellipsis-2">{{ m.content }}</text>
        <text class="msg-time">{{ m.createdAt }}</text>
      </view>
      <view v-if="loading" class="loading-tip">加载中…</view>
      <view v-if="!loading && messages.length===0" class="empty-page">
        <image class="empty-page-icon" src="/static/icons/header-bell.svg" mode="aspectFit" />
        <text class="empty-page-title">暂无通知</text>
        <text class="empty-page-desc">订单和服务消息会显示在这里</text>
      </view>
    </scroll-view>
  </view>
</template>
<script setup>
import { clientStorage } from '@/utils/client-storage'
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import ScreenHeader from '@/components/ScreenHeader.vue'
import PageState from '@/components/PageState.vue'
import { getMessageList, markRead, markAllRead } from '@/api/message'
import { getActiveNotices } from '@/api/notice'
import { productDescriptionHtml } from '@/utils/product-content'
import { useRemindStore } from '@/store/remind'
import { getCurrentRole } from '@/utils/auth'

const messages = ref([])
const pageNum = ref(1)
const loading = ref(false)
const finished = ref(false)
const announcements = ref(false)
const notices = ref([])
const noticeError = ref('')
const openNoticeId = ref(null)

onLoad((options) => {
  announcements.value = options?.section === 'announcements'
  if (announcements.value) loadNotices()
  else loadData()
})
onShow(() => { if (!announcements.value) useRemindStore().refresh() })
async function loadNotices() {
  loading.value = true
  noticeError.value = ''
  try {
    const res = await getActiveNotices({ loading: false, silent: true })
    const list = res.data?.records || res.data
    notices.value = Array.isArray(list) ? list : []
  } catch {
    noticeError.value = '平台公告加载失败，请重试。'
  } finally { loading.value = false }
}
async function loadData() {
  loading.value = true
  const params = { pageNum: pageNum.value, pageSize: 20 }
  try {
    const res = await getMessageList(params)
    const list = res.data?.records || []
    if (list.length < 20) finished.value = true
    messages.value = pageNum.value === 1 ? list : [...messages.value, ...list]
  } catch {
    if (pageNum.value > 1) pageNum.value--
  } finally { loading.value = false }
}
function loadMore() { if (!loading.value && !finished.value) { pageNum.value++; loadData() } }
async function onTap(m) {
  if (!m.isRead) { await markRead(m.id); m.isRead = 1; useRemindStore().changed() }
  const role = getCurrentRole()
  const type = m.bizType
  const relatedId = encodeURIComponent(m.relatedId || '')
  let url = ''
  if (type === 'IDENTITY_REVIEWED' && role === 'user') url = '/pages/mine/identity'
  if (type === 'MATERIAL_REVIEWED' && role === 'player') url = '/pages-player/profile/introduction'
  if (['WITHDRAW_COMPLETED', 'WITHDRAW_REJECTED'].includes(type) && role === 'player') url = '/pages-player/withdraw/list'
  if (type === 'TEAMMATE_INVITED' && role === 'player') url = '/pages-player/invite/list?orderId=' + relatedId
  if (relatedId && type?.startsWith('ORDER_')) {
    const prefix = { user: '/pages', player: '/pages-player' }[role]
    if (prefix) url = prefix + '/order/detail?id=' + relatedId
  }
  if (url) uni.navigateTo({ url })
}

async function markAllReadClick() {
  try {
    const role = clientStorage.get('app_role') || 'user'
    const userType = role === 'cs' ? 'CS' : role === 'player' ? 'PLAYER' : 'USER'
    await markAllRead(userType)
    uni.showToast({ title: '已全部标为已读', icon: 'none' })
    messages.value.forEach(m => { m.isRead = 1 })
    useRemindStore().changed()
  } catch (e) {
    uni.showToast({ title: '操作失败', icon: 'none' })
  }
}
</script>
<style lang="scss" scoped>
.message-page { display: flex; flex-direction: column; height: var(--app-page-height, 100vh); min-height: 0; background: #f7f7f7; overflow: hidden; }
.read-all { padding: 9rpx 16rpx; border: 1rpx solid #171717; border-radius: 14rpx; color: #171717; font-size: 20rpx; font-weight: 700; }
.header-row { display: flex; align-items: center; justify-content: space-between; padding: 24rpx 24rpx 20rpx; background: #f7f7f7; border-bottom: 1rpx solid #eeeeee; }
.page-title { font-size: 32rpx; font-weight: bold; color: #111111; }
.btn-read-all { font-size: 28rpx; color: #111111; }
.list { flex: 1; height: 0; min-height: 0; padding: 0 24rpx 30rpx; box-sizing: border-box; }
.msg-item { padding: 22rpx; margin-top: 12rpx; background: #ffffff; border: 1rpx solid #eeeeee; border-radius: 20rpx; }
.msg-header { display: flex; align-items: center; gap: 8rpx;
  .msg-title { font-size: 28rpx; font-weight: 500; color: #111111; flex: 1; }
  .dot { width: 14rpx; height: 14rpx; background: #d44955; border-radius: 50%; flex-shrink: 0; }
}
.msg-content { font-size: 26rpx; color: #777777; margin-top: 8rpx; display: block; }
.notice-expand { color: #666666; font-size: 22rpx; flex-shrink: 0; }
.msg-time { font-size: 22rpx; color: #777777; margin-top: 8rpx; display: block; }
.loading-tip { text-align: center; padding: 32rpx; font-size: 24rpx; color: #777777; }
</style>
<style lang="scss" scoped>
.read-all { padding: 0; border: 0; color: #777777; font-size: 23rpx; font-weight: 650; }
.list { padding: 8rpx 28rpx calc(34rpx + var(--app-safe-bottom, env(safe-area-inset-bottom))); }
.msg-item { padding: 26rpx; margin-top: 14rpx; background: #ffffff; border: 0; border-radius: 24rpx; }
.msg-header .msg-title { font-size: 30rpx; font-weight: 800; color: #111111; }
.msg-header .dot { width: 14rpx; height: 14rpx; background: #e24d4d; }
.msg-content { font-size: 24rpx; color: #666666; line-height: 1.7; margin-top: 14rpx; }
.notice-expand { color: #111111; font-size: 22rpx; font-weight: 650; }
.msg-time { font-size: 21rpx; color: #aaaaaa; margin-top: 10rpx; }
.empty-page { display: flex; flex-direction: column; align-items: center; padding: 90rpx 24rpx; margin-top: 14rpx; border-radius: 24rpx; background: #ffffff; }
.empty-page-icon { width: 112rpx; height: 112rpx; opacity: .45; }
.empty-page-title { margin-top: 18rpx; color: #111111; font-size: 30rpx; font-weight: 800; }
.empty-page-desc { margin-top: 8rpx; color: #999999; font-size: 22rpx; }
</style>
