<template>
  <view class="workbench-page">
    <view class="toolbar">
      <text class="title">待办事项</text>
      <button size="mini" :disabled="saving || !store.workbenchUnread" @click="allRead">全部已读</button>
    </view>
    <view class="tabs">
      <button v-for="choice in filters" :key="choice.value" size="mini" :class="{ active: choice.value === filter }" @click="choose(choice.value)">{{ choice.label }}</button>
    </view>
    <text class="hint">已读只清除提醒，业务办结后才会从待办移除。</text>
    <view v-if="error" class="hint" @click="load">{{ error }} · 点此重试</view>
    <text v-else-if="!items.length" class="hint">{{ loading ? '加载中…' : '暂无待办' }}</text>
    <view v-for="item in items" :key="item.kind + ':' + item.id" class="task">
      <view class="task-copy">
        <text v-if="item.unread" class="dot" aria-label="未读" />
        <text class="task-title">{{ item.title }}</text>
        <text v-if="item.urgent" class="urgent">紧急</text>
        <text class="hint">{{ item.reference || '编号 ' + item.id }}</text>
        <text v-if="!destination(item)" class="hint">请在网页管理后台处理</text>
      </view>
      <button v-if="item.unread" size="mini" :disabled="saving" @click="seen(item)">已读</button>
      <button v-if="destination(item)" size="mini" class="active" :disabled="saving" @click="openTask(item)">去处理</button>
    </view>
    <view v-if="page > 1 || more" class="tabs">
      <button size="mini" :disabled="page <= 1 || loading" @click="page--; load()">上一页</button>
      <button size="mini" :disabled="!more || loading" @click="page++; load()">下一页</button>
    </view>
  </view>
</template>

<script setup>
import { ref, watch } from 'vue'
import { onHide, onShow } from '@dcloudio/uni-app'
import { getTasks, readTask, acknowledgeTask, readAllTasks } from '@/api/workbench'
import { useRemindStore } from '@/store/remind'
import { getCurrentRole, getTokenByRole } from '@/utils/auth'

const filters = [{ label: '全部', value: 'ALL' }, { label: '紧急', value: 'URGENT' }, { label: '未读', value: 'UNREAD' }]
const filter = ref('ALL')
const items = ref([])
const page = ref(1)
const more = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const store = useRemindStore()
let generation = 0
let visible = false
const links = {
  PAY: '/pages/order/detail?id=', CONFIRM: '/pages/order/detail?id=',
  ACCEPT: '/pages-player/order/detail?id=', START: '/pages-player/order/detail?id=',
  COMPLAINT: '/pages-cs/complaint/handle?id=', WITHDRAW: '/pages-cs/withdraw/handle?id=',
  RELAY: '/pages-cs/relay/list?taskId=', REPLACE: '/pages-cs/replace/list?taskId=',
  ENROLLMENT: '/pages-cs/player/list?taskId=', CHAT: '/pages-cs/chat/room?sessionId='
}
function destination(task) {
  if (task.kind === 'ASSIGN') return '/pages-cs/order/detail?id=' + encodeURIComponent(task.id)
  if (task.kind === 'DEPOSIT') return '/pages-player/withdraw/index'
  if (task.kind === 'INVITE') return '/pages-player/invite/list?orderId=' + encodeURIComponent(task.reference || '')
  return links[task.kind] ? links[task.kind] + encodeURIComponent(task.id) : ''
}
async function load() {
  if (!getTokenByRole(getCurrentRole())) return
  const id = ++generation
  loading.value = true
  error.value = ''
  try {
    const { data } = await getTasks({ filter: filter.value, page: page.value })
    if (id !== generation) return
    items.value = data.tasks || []
    more.value = data.hasMore
    if (!items.value.length && page.value > 1) { page.value--; return load() }
  } catch {
    if (id === generation) error.value = '暂时无法加载'
  } finally {
    if (id === generation) loading.value = false
  }
}
function choose(value) { filter.value = value; page.value = 1; items.value = []; more.value = false; load() }
function reference({ kind, id, version }) { return { kind, id, version } }
async function mutate(fn) {
  if (saving.value) return
  saving.value = true
  try { await fn(); store.changed(); await load() }
  catch { uni.showToast({ title: '操作失败，请重试', icon: 'none' }) }
  finally { saving.value = false }
}
const seen = item => mutate(() => readTask(reference(item)))
const allRead = () => mutate(readAllTasks)
async function openTask(item) {
  const url = destination(item)
  if (!url || saving.value) return
  saving.value = true
  try { await acknowledgeTask(reference(item)); store.changed() }
  catch { /* The destination still checks access. */ }
  finally { saving.value = false }
  uni.navigateTo({ url })
}
watch(() => store.summary, () => { if (visible && !loading.value && !saving.value) load() })
onShow(() => { visible = true; store.refresh(); load() })
onHide(() => { visible = false; generation++ })
</script>

<style scoped>
.workbench-page { min-height:100vh; padding:24rpx; background:#f5f6f7; }
.toolbar,.tabs,.task { display:flex; align-items:center; gap:10rpx; }
.toolbar { justify-content:space-between; }
.toolbar button,.tabs button,.task button { margin:0; }
.tabs { flex-wrap:wrap; margin:16rpx 0; }
.title,.task-title { font-weight:700; }
.hint { display:block; color:#626974; font-size:24rpx; margin:10rpx 0; }
.task { border:1rpx solid #e6e8eb; border-radius:12rpx; background:#fff; margin:12rpx 0; padding:18rpx 12rpx 18rpx 18rpx; }
.task-copy { flex:1; min-width:0; overflow-wrap:anywhere; }
.task-title { font-size:28rpx; }
.urgent { font-size:22rpx; margin-left:8rpx; color:#a83842; }
.dot { display:inline-block; width:12rpx; height:12rpx; margin-right:8rpx; background:#c83e4b; border-radius:50%; }
button.active { color:#fff; background:#22262b; }
</style>
