<template>
  <section class="wechat-accounts">
    <PageHeader title="公众号运营" description="绑定本客户自己的公众号，按角色配置模板并检查可发送状态。">
      <template #actions>
        <el-button @click="loadAccounts">刷新</el-button>
        <el-button type="primary" @click="openAccount()">新增公众号</el-button>
      </template>
    </PageHeader>

    <el-alert type="info" :closable="false" show-icon
      title="平台级微信开放平台组件只在授权中心维护；本页只保存本租户公众号绑定和模板映射。" />

    <el-card shadow="never" class="account-list-card">
      <div class="account-toolbar">
        <div>
          <strong>已绑定公众号</strong>
          <span>模板 ID 只对当前公众号有效，不能跨客户复制。</span>
        </div>
        <el-select v-model="selectedId" placeholder="选择公众号" style="width: 300px" @change="loadAccountData">
          <el-option v-for="account in accounts" :key="account.id"
            :label="`${account.name} · ${account.appId}`" :value="account.id" />
        </el-select>
      </div>
      <DataTable :loading="loading" :data="accounts" row-key="id" height="260" empty-text="尚未绑定公众号">
        <el-table-column prop="name" label="公众号" min-width="170" />
        <el-table-column prop="appId" label="AppID" min-width="190" />
        <el-table-column label="接入方式" width="120">
          <template #default="{ row }">{{ row.mode === 'OPEN_PLATFORM' ? '开放平台' : '客户直连' }}</template>
        </el-table-column>
        <el-table-column label="状态" min-width="160">
          <template #default="{ row }">
            <el-tag v-if="row.defaultAccount" size="small" type="success">默认</el-tag>
            <el-tag v-if="!row.secretConfigured" size="small" type="warning">缺凭据</el-tag>
            <el-tag v-if="row.lastError" size="small" type="danger">{{ row.lastError }}</el-tag>
            <span v-if="!row.defaultAccount && !row.lastError && row.secretConfigured" class="muted">正常</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="selectAccount(row.id)">管理</el-button>
            <el-button link @click="openAccount(row)">编辑</el-button>
            <el-button link type="danger" @click="removeAccount(row)">删除</el-button>
          </template>
        </el-table-column>
      </DataTable>
    </el-card>

    <template v-if="selectedAccount">
      <div class="account-summary">
        <div>
          <span class="summary-label">当前公众号</span>
          <strong>{{ selectedAccount.name }}</strong>
          <small>{{ selectedAccount.appId }} · {{ selectedAccount.mode === 'OPEN_PLATFORM' ? '开放平台' : '客户直连' }}</small>
        </div>
        <div class="summary-actions">
          <el-button :loading="syncing" @click="syncTemplates">同步模板</el-button>
          <el-button type="primary" plain @click="openTemplate()">新增模板映射</el-button>
        </div>
      </div>

      <el-tabs v-model="activeTab" class="account-tabs">
        <el-tab-pane label="场景就绪" name="readiness">
          <DataTable :loading="loadingReadiness" :data="readinessRows" row-key="key" empty-text="请先同步公众号模板">
            <el-table-column label="角色" width="110">
              <template #default="{ row }">{{ roleLabel(row.role) }}</template>
            </el-table-column>
            <el-table-column label="场景" min-width="180">
              <template #default="{ row }">{{ scenarioLabel(row.scenario) }} <small class="muted">{{ row.scenario }}</small></template>
            </el-table-column>
            <el-table-column label="状态" width="150">
              <template #default="{ row }">
                <el-tag :type="row.configured ? 'success' : 'warning'" size="small">
                  {{ row.configured ? '就绪' : '缺模板' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="templateId" label="模板 ID" min-width="220" show-overflow-tooltip />
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openTemplate(row)">{{ row.configured ? '编辑' : '配置' }}</el-button>
                <el-button v-if="row.configured" link type="danger" @click="removeTemplate(row)">删除</el-button>
              </template>
            </el-table-column>
          </DataTable>
        </el-tab-pane>

        <el-tab-pane label="公众号模板库" name="catalog">
          <DataTable :loading="loadingCatalog" :data="catalog" row-key="templateId" empty-text="还没有同步模板，请点击上方“同步模板”">
            <el-table-column prop="title" label="模板标题" min-width="220" />
            <el-table-column prop="templateId" label="模板 ID" min-width="230" show-overflow-tooltip />
            <el-table-column prop="primaryIndustry" label="主行业" min-width="150" />
            <el-table-column prop="syncedAt" label="同步时间" min-width="170" />
            <el-table-column label="内容预览" min-width="250">
              <template #default="{ row }"><span class="template-content">{{ row.content || '-' }}</span></template>
            </el-table-column>
          </DataTable>
        </el-tab-pane>
      </el-tabs>
    </template>

    <el-empty v-else description="先新增并选择一个公众号" />

    <el-dialog v-model="accountDialog" :title="accountForm.id ? '编辑公众号' : '新增公众号'" width="min(600px, 94vw)" destroy-on-close>
      <el-form label-position="top" class="dialog-form">
        <el-form-item label="公众号名称"><el-input v-model="accountForm.name" maxlength="80" placeholder="例如：沧炼电竞服务号" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="AppID"><el-input v-model="accountForm.appId" maxlength="64" placeholder="wx..." /></el-form-item>
          <el-form-item label="接入方式">
            <el-select v-model="accountForm.mode">
              <el-option label="客户直连" value="DIRECT" />
              <el-option label="授权中心开放平台" value="OPEN_PLATFORM" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item :label="accountForm.id ? 'AppSecret（留空不修改）' : 'AppSecret'">
          <el-input v-model="accountForm.appSecret" type="password" show-password maxlength="256"
            :placeholder="accountForm.id ? '留空保留现有凭据' : '填写公众号 AppSecret'" />
        </el-form-item>
        <el-alert v-if="accountForm.mode === 'OPEN_PLATFORM'" type="warning" :closable="false" show-icon
          title="开放平台模式需要在授权中心完成组件配置后才会下发可用授权；当前客户后台不会保存组件密钥。" />
        <div class="form-grid">
          <el-form-item label="回调 Token（选填）"><el-input v-model="accountForm.callbackToken" maxlength="256" /></el-form-item>
          <el-form-item label="EncodingAESKey（选填）"><el-input v-model="accountForm.aesKey" maxlength="256" /></el-form-item>
        </div>
        <div class="switch-row">
          <el-checkbox v-model="accountForm.defaultAccount">设为默认公众号</el-checkbox>
          <el-checkbox v-model="accountForm.enabled">启用</el-checkbox>
        </div>
      </el-form>
      <template #footer><el-button @click="accountDialog=false">取消</el-button><el-button type="primary" :loading="savingAccount" @click="saveAccount">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="templateDialog" :title="templateForm.configured ? '编辑模板映射' : '配置模板映射'" width="min(760px, 96vw)" destroy-on-close>
      <el-form label-position="top" class="dialog-form">
        <div class="form-grid three">
          <el-form-item label="接收角色"><el-input :model-value="roleLabel(templateForm.role)" disabled /></el-form-item>
          <el-form-item label="业务场景"><el-input :model-value="scenarioLabel(templateForm.scenario)" disabled /></el-form-item>
          <el-form-item label="状态"><el-tag :type="templateForm.templateId ? 'success' : 'info'">{{ templateForm.templateId ? '已配置' : '待配置' }}</el-tag></el-form-item>
        </div>
        <el-form-item label="微信模板 ID">
          <el-select v-model="templateForm.templateId" filterable allow-create default-first-option clearable style="width:100%" placeholder="从公众号模板库选择或直接粘贴模板 ID">
            <el-option v-for="item in catalog" :key="item.templateId" :label="`${item.title} · ${item.templateId}`" :value="item.templateId" />
          </el-select>
        </el-form-item>
        <el-alert v-if="templateForm.templateHint" :title="`授权中心建议：${templateForm.templateHint}`" type="info" :closable="false" show-icon />
        <div class="field-heading"><span>模板参数映射</span><small>左侧填写微信模板变量，右侧选择系统数据源</small></div>
        <div v-for="(field, index) in templateForm.fields" :key="index" class="field-row">
          <el-input v-model="field.key" maxlength="64" placeholder="例如 thing1" />
          <el-select v-model="field.source" filterable clearable placeholder="系统数据源">
            <el-option-group v-for="group in sourceGroups" :key="group.label" :label="group.label">
              <el-option v-for="source in group.sources" :key="source.source" :label="`${source.label}（${source.source}）`" :value="source.source" />
            </el-option-group>
          </el-select>
          <el-button text type="danger" :disabled="templateForm.fields.length <= 1" @click="templateForm.fields.splice(index, 1)">删除</el-button>
        </div>
        <el-button plain size="small" @click="templateForm.fields.push({ key: '', source: '' })">添加参数</el-button>
        <el-form-item label="消息点击链接（选填）"><el-input v-model="templateForm.url" maxlength="2000" placeholder="https://当前客户域名/订单详情" /></el-form-item>
        <div class="form-grid">
          <el-form-item label="小程序 AppID（选填）"><el-input v-model="templateForm.miniAppId" maxlength="64" /></el-form-item>
          <el-form-item label="小程序页面路径（选填）"><el-input v-model="templateForm.miniPagepath" maxlength="200" /></el-form-item>
        </div>
        <el-checkbox v-model="templateForm.enabled">启用此场景</el-checkbox>
      </el-form>
      <template #footer><el-button @click="templateDialog=false">取消</el-button><el-button type="primary" :loading="savingTemplate" @click="saveTemplate">保存映射</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import DataTable from '@/components/DataTable.vue'
import { notificationConfigGuide } from '@/utils/notification-examples'
import {
  deleteWechatAccount, deleteWechatTemplate, getWechatAccounts, getWechatReadiness,
  getWechatTemplateCatalog, saveWechatAccount, saveWechatTemplate, syncWechatTemplates
} from '@/api/wechat-operations'

const accounts = ref([])
const selectedId = ref('')
const readinessRows = ref([])
const catalog = ref([])
const authoritySchemes = ref([])
const loading = ref(false)
const loadingReadiness = ref(false)
const loadingCatalog = ref(false)
const syncing = ref(false)
const savingAccount = ref(false)
const savingTemplate = ref(false)
const accountDialog = ref(false)
const templateDialog = ref(false)
const activeTab = ref('readiness')
const accountForm = reactive({ id: '', name: '', appId: '', appSecret: '', mode: 'DIRECT', callbackToken: '', aesKey: '', defaultAccount: false, enabled: true })
const templateForm = reactive({ configured: false, role: 'ADMIN', scenario: 'new_order', templateId: '', templateHint: '', fields: [{ key: 'thing1', source: 'message' }], url: '', miniAppId: '', miniPagepath: '', enabled: true })
const roleKeys = { USER: 'user', PLAYER: 'player', ADMIN: 'admin', CS: 'cs' }
const roleNames = { USER: '客户', PLAYER: '打手', ADMIN: '管理员', CS: '客服' }
const selectedAccount = computed(() => accounts.value.find(row => row.id === selectedId.value) || null)
const sourceGroups = computed(() => notificationConfigGuide('wechat.official.templates.admin')?.sourceGroups || [])
const eventMap = computed(() => Object.fromEntries((notificationConfigGuide('wechat.official.templates.admin')?.events || []).map(item => [item.key, item])))

function roleLabel(role) { return roleNames[role] || role || '-' }
function scenarioLabel(scenario) { return eventMap.value[scenario]?.label || scenario }

async function loadAccounts() {
  loading.value = true
  try {
    const { data } = await getWechatAccounts()
    accounts.value = Array.isArray(data) ? data : []
    if (!selectedId.value || !accounts.value.some(row => row.id === selectedId.value)) selectedId.value = accounts.value[0]?.id || ''
    if (selectedId.value) await loadAccountData()
  } catch (error) {
    ElMessage.error(error?.message || '公众号账号读取失败')
  } finally {
    loading.value = false
  }
}

async function loadAccountData() {
  if (!selectedId.value) return
  const accountId = selectedId.value
  loadingReadiness.value = true
  loadingCatalog.value = true
  try {
    const [state, templates] = await Promise.all([getWechatReadiness(accountId), getWechatTemplateCatalog(accountId)])
    if (selectedId.value !== accountId) return
    readinessRows.value = (state.data?.templates || []).map(row => ({ ...row, key: `${row.role}:${row.scenario}` }))
    authoritySchemes.value = Array.isArray(state.data?.authoritySchemes) ? state.data.authoritySchemes : []
    catalog.value = Array.isArray(templates.data) ? templates.data : []
  } catch (error) {
    ElMessage.error(error?.message || '公众号模板状态读取失败')
  } finally {
    if (selectedId.value === accountId) { loadingReadiness.value = false; loadingCatalog.value = false }
  }
}

function selectAccount(id) { selectedId.value = id; loadAccountData() }

function openAccount(row = null) {
  Object.assign(accountForm, { id: '', name: '', appId: '', appSecret: '', mode: 'DIRECT', callbackToken: '', aesKey: '', defaultAccount: false, enabled: true }, row || {})
  accountForm.appSecret = ''
  accountForm.callbackToken = ''
  accountForm.aesKey = ''
  accountDialog.value = true
}

async function saveAccount() {
  if (!accountForm.name.trim() || !accountForm.appId.trim()) return ElMessage.warning('请填写公众号名称和 AppID')
  if (!accountForm.id && !accountForm.appSecret.trim() && accountForm.mode === 'DIRECT') return ElMessage.warning('直连模式请填写 AppSecret')
  savingAccount.value = true
  try {
    const { data } = await saveWechatAccount({ ...accountForm })
    accountDialog.value = false
    ElMessage.success('公众号账号已保存')
    selectedId.value = data?.id || selectedId.value
    await loadAccounts()
  } catch (error) {
    ElMessage.error(error?.message || '保存公众号账号失败')
  } finally { savingAccount.value = false }
}

async function removeAccount(row) {
  try {
    await ElMessageBox.confirm(`确认删除“${row.name}”？模板映射会一并删除，已发送通知记录不会删除。`, '删除公众号', { type: 'warning' })
    await deleteWechatAccount(row.id)
    ElMessage.success('公众号已删除')
    selectedId.value = ''
    await loadAccounts()
  } catch (error) { if (error?.message) ElMessage.error(error.message) }
}

async function syncTemplates() {
  if (!selectedId.value) return
  syncing.value = true
  try {
    const { data } = await syncWechatTemplates(selectedId.value)
    catalog.value = Array.isArray(data) ? data : []
    ElMessage.success(`已同步 ${catalog.value.length} 个公众号模板`)
    await loadAccountData()
  } catch (error) {
    ElMessage.error(error?.message || '模板同步失败')
  } finally { syncing.value = false }
}

function openTemplate(row = null) {
  const role = row?.role || 'ADMIN', scenario = row?.scenario || 'new_order'
  const guide = notificationConfigGuide(`wechat.official.templates.${roleKeys[role] || 'admin'}`)
  const event = guide?.events?.find(item => item.key === scenario)
  const authority = authoritySchemes.value.find(item => item.role === role && item.scenario === scenario)
  const defaults = authority?.fields && Object.keys(authority.fields).length
    ? Object.entries(authority.fields).map(([key, source]) => ({ key, source }))
    : event?.fields && Object.keys(event.fields).length
    ? Object.entries(event.fields).map(([key, source]) => ({ key, source }))
    : [{ key: 'thing1', source: 'message' }]
  Object.assign(templateForm, {
    configured: Boolean(row?.configured), role, scenario,
    templateId: row?.templateId || '', templateHint: authority?.templateHint || '', fields: defaults,
    url: row?.url || '', miniAppId: row?.miniprogram?.appid || '', miniPagepath: row?.miniprogram?.pagepath || '', enabled: true
  })
  if (row?.configured && row.fields && Object.keys(row.fields).length) {
    templateForm.fields = Object.entries(row.fields).map(([key, source]) => ({ key, source }))
  }
  templateDialog.value = true
}

async function saveTemplate() {
  if (!templateForm.templateId.trim()) return ElMessage.warning('请选择或填写微信模板 ID')
  const fields = Object.fromEntries(templateForm.fields.filter(row => row.key.trim() && row.source).map(row => [row.key.trim(), row.source]))
  if (!Object.keys(fields).length) return ElMessage.warning('至少配置一个模板参数')
  savingTemplate.value = true
  try {
    await saveWechatTemplate(selectedId.value, {
      role: templateForm.role, scenario: templateForm.scenario, templateId: templateForm.templateId.trim(),
      fields, url: templateForm.url.trim(), miniprogram: templateForm.miniAppId.trim() ? { appid: templateForm.miniAppId.trim(), pagepath: templateForm.miniPagepath.trim() } : {}, enabled: templateForm.enabled
    })
    templateDialog.value = false
    ElMessage.success('模板映射已保存')
    await loadAccountData()
  } catch (error) {
    ElMessage.error(error?.message || '保存模板映射失败')
  } finally { savingTemplate.value = false }
}

async function removeTemplate(row) {
  try {
    await ElMessageBox.confirm(`确认删除“${scenarioLabel(row.scenario)}”的模板映射？`, '删除模板映射', { type: 'warning' })
    await deleteWechatTemplate(selectedId.value, row.role, row.scenario)
    ElMessage.success('模板映射已删除')
    await loadAccountData()
  } catch (error) { if (error?.message) ElMessage.error(error.message) }
}

onMounted(loadAccounts)
</script>

<style scoped>
.wechat-accounts { display:grid; gap:16px; }
.account-list-card :deep(.el-card__body) { padding:0; }
.account-toolbar { display:flex; align-items:center; justify-content:space-between; gap:16px; padding:18px 20px; border-bottom:1px solid var(--el-border-color-lighter); }
.account-toolbar strong, .account-toolbar span { display:block; }
.account-toolbar strong { color:var(--el-text-color-primary); font-size:14px; }
.account-toolbar span { margin-top:4px; color:var(--el-text-color-secondary); font-size:12px; }
.account-summary { display:flex; align-items:center; justify-content:space-between; gap:16px; padding:18px 20px; border:1px solid var(--el-border-color-light); border-radius:14px; background:var(--el-bg-color); }
.account-summary strong, .account-summary small { display:block; }
.account-summary strong { margin-top:3px; font-size:18px; }
.account-summary small { margin-top:4px; color:var(--el-text-color-secondary); }
.summary-label { color:var(--el-text-color-secondary); font-size:12px; }
.summary-actions { display:flex; gap:8px; }
.account-tabs :deep(.el-tabs__header) { margin-bottom:12px; }
.muted { margin-left:4px; color:var(--el-text-color-placeholder); font-size:11px; }
.template-content { color:var(--el-text-color-secondary); font-size:12px; line-height:1.6; white-space:pre-wrap; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical; overflow:hidden; }
.dialog-form { display:grid; gap:2px; }
.form-grid { display:grid; grid-template-columns:1fr 1fr; gap:12px; }
.form-grid.three { grid-template-columns:repeat(3,minmax(0,1fr)); }
.field-heading { display:flex; align-items:baseline; justify-content:space-between; margin:4px 0 8px; color:var(--el-text-color-primary); font-size:13px; font-weight:700; }
.field-heading small { color:var(--el-text-color-secondary); font-size:11px; font-weight:400; }
.field-row { display:grid; grid-template-columns:160px minmax(0,1fr) 48px; gap:8px; margin-bottom:8px; }
.switch-row { display:flex; gap:24px; margin-bottom:18px; }
@media(max-width:768px) {
  .account-toolbar, .account-summary { align-items:stretch; flex-direction:column; }
  .account-toolbar :deep(.el-select) { width:100% !important; }
  .summary-actions { width:100%; }
  .summary-actions :deep(.el-button) { flex:1; }
  .form-grid, .form-grid.three { grid-template-columns:1fr; }
  .field-row { grid-template-columns:1fr; }
}
</style>
