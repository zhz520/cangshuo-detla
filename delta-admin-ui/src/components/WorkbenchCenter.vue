<template>
  <el-drawer v-model="open" :title="mode === 'notices' ? '系统通知' : '待办中心'" size="min(100%, 540px)" append-to-body>
    <div class="drawer-tools">
      <template v-if="mode === 'tasks'">
        <el-select v-model="kind" aria-label="待办分类" class="kind-select" @change="reset">
          <el-option label="全部分类" value="" />
          <el-option v-for="entry in categories" :key="entry.kind" :label="entry.label" :value="entry.kind" />
        </el-select>
        <el-select v-model="filter" aria-label="待办筛选" class="filter-select" @change="reset">
          <el-option label="全部" value="ALL" /><el-option label="紧急" value="URGENT" /><el-option label="未读" value="UNREAD" />
        </el-select>
      </template>
      <el-button :disabled="!unread" :loading="saving" @click="readAll">全部已读</el-button>
    </div>
    <p v-if="mode === 'tasks'" class="muted">已读只清除提醒；业务办结后，待办才会消失。</p>
    <p v-if="error" role="alert">{{ error }} <el-button link @click="load">重试</el-button></p>
    <p v-else-if="!rows.length" class="muted">{{ loading ? '正在加载…' : (mode === 'tasks' ? '没有符合条件的待办' : '暂无通知') }}</p>
    <ul class="center-list">
      <li v-for="item in rows" :key="mode === 'tasks' ? item.kind + ':' + item.id : item.id" class="center-item">
        <template v-if="mode === 'tasks'">
          <div class="item-copy">
            <strong><i v-if="item.unread" class="unread-dot" aria-label="未读" />{{ item.title }}</strong>
            <el-tag v-if="item.urgent" size="small" type="danger">紧急</el-tag>
            <p>{{ item.reference || '编号 ' + item.id }}</p>
          </div>
          <el-button v-if="item.unread" text :disabled="saving" @click="mark(item)">已读</el-button>
          <el-button type="primary" plain :disabled="saving" @click="go(item)">去处理</el-button>
        </template>
        <template v-else>
          <div class="item-copy">
            <strong><i v-if="!item.isRead" class="unread-dot" aria-label="未读" />{{ item.title }}</strong>
            <p>{{ item.content }}</p><small>{{ item.createdAt?.replace('T', ' ') }}</small>
          </div>
          <el-button v-if="!item.isRead" text :disabled="saving" @click="mark(item)">已读</el-button>
        </template>
      </li>
    </ul>
    <div v-if="page > 1 || hasMore" class="drawer-tools">
      <el-button :disabled="page <= 1 || loading" @click="page--; load()">上一页</el-button>
      <span>第 {{ page }} 页</span>
      <el-button :disabled="!hasMore || loading" @click="page++; load()">下一页</el-button>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import request from '@/utils/request'
import { useWorkbenchStore } from '@/stores/workbench'
import { taskLocation } from '@/utils/workbench-links'

const props = defineProps({ mode: { type: String, default: 'tasks' }, initialKind: String })
const open = defineModel({ type: Boolean, default: false })
const router = useRouter()
const store = useWorkbenchStore()
const kind = ref('')
const filter = ref('ALL')
const rows = ref([])
const page = ref(1)
const hasMore = ref(false)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
let loadId = 0
const categories = computed(() => store.summary?.categories || [])
const unread = computed(() => props.mode === 'tasks' ? store.summary?.unread : store.summary?.systemUnread)

watch([open, () => props.mode, () => props.initialKind], ([shown]) => {
  if (!shown) { loadId++; return }
  filter.value = 'ALL'
  kind.value = props.initialKind || ''
  reset()
})

watch(() => store.summary, () => {
  if (open.value && !saving.value && !loading.value) load()
})

function reset() {
  page.value = 1
  rows.value = []
  hasMore.value = false
  load()
}

async function load() {
  const id = ++loadId
  const mode = props.mode
  loading.value = true
  error.value = ''
  try {
    const opts = { silent: true, loading: false }
    const res = mode === 'tasks'
      ? await request.get('/workbench/tasks', { ...opts, params: { kind: kind.value, filter: filter.value, page: page.value } })
      : await request.get('/message/list', { ...opts, params: { pageNum: page.value, pageSize: 20 } })
    if (id !== loadId) return
    rows.value = mode === 'tasks' ? res.data.tasks : res.data.records
    hasMore.value = mode === 'tasks' ? res.data.hasMore : page.value * 20 < res.data.total
    if (!rows.value.length && page.value > 1) { page.value--; return load() }
  } catch {
    if (id === loadId) error.value = '暂时无法加载，请稍后重试。'
  } finally {
    if (id === loadId) loading.value = false
  }
}

function taskRef(item) { return { kind: item.kind, id: item.id, version: item.version } }

async function mutate(run) {
  if (saving.value) return
  saving.value = true
  try { await run(); store.changed(); await load() }
  catch { error.value = '操作失败，请重试。' }
  finally { saving.value = false }
}

function mark(item) {
  return mutate(() => props.mode === 'tasks'
    ? request.post('/workbench/read', [taskRef(item)])
    : request.post('/message/read/' + item.id))
}

function readAll() {
  return mutate(() => request.post(props.mode === 'tasks' ? '/workbench/read-all' : '/message/read-all'))
}

async function go(item) {
  const target = taskLocation(item)
  if (!target) return
  saving.value = true
  try {
    await request.post('/workbench/acknowledge', taskRef(item), { silent: true, loading: false })
    store.changed()
  } catch { /* The business page is still available if marking as read fails. */ }
  finally { saving.value = false }
  await router.push(target)
  open.value = false
}
</script>

<style scoped>
.drawer-tools { display:flex; flex-wrap:wrap; gap:8px; align-items:center; }
.kind-select { width:150px; }
.filter-select { width:94px; }
.muted, small { color:#626974; }
.center-list { padding:0; margin:12px 0; list-style:none; }
.center-item { display:flex; align-items:center; gap:8px; padding:12px 0; border-bottom:1px solid #eaecf0; }
.item-copy { flex:1; min-width:0; overflow-wrap:anywhere; }
.item-copy strong { margin-right:8px; }
.item-copy p { margin:4px 0; color:#535965; white-space:pre-wrap; }
.unread-dot { display:inline-block; width:8px; height:8px; margin-right:6px; border-radius:50%; background:#c83e4b; }
@media(max-width:400px) { .center-item { gap:4px; } .center-item :deep(.el-button) { margin-left:0; padding-inline:8px; } }
</style>
