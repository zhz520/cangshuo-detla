<template>
  <section class="workbench-card" aria-labelledby="workbench-title">
    <div class="workbench-heading">
      <div><h3 id="workbench-title">今日待办</h3><p>待处理事项 · 优先处理紧急任务</p></div>
      <el-button type="primary" plain @click="openCenter('')">查看全部</el-button>
    </div>
    <div class="metrics">
      <div v-for="entry in metrics" :key="entry.key">
        <strong :class="{ urgent: entry.key === 'urgent' && summary?.urgent }">{{ summary?.[entry.key] ?? '—' }}</strong>
        <span>{{ entry.label }}</span>
      </div>
    </div>
    <p v-if="store.error" class="hint" role="alert">{{ store.error }} <el-button link @click="store.refresh">重试</el-button></p>
    <div v-if="activeCategories.length" class="categories">
      <button v-for="item in activeCategories" :key="item.kind" type="button" @click="openCenter(item.kind)">
        {{ item.label }} <b>{{ item.pending }}</b><i v-if="item.unread" aria-label="有未读待办" />
      </button>
    </div>
    <ul v-if="tasks.length" class="preview">
      <li v-for="task in tasks" :key="task.kind + ':' + task.id">
        <div><strong>{{ task.title }}</strong><span>{{ task.reference || '编号 ' + task.id }}</span></div>
        <el-button link type="primary" @click="go(task)">去处理</el-button>
      </li>
    </ul>
    <p v-else-if="summary && !summary.pending" class="hint">待办已清空。</p>
    <WorkbenchCenter v-model="open" :initial-kind="initialKind" />
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useWorkbenchStore } from '@/stores/workbench'
import { taskLocation } from '@/utils/workbench-links'
import request from '@/utils/request'
import WorkbenchCenter from './WorkbenchCenter.vue'

const router = useRouter()
const store = useWorkbenchStore()
const summary = computed(() => store.summary)
const activeCategories = computed(() => (summary.value?.categories || []).filter(item => item.pending))
const metrics = [
  { key: 'pending', label: '待处理' }, { key: 'urgent', label: '紧急' },
  { key: 'unread', label: '未读' }, { key: 'handledToday', label: '今日已处理' }
]
const tasks = ref([])
const open = ref(false)
const initialKind = ref('')
watch(() => summary.value?.categories, async (categories, _, onCleanup) => {
  let cancelled = false
  onCleanup(() => { cancelled = true })
  if (!categories?.some(item => item.urgent)) { tasks.value = []; return }
  try {
    const { data } = await request.get('/workbench/tasks', { params: { filter: 'URGENT', size: 5 }, silent: true, loading: false })
    if (!cancelled) tasks.value = data.tasks
  } catch { if (!cancelled) tasks.value = [] }
}, { immediate: true })

function openCenter(kind) { initialKind.value = kind; open.value = true }
async function go(task) {
  const destination = taskLocation(task)
  if (!destination) return
  try {
    const { kind, id, version } = task
    await request.post('/workbench/acknowledge', { kind, id, version }, { silent: true, loading: false })
    store.changed()
  } catch { /* Continue to the business page. */ }
  router.push(destination)
}
</script>

<style scoped>
.workbench-card { background:#fff; border:1px solid #e0e3e8; border-top:3px solid #17191c; border-radius:12px; padding:clamp(12px,2.2vw,22px); margin-bottom:20px; }
.workbench-heading { display:flex; justify-content:space-between; align-items:flex-start; gap:10px; }
.workbench-heading h3 { margin:0; font-size:20px; }
.workbench-heading p, .hint { margin:6px 0; color:#626974; font-size:13px; }
.metrics { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:8px; margin-top:14px; }
.metrics div { border-radius:8px; padding:10px 12px; background:#f4f5f7; }
.metrics strong { font-size:23px; display:block; }
.metrics span { color:#59616d; font-size:12px; }
.metrics .urgent { color:#bd343e; }
.categories { display:flex; flex-wrap:wrap; gap:8px; margin-top:12px; }
.categories button { background:#fff; border:1px solid #e1e4e8; border-radius:999px; padding:6px 10px; cursor:pointer; color:inherit; }
.categories i { display:inline-block; width:7px; height:7px; margin-left:5px; border-radius:50%; background:#c83e4b; }
.preview { margin:10px 0 0; padding:0; list-style:none; }
.preview li { display:flex; align-items:center; justify-content:space-between; gap:10px; border-top:1px solid #eaecf0; padding:7px 0; }
.preview li span { display:block; overflow-wrap:anywhere; font-size:12px; color:#626974; }
@media(max-width:420px) { .metrics { grid-template-columns:repeat(2,minmax(0,1fr)); } }
</style>
