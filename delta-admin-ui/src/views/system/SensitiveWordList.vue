<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <div>
            <strong>敏感词管理</strong>
            <p class="subtitle">启用的敏感词会用于打手主页资料检测；聊天和评价中的命中词也会被拦截或替换。</p>
          </div>
          <el-button type="primary" @click="openCreate">新增敏感词</el-button>
        </div>
      </template>
      <div class="toolbar">
        <el-input v-model="query.keyword" clearable maxlength="64" placeholder="搜索敏感词" @keyup.enter="search" />
        <el-select v-model="query.category" clearable placeholder="全部分类" @change="search">
          <el-option v-for="item in categories" :key="item.value" :value="item.value" :label="item.label" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column prop="word" label="敏感词" min-width="180" show-overflow-tooltip />
        <el-table-column label="分类" width="120"><template #default="{ row }">{{ categoryLabel(row.category) }}</template></el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-switch :model-value="Number(row.status) === 1" :loading="changingId === row.id" @change="value => toggleStatus(row, value)" />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="180"><template #default="{ row }">{{ formatTime(row.created_at) }}</template></el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="load" />
    </el-card>

    <el-dialog v-model="visible" :title="form.id ? '编辑敏感词' : '新增敏感词'" width="min(92vw, 480px)" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="敏感词" prop="word"><el-input v-model="form.word" maxlength="64" show-word-limit /></el-form-item>
        <el-form-item label="分类" prop="category">
          <el-select v-model="form.category" style="width:100%"><el-option v-for="item in categories" :key="item.value" :value="item.value" :label="item.label" /></el-select>
        </el-form-item>
        <el-form-item label="状态"><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import Pagination from '@/components/Pagination.vue'
import { addSensitiveWord, deleteSensitiveWord, getSensitiveWordList, updateSensitiveWord } from '@/api/system'

const categories = [
  { value: 'DIRTY', label: '低俗辱骂' },
  { value: 'AD', label: '广告导流' },
  { value: 'POLITICAL', label: '政治敏感' },
  { value: 'OTHER', label: '其他' }
]
const query = reactive({ pageNum: 1, pageSize: 20, keyword: '', category: '' })
const rows = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const changingId = ref(null)
const visible = ref(false)
const formRef = ref()
const form = reactive({ id: null, word: '', category: 'OTHER', enabled: true })
const rules = {
  word: [{ required: true, message: '请输入敏感词', trigger: 'blur' }],
  category: [{ required: true, message: '请选择分类', trigger: 'change' }]
}
const categoryLabel = value => categories.find(item => item.value === value)?.label || value || '其他'
const formatTime = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'

async function load() {
  loading.value = true
  try {
    const { data } = await getSensitiveWordList({ ...query })
    rows.value = data?.records || []
    total.value = Number(data?.total || 0)
  } finally { loading.value = false }
}
function search() { query.pageNum = 1; return load() }
function reset() { Object.assign(query, { pageNum: 1, keyword: '', category: '' }); return load() }
function openCreate() { Object.assign(form, { id: null, word: '', category: 'OTHER', enabled: true }); visible.value = true }
function openEdit(row) { Object.assign(form, { id: row.id, word: row.word, category: row.category || 'OTHER', enabled: Number(row.status) === 1 }); visible.value = true }

async function save() {
  await formRef.value?.validate()
  saving.value = true
  try {
    const payload = { word: form.word.trim(), category: form.category, status: form.enabled ? 1 : 0 }
    if (form.id) await updateSensitiveWord({ ...payload, id: form.id })
    else await addSensitiveWord(payload)
    ElMessage.success('敏感词已保存')
    visible.value = false
    await load()
  } finally { saving.value = false }
}

async function toggleStatus(row, enabled) {
  changingId.value = row.id
  try {
    await updateSensitiveWord({ id: row.id, word: row.word, category: row.category || 'OTHER', status: enabled ? 1 : 0 })
    row.status = enabled ? 1 : 0
    ElMessage.success(enabled ? '已启用' : '已停用')
  } finally { changingId.value = null }
}

async function remove(row) {
  await ElMessageBox.confirm(`确认删除敏感词“${row.word}”？`, '删除敏感词', { type: 'warning' })
  await deleteSensitiveWord(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<style scoped>
.card-header { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; }
.subtitle { margin:8px 0 0; color:#6b7280; font-size:13px; line-height:1.6; }
.toolbar { display:flex; flex-wrap:wrap; gap:10px; margin-bottom:16px; }
.toolbar .el-input { width:220px; }
.toolbar .el-select { width:150px; }
@media(max-width:600px){ .card-header{flex-direction:column;} .toolbar .el-input,.toolbar .el-select{width:100%;} }
</style>
