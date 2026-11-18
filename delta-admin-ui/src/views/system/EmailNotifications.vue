<template>
  <div class="email-notification-page">
    <LoadFeedback :loading="loading" :error="loadError" label="邮件通知配置" @retry="load" />
    <template v-if="form">
      <el-card shadow="never" class="settings-card">
        <template #header>
          <div class="card-header">
            <div><h2>{{ embedded ? 'SMTP 设置' : '邮件通知' }}</h2><p v-if="!embedded">通过客户自有 SMTP 邮箱接收重要业务通知。</p></div>
            <div class="header-actions"><el-tag :type="form.enabled ? 'success' : 'info'">{{ form.enabled ? '运行中' : '已停用' }}</el-tag><el-button :loading="testing" :disabled="saving || !form.enabled" @click="sendTest">发送测试邮件</el-button><el-button v-if="!embedded" type="primary" :loading="saving" :disabled="embedded && !dirty" @click="save">保存配置</el-button></div>
          </div>
        </template>

        <div class="status-strip"><div><strong>通知总开关</strong><span>开启后才会按下方事件配置排队发送。</span></div><el-switch v-model="form.enabled" active-text="启用" inactive-text="停用"/></div>

        <el-form label-position="top" class="mail-form">
          <div class="form-grid">
            <el-form-item label="SMTP 服务器" class="span-2"><el-input v-model="form.host" placeholder="例如 smtp.qq.com" autocomplete="off"/></el-form-item>
            <el-form-item label="端口"><el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right"/></el-form-item>
            <el-form-item label="连接加密"><el-select v-model="form.security"><el-option label="SSL/TLS（通常 465）" value="TLS"/><el-option label="STARTTLS（通常 587）" value="STARTTLS"/></el-select></el-form-item>
            <el-form-item label="邮箱账号"><el-input v-model="form.username" autocomplete="username" placeholder="发信账号"/></el-form-item>
            <el-form-item label="邮箱密码 / 授权码"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" :placeholder="form.passwordConfigured ? '已保存，留空保留原值' : '填写 SMTP 授权码'"/></el-form-item>
            <el-form-item label="发件邮箱"><el-input v-model="form.fromAddress" autocomplete="off" placeholder="noreply@example.com"/></el-form-item>
            <el-form-item label="发件人名称"><el-input v-model="form.senderName" maxlength="60" placeholder="见高山电竞"/></el-form-item>
          </div>

          <el-form-item label="运营通知收件邮箱" class="recipient-field">
            <div class="recipient-editor">
              <div class="recipient-summary"><span>接收重要通知的邮箱</span><small>{{ form.recipients.length }} / 20</small></div>
              <div v-if="form.recipients.length" class="recipient-tags"><el-tag v-for="address in form.recipients" :key="address" closable effect="plain" class="recipient-chip" @close="removeRecipient(address)">{{ address }}</el-tag></div>
              <div v-else class="recipient-empty">尚未添加收件邮箱</div>
              <div class="recipient-add"><el-input v-model="recipientDraft" clearable placeholder="输入、粘贴或换行添加多个邮箱" @keyup.enter="addRecipient"/><el-button :disabled="!recipientDraft.trim()" @click="addRecipient">添加</el-button></div>
              <p>支持逗号、分号或换行分隔；重复邮箱自动忽略。</p>
            </div>
          </el-form-item>
        </el-form>

        <div class="event-heading"><div><h3>通知事件</h3><p>只发送已勾选的业务事件，未勾选事件不会进入发送队列。</p></div><el-tag type="info">{{ form.events.length }} / {{ availableEvents.length }} 已启用</el-tag></div>
        <el-checkbox-group v-model="form.events" class="event-grid">
          <el-checkbox v-for="event in availableEvents" :key="event.id" :value="event.id" class="event-item"><span class="event-name">{{ event.name }}</span><small>{{ event.description }}</small></el-checkbox>
        </el-checkbox-group>
      </el-card>

      <el-card shadow="never">
        <template #header><div class="card-header"><div><h2>发送记录</h2><p>失败邮件最多自动重试 5 次，超过一天的待发记录会过期。</p></div><el-button :loading="recordsLoading" @click="loadRecords">刷新记录</el-button></div></template>
        <LoadFeedback :loading="recordsLoading && !records.length" :error="recordsError" label="发送记录" @retry="loadRecords" />
        <el-table v-if="records.length" :data="records" stripe><el-table-column prop="recipient" label="收件邮箱" min-width="200"/><el-table-column prop="subject" label="主题" min-width="220" show-overflow-tooltip/><el-table-column prop="event_type" label="事件" min-width="160"/><el-table-column label="状态" min-width="110"><template #default="{row}"><el-tag :type="statusType(row.status)" size="small">{{ statusLabels[row.status] || row.status }}</el-tag></template></el-table-column><el-table-column prop="attempts" label="尝试"/><el-table-column prop="last_error" label="错误代码" min-width="180"/><el-table-column label="创建时间" min-width="180"><template #default="{row}">{{ time(row.created_at) }}</template></el-table-column><el-table-column label="操作"><template #default="{row}"><el-button v-if="['FAILED','RETRY'].includes(row.status)" link type="primary" :loading="retrying===row.id" @click="retry(row.id)">重试</el-button></template></el-table-column></el-table>
        <el-empty v-else-if="!recordsLoading && !recordsError" description="暂无发送记录"/>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import LoadFeedback from '@/components/LoadFeedback.vue'
import { getEmailNotificationRecords, getEmailNotificationSettings, retryEmailNotification, saveEmailNotificationSettings, sendEmailNotificationTest } from '@/api/email-notifications'

const props = defineProps({ embedded: Boolean })
const emit = defineEmits(['dirty', 'saved', 'busy'])
const baseline = ref(null)
const form = ref(null), availableEvents = ref([]), loading = ref(false), loadError = ref('')
const records = ref([]), recordsLoading = ref(false), recordsError = ref('')
const saving = ref(false), testing = ref(false), retrying = ref(''), recipientDraft = ref('')
const dirty = computed(() => baseline.value !== null && (JSON.stringify(form.value) !== baseline.value || Boolean(recipientDraft.value.trim())))
watch(dirty, value => emit('dirty', value), { immediate: true })
watch([saving, testing], ([isSaving, isTesting]) => emit('busy', isSaving || isTesting), { immediate:true })
const statusLabels = { PENDING: '等待发送', SENDING: '发送中', SENT: '已发送', RETRY: '等待重试', FAILED: '发送失败', EXPIRED: '已过期' }
const emailPattern = /^[^\s<>@,;]+@[^\s<>@,;]+\.[^\s<>@,;]+$/

function applySettings(value) {
  form.value = { enabled: false, host: '', port: 465, security: 'TLS', username: '', fromAddress: '', senderName: '见高山电竞', recipients: [], events: [], password: '', passwordConfigured: false, ...value, recipients: Array.isArray(value?.recipients) ? value.recipients : [], events: Array.isArray(value?.events) ? value.events : [] }
  availableEvents.value = Array.isArray(value?.availableEvents) ? value.availableEvents : availableEvents.value
  baseline.value = JSON.stringify(form.value)
}
function addRecipient() {
  const candidates = String(recipientDraft.value || '').split(/[,;\n]+/).map(value => value.trim()).filter(Boolean)
  if (!candidates.length) return true
  const invalid = candidates.find(value => !emailPattern.test(value) || value.length > 254)
  if (invalid) { ElMessage.warning('邮箱格式不正确：' + invalid); return false }
  const seen = new Set(form.value.recipients.map(value => value.toLowerCase()))
  for (const address of candidates) if (!seen.has(address.toLowerCase())) { seen.add(address.toLowerCase()); form.value.recipients.push(address) }
  if (form.value.recipients.length > 20) { ElMessage.warning('最多配置 20 个收件邮箱'); form.value.recipients = form.value.recipients.slice(0, 20); return false }
  recipientDraft.value = ''
  return true
}
function removeRecipient(address) { form.value.recipients = form.value.recipients.filter(value => value !== address) }
function time(value) { return value ? new Date(String(value).replace(' ', 'T')).toLocaleString('zh-CN', { hour12: false }) : '—' }
function statusType(status) { return status === 'SENT' ? 'success' : status === 'FAILED' || status === 'EXPIRED' ? 'danger' : status === 'RETRY' ? 'warning' : 'info' }
async function load() {
  if (loading.value) return
  loading.value = true; loadError.value = ''
  try { applySettings((await getEmailNotificationSettings({ silent: true })).data) }
  catch { loadError.value = '邮件通知配置加载失败，请重试。'; form.value = null }
  finally { loading.value = false }
}
async function loadRecords() {
  if (recordsLoading.value) return
  recordsLoading.value = true; recordsError.value = ''
  try { records.value = (await getEmailNotificationRecords({ silent: true })).data || [] }
  catch { recordsError.value = '发送记录加载失败，请重试。' }
  finally { recordsLoading.value = false }
}
function discard() {
  if (baseline.value === null || saving.value) return
  recipientDraft.value = ''
  applySettings(JSON.parse(baseline.value))
}
defineExpose({ save, discard })

async function save() {
  if (saving.value || !form.value) return
  if (!addRecipient()) return
  if (form.value.enabled && !form.value.events.length) return ElMessage.warning('启用前至少选择一个通知事件')
  if (form.value.enabled && !form.value.recipients.length) return ElMessage.warning('启用前至少添加一个收件邮箱')
  saving.value = true
  try { applySettings((await saveEmailNotificationSettings(form.value)).data); emit('saved'); ElMessage.success('邮件通知配置已保存') }
  finally { saving.value = false }
}
async function sendTest() {
  if (testing.value) return
  try {
    const { value } = await ElMessageBox.prompt('填写一个或多个测试收件邮箱，使用逗号、分号或换行分隔。', '发送测试邮件', { inputValue: form.value.recipients.join(', '), inputPlaceholder: 'ops@example.com' })
    testing.value = true
    await sendEmailNotificationTest({ recipients: value })
    ElMessage.success('测试邮件已进入发送队列')
    await loadRecords()
  } catch (error) { if (error?.message && error.message !== 'cancel') ElMessage.error(error.message) }
  finally { testing.value = false }
}
async function retry(id) {
  if (retrying.value) return
  retrying.value = id
  try { await retryEmailNotification(id); ElMessage.success('已重新进入发送队列'); await loadRecords() }
  finally { retrying.value = '' }
}
onMounted(async () => { await load(); if (form.value) await loadRecords() })
</script>

<style scoped>
.email-notification-page{display:grid;gap:18px}.settings-card :deep(.el-card__body){padding:0 22px 24px}.card-header{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;flex-wrap:wrap}.card-header h2{margin:0;color:#17191c;font-size:20px}.card-header p,.event-heading p{margin:6px 0 0;color:#7a8492;font-size:13px;line-height:1.6}.header-actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.status-strip{display:flex;align-items:center;justify-content:space-between;gap:18px;margin:20px 0;padding:16px 18px;border:1px solid #dfe5ec;border-radius:12px;background:#f8fafc}.status-strip div{display:flex;flex-direction:column;gap:4px}.status-strip strong{font-size:15px}.status-strip span{color:#7a8492;font-size:13px}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}.span-2{grid-column:span 2}.form-grid :deep(.el-form-item){margin-bottom:18px}.recipient-field{margin-top:2px}.recipient-field :deep(.el-form-item__content){align-items:stretch}.recipient-editor{width:100%;padding:16px;border:1px solid #dfe5ec;border-radius:14px;background:linear-gradient(180deg,#f8fafc,#fff);box-shadow:inset 0 1px 0 rgba(255,255,255,.8)}.recipient-summary{display:flex;justify-content:space-between;gap:12px;margin-bottom:10px;color:#536273;font-size:13px;font-weight:600}.recipient-summary small{color:#8a96a5;font-weight:500}.recipient-tags{display:flex;flex-wrap:wrap;gap:8px;min-height:34px;margin-bottom:12px}.recipient-chip{border-radius:999px;background:#eef2f7;border-color:#dbe3ed;color:#253247}.recipient-empty{min-height:34px;margin-bottom:12px;padding:7px 10px;border-radius:8px;background:#f5f7fa;color:#95a0ae;font-size:13px}.recipient-add{display:flex;gap:8px}.recipient-add .el-input{flex:1}.recipient-editor p{margin:9px 0 0;color:#7b8796;font-size:12px}.event-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin:8px 0 14px}.event-heading h3{margin:0;font-size:17px}.event-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:10px}.event-item{height:auto;min-height:76px;margin:0!important;padding:13px 14px;align-items:flex-start;border:1px solid #e5e9ef;border-radius:11px;background:#fff;white-space:normal}.event-item :deep(.el-checkbox__input){margin-top:2px}.event-item :deep(.el-checkbox__label){display:flex;min-width:0;flex-direction:column;gap:5px;white-space:normal}.event-name{color:#252b33;font-size:14px;font-weight:600}.event-item small{color:#7c8795;font-size:12px;line-height:1.55}.el-table{margin-top:16px}@media(max-width:760px){.settings-card :deep(.el-card__body){padding:0 14px 18px}.form-grid{grid-template-columns:1fr}.span-2{grid-column:auto}.header-actions{width:100%}.status-strip{align-items:flex-start}.recipient-add,.event-heading{flex-direction:column;align-items:stretch}.event-grid{grid-template-columns:1fr}}
</style>
