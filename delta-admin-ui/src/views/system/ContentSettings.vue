<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <div class="card-header">
          <strong>内容页面</strong>
          <el-button type="primary" :disabled="loading || !!loadError || saving || deleting" @click="openCreate">新增页面</el-button>
        </div>
      </template>
      <div class="quick-keys">
        <el-button v-for="item in presets" :key="item.key" plain :disabled="loading || !!loadError || saving || deleting" @click="openPreset(item)">{{ presetAction(item) }}</el-button>
      </div>
      <LoadFeedback v-if="loadError" :error="loadError" label="内容页面" @retry="load" />
      <el-table v-loading="loading" :data="rows" stripe>
        <el-table-column label="页面" min-width="200">
          <template #default="{ row }"><strong>{{ keyLabel(row.configKey) }}</strong><div class="key-code">{{ row.configKey }}</div></template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="220" show-overflow-tooltip />
        <el-table-column label="更新时间" min-width="180"><template #default="{ row }">{{ formatTime(row.updatedAt || row.updated_at) }}</template></el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }"><el-button link type="primary" :disabled="loading || saving || deleting" @click="openEdit(row)">编辑</el-button><el-button link type="danger" :disabled="loading || saving || deleting" @click="remove(row)">删除</el-button></template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && !loadError && !rows.length" description="尚未创建内容页面" />
    </el-card>

    <el-dialog v-model="visible" :title="form.id ? '编辑内容页面' : '新增内容页面'" width="min(96vw, 980px)" :show-close="!saving" :close-on-click-modal="!saving" :close-on-press-escape="!saving" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" :disabled="saving">
        <div class="form-grid">
          <el-form-item label="内容键" prop="configKey">
            <el-input v-model="form.configKey" :disabled="!!form.id" maxlength="128" placeholder="例如 privacy_policy" @input="normalizeKey" />
            <small v-if="form.id">内容键创建后不能修改</small>
          </el-form-item>
          <el-form-item label="页面名称/备注" prop="remark"><el-input v-model="form.remark" maxlength="255" placeholder="例如 用户隐私政策" /></el-form-item>
        </div>
        <el-form-item label="页面内容" prop="configValue">
          <RichTextEditor v-model="form.configValue" :rows="14" />
        </el-form-item>
      </el-form>
      <template #footer><el-button :disabled="saving" @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import RichTextEditor from '@/components/RichTextEditor.vue'
import LoadFeedback from '@/components/LoadFeedback.vue'
import { addContentConfig, deleteContentConfig, getContentConfigList, updateContentConfig } from '@/api/system'

const presets = [
  { key: 'user_agreement', label: '用户协议', remark: '用户协议' },
  { key: 'privacy_policy', label: '隐私政策', remark: '隐私政策' },
  { key: 'about_us', label: '关于我们', remark: '关于我们' }
]
const labels = Object.fromEntries(presets.map(item => [item.key, item.label]))
const rows = ref([])
const loading = ref(false)
const loadError = ref('')
const saving = ref(false)
const deleting = ref(false)
const visible = ref(false)
const formRef = ref()
const form = reactive({ id: null, configKey: '', configValue: '', remark: '' })
const rules = {
  configKey: [
    { required: true, message: '请输入内容键', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9_-]{0,127}$/, message: '以小写字母开头，仅包含小写字母、数字、下划线或短横线', trigger: 'blur' }
  ],
  configValue: [{ required: true, message: '请输入页面内容', trigger: 'blur' }],
  remark: [{ required: true, message: '请填写页面名称', trigger: 'blur' }]
}
const keyLabel = key => labels[key] || key
const formatTime = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-'
const normalizeKey = value => { form.configKey = String(value || '').toLowerCase().replace(/[^a-z0-9_-]/g, '') }
const presetAction = item => (rows.value.some(row => row.configKey === item.key) ? '编辑' : '创建') + item.label

async function load() {
  if (loading.value) return
  loading.value = true
  loadError.value = ''
  try { rows.value = (await getContentConfigList()).data || [] }
  catch (error) {
    rows.value = []
    loadError.value = error?.response?.data?.msg || error?.message || '请重试'
  } finally { loading.value = false }
}
function openCreate() { Object.assign(form, { id: null, configKey: '', configValue: '', remark: '' }); visible.value = true }
function openPreset(item) {
  if (loading.value || loadError.value) return
  const existing = rows.value.find(row => row.configKey === item.key)
  if (existing) return openEdit(existing)
  Object.assign(form, { id: null, configKey: item.key, configValue: '', remark: item.remark })
  visible.value = true
}
function openEdit(row) { Object.assign(form, { id: row.id, configKey: row.configKey, configValue: row.configValue || '', remark: row.remark || keyLabel(row.configKey) }); visible.value = true }
async function save() {
  if (!formRef.value || saving.value || deleting.value || loading.value) return
  saving.value = true
  try {
    await formRef.value.validate()
  } catch {
    saving.value = false
    return
  }
  const content = String(form.configValue || '').trim()
  const hasVisibleContent = content.replace(/<[^>]*>/g, '').trim().length > 0
  const hasMedia = /<(img|hr|table)\b/i.test(content)
  if (!content || (!hasVisibleContent && !hasMedia)) {
    saving.value = false
    return ElMessage.warning('页面内容不能为空')
  }
  try {
    const payload = { id: form.id, configKey: form.configKey, configValue: form.configValue, remark: form.remark.trim() }
    if (form.id) await updateContentConfig(payload)
    else await addContentConfig(payload)
    ElMessage.success('内容页面已保存')
    visible.value = false
    await load()
  } catch (error) {
    if (!error?.requestHandled) ElMessage.error(error?.response?.data?.msg || '内容页面保存失败，请重试')
  } finally { saving.value = false }
}
async function remove(row) {
  if (deleting.value || saving.value || loading.value) return
  deleting.value = true
  try {
    try {
      await ElMessageBox.confirm(`确认删除“${keyLabel(row.configKey)}”？客户端将无法再读取该页面。`, '删除内容页面', { type: 'warning' })
    } catch { return }
    await deleteContentConfig(row.id)
    ElMessage.success('内容页面已删除')
    await load()
  } catch (error) {
    if (!error?.requestHandled) ElMessage.error(error?.response?.data?.msg || '内容页面删除失败，请重试')
  } finally { deleting.value = false }
}
onMounted(load)
</script>

<style scoped>
.card-header { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; }
.quick-keys { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:16px; }
.key-code { margin-top:3px; color:#909399; font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:12px; }
.form-grid { display:grid; grid-template-columns:minmax(0,1fr) minmax(0,1fr); gap:16px; }
@media(max-width:768px){ .card-header{flex-direction:column;} .form-grid{grid-template-columns:1fr;} }
</style>
