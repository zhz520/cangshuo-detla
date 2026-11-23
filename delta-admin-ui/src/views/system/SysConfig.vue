<template>
  <div class="settings-workspace" :class="{ 'demo-page': readOnly, 'settings-workspace-mobile': mobileShell }">
    <header v-if="!mobileShell" class="settings-page-head">
      <Setting aria-hidden="true" />
      <div><h1>系统配置</h1><p>管理平台基础信息、账户安全、支付交易与消息通知。</p></div>
    </header>
    <nav class="settings-categories" aria-label="配置分类">
      <button v-for="group in navigation" :key="group.id" type="button" class="settings-category"
        :aria-current="activeGroup.id === group.id ? 'true' : undefined" @click="openGroup(group)">
        <component :is="categoryIcons[group.id]" aria-hidden="true" />
        <span><strong>{{ group.title }}</strong><small>{{ group.description }}</small></span>
        <span v-if="group.tabs.some(tab => unsaved(tab.id))" class="settings-category-dot" aria-label="有未保存更改" />
        <span v-else-if="group.tabs.some(tab => readiness(tab.id) === 'incomplete')" class="settings-dot" aria-label="有未配置项" />
      </button>
    </nav>
    <div class="settings-body">
      <nav class="settings-nav" aria-label="系统配置导航">
        <button v-for="tab in subnav" :key="tab.id" type="button" class="settings-nav-item"
          :aria-current="subnavId === tab.id ? 'page' : undefined" @click="openSection(tab.id)">
          <component :is="sectionIcons[tab.id] || Document" aria-hidden="true" />
          <span>{{ tab.title }}</span>
          <span v-if="unsaved(tab.id)" class="settings-draft">未保存</span>
          <span v-else-if="readiness(tab.id) === 'incomplete'" class="settings-dot" aria-label="未配置" />
        </button>
      </nav>
      <div class="settings-content">
        <header class="settings-header">
          <div><h2>{{ active.title }}</h2><p v-if="active.id === 'site'">设置平台的基本信息、品牌展示与访问地址。</p></div>
          <el-tag v-if="readOnly" type="info" effect="plain">演示账号仅可查看</el-tag>
        </header>

      <template v-if="readOnly">
        <section v-for="panel in active.panels" :key="panel.id" class="settings-panel" :id="`config-panel-${panel.id}`">
          <div class="settings-panel-head"><h3><component :is="panelIcons[panel.id] || Document" aria-hidden="true" />{{ panel.title }}</h3></div>
          <dl class="settings-grid settings-preview">
            <div v-for="field in panel.fields" :key="field"><dt>{{ field }}</dt><dd aria-label="已脱敏">******</dd></div>
          </dl>
        </section>
      </template>

      <template v-else>
        <template v-if="!active.editor">
          <LoadFeedback :loading="loading" :error="loadError" label="系统配置" @retry="fetchData" />
          <p v-if="active.id === 'payment'" class="settings-note">
            <InfoFilled aria-hidden="true" />订单、充值和押金使用本站收款配置；功能商城与主题购买由授权中心收款。
          </p>
          <el-form v-if="!loading && !loadError" label-position="top" id="settings-form" class="settings-form" @submit.prevent="handleSave">
            <section v-for="panel in displayedPanels" :key="panel.id" class="settings-panel" :id="`config-panel-${panel.id}`">
              <div class="settings-panel-head">
                <h3><component :is="panelIcons[panel.id] || Document" aria-hidden="true" />{{ panel.title }}</h3>
                <el-button v-if="panel.id === 'deposit'" link type="primary"
                  @click="router.push(mobileShell ? '/h5/player-level' : '/player-level')">设置各等级提现押金</el-button>
                <el-button v-if="panel.id === 'wechat' && can('system.manage') && platformFeatureAllowed('official_wechat_notifications')"
                  link type="primary" native-type="button" @click="router.push(mobileShell ? '/h5/wechat/accounts' : '/wechat/accounts')">管理公众号模板</el-button>
                <span v-if="panel.status" class="settings-tag" :class="{ incomplete:panel.status === 'incomplete' }">
                  {{ panel.status === 'incomplete' ? '未配置' : '已配置' }}
                </span>
              </div>
              <p v-if="panel.id === 'public-key'" class="settings-note">
                <InfoFilled aria-hidden="true" />微信支付公钥文件和公钥 ID 必须从同一商户账户成对下载。公众号支付还需配置网页授权域名与 JSAPI 支付授权目录。
              </p>
              <div class="settings-grid">
                <SystemConfigField v-for="item in panel.fields" :key="item.configKey" :item="item" :saved-value="previousValues[item.configKey]"
                  :busy="submitting || uploading.has(item.configKey)" :upload="options => handleCertificateUpload(item, options)"
                  @change="item.configValue = $event" @validity="notificationValidity[item.configKey] = $event"
                  @busy="setUploading(item.configKey, $event)" />
              </div>
              <div v-if="panel.templates.length" class="settings-template">
                <label>
                  <span>收件角色</span>
                  <select :value="templateSelected(panel)?.configKey" @change="templateSelection[panel.id] = $event.target.value">
                    <option v-for="item in panel.templates" :key="item.configKey" :value="item.configKey">{{ item.configName }}</option>
                  </select>
                </label>
                <SystemConfigField v-for="item in panel.templates" v-show="item === templateSelected(panel)" :key="item.configKey"
                  :item="item" :busy="submitting" :saved-value="previousValues[item.configKey]"
                  @change="item.configValue = $event" @validity="notificationValidity[item.configKey] = $event" />
              </div>
            </section>
            <el-empty v-if="!displayedPanels.length" description="暂无可配置项" />
          </el-form>
        </template>
        <template v-for="tab in visitedEditors" :key="tab.id">
          <section v-show="active.id === tab.id" class="settings-special" :aria-label="tab.title">
            <MembershipLevels v-if="tab.editor === 'membership'" :ref="instance => setEditor(tab.id, instance)" embedded
              @dirty="editorDirty[tab.id] = $event" @busy="editorBusy[tab.id] = $event" @saved="markSaved(tab.id)" />
            <Governance v-else-if="tab.editor === 'invitation'" fixed-kind="invitations" />
            <EmailNotifications v-else-if="tab.editor === 'email' && emailAvailable" :ref="instance => setEditor(tab.id, instance)" embedded
              @dirty="editorDirty[tab.id] = $event" @busy="editorBusy[tab.id] = $event" @saved="markSaved(tab.id)" />
            <div v-else-if="tab.editor === 'email'" class="settings-panel">
              <LoadFeedback :loading="enabledPlatformFeatures === null" :error="platformFeatureError" label="邮件通知功能" @retry="loadPlatformFeatureVisibility(true)" />
              <template v-if="enabledPlatformFeatures !== null && !platformFeatureError">
                <p>邮件通知尚未开通或启用。</p>
                <el-button link @click="router.push('/platform/features')">查看功能商城</el-button>
              </template>
            </div>
          </section>
        </template>
      </template>
      </div>
    </div>
    <footer v-if="!readOnly && showSaveBar" class="settings-footer">
      <span v-if="unsaved(active.id)" class="settings-footer-status" role="status">本页有未保存更改</span>
      <span v-else-if="lastSaved" class="settings-footer-status" role="status"><InfoFilled aria-hidden="true" />上次保存：{{ lastSaved }}</span>
      <div class="settings-footer-actions">
        <el-button :disabled="!unsaved(active.id) || savingCurrent || uploading.size > 0" @click="discardCurrent">取消修改</el-button>
        <el-button type="primary" :icon="Check" @click="saveCurrent"
          :disabled="!unsaved(active.id) || uploading.size > 0" :loading="savingCurrent">保存更改</el-button>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Bell, Box, Check, Document, Grid, InfoFilled, Link, Lock, Monitor, Setting, Wallet } from '@element-plus/icons-vue'
import LoadFeedback from '@/components/LoadFeedback.vue'
import SystemConfigField from '@/components/SystemConfigField.vue'
import { batchUpdateSysConfig, getSysConfigList, uploadWxPayCertificate } from '@/api/system'
import { can } from '@/utils/staff-permissions'
import { enabledPlatformFeatures, loadPlatformFeatureVisibility, platformFeatureAllowed, platformFeatureError } from '@/utils/platform-feature-visibility'
import { editableSystemConfigs, isSmsTemplateField } from '@/utils/system-config'
import { configGroups, configTabs, configSection, configReadiness, defaultConfigTab, orderConfigFields } from '@/utils/config-sections'

const MembershipLevels = defineAsyncComponent(() => import('@/views/marketing/MembershipLevels.vue'))
const Governance = defineAsyncComponent(() => import('@/views/player/Governance.vue'))
const EmailNotifications = defineAsyncComponent(() => import('@/views/system/EmailNotifications.vue'))

const props = defineProps({ readOnly: Boolean })
const route = useRoute(), router = useRouter()
const mobileShell = computed(() => route.path.startsWith('/h5/'))
const configList = ref([]), previousValues = ref({}), snapshotLoaded = ref(false)
const saveTimes = reactive({}), lastVisited = reactive({})
const siteAnchor = ref(false)
const categoryIcons = { basic: Setting, account: Lock, payment: Wallet, message: Bell, business: Box }
const sectionIcons = { site: Monitor, display: Grid, origin: Link }
const panelIcons = { brand: InfoFilled, origin: Link }
const loading = ref(false), submitting = ref(false), loadError = ref('')
const picked = ref(defaultConfigTab)
const uploading = reactive(new Set()), visited = reactive(new Set())
const editorDirty = reactive({}), editorBusy = reactive({}), editorRefs = new Map()
const notificationValidity = reactive({}), templateSelection = reactive({})
const canView = tab => props.readOnly || tab.id !== 'invitation' || can('player.manage')
const navigation = computed(() => configGroups.map(group => ({ ...group, tabs: group.tabs.filter(canView) })))
const allowedTabs = computed(() => configTabs.filter(canView))
const active = computed(() => allowedTabs.value.find(tab => tab.id === picked.value) || configTabs.find(tab => tab.id === defaultConfigTab))
const activeGroup = computed(() => navigation.value.find(group => group.id === active.value.group))
const subnav = computed(() => activeGroup.value.id === 'basic'
  ? [...activeGroup.value.tabs, { id:'origin', title:'站点入口' }]
  : activeGroup.value.tabs)
const subnavId = computed(() => active.value.id === 'site' && siteAnchor.value ? 'origin' : active.value.id)
const lastSaved = computed(() => {
  const dates = fieldsFor(active.value.id).map(item => item.updatedAt || '').sort()
  const raw = saveTimes[active.value.id] || dates[dates.length - 1]
  return raw ? String(raw).replace('T',' ').slice(0,16) : ''
})
const visitedEditors = computed(() => allowedTabs.value.filter(tab => tab.editor && visited.has(tab.id)))
const emailAvailable = computed(() => platformFeatureAllowed('email_notifications'))
const editable = computed(() => editableSystemConfigs(configList.value, enabledPlatformFeatures.value))
const fieldsByPanel = computed(() => {
  const map = new Map()
  for (const item of editable.value) {
    const panel = configSection(item.configKey)
    if (!panel) continue
    const items = map.get(panel) || []
    items.push(item)
    map.set(panel, items)
  }
  return map
})
const displayValue = value => String(value ?? '')
function fieldsFor(tab) {
  const target = configTabs.find(item => item.id === tab)
  return target?.panels.flatMap(panel => fieldsByPanel.value.get(panel.id) || []) || []
}
function dirtyFields(tab) {
  return fieldsFor(tab).filter(item => displayValue(item.configValue) !== displayValue(previousValues.value[item.configKey]))
}
const unsaved = tab => !props.readOnly && (dirtyFields(tab).length > 0 || !!editorDirty[tab])
const hasDrafts = computed(() => allowedTabs.value.some(tab => unsaved(tab.id)))
const readiness = tab => props.readOnly ? null : configReadiness(tab, snapshotLoaded.value ? previousValues.value : null)
const displayedPanels = computed(() => active.value.panels.flatMap(panel => {
  const all = orderConfigFields(fieldsByPanel.value.get(panel.id) || [])
  if (!all.length) return []
  return [{ ...panel, fields: all.filter(item => !isSmsTemplateField(item.configKey)), templates: all.filter(item => isSmsTemplateField(item.configKey)),
    status: ['wechat','sms'].includes(panel.id) ? readiness(active.value.id) : null }]
}))
function templateSelected(panel) {
  return panel.templates.find(item => item.configKey === templateSelection[panel.id]) || panel.templates[0]
}

function openGroup(group) {
  if (group.id === activeGroup.value.id) return
  openTab(group.tabs.find(tab => tab.id === lastVisited[group.id])?.id || group.tabs[0].id)
}
async function openSection(id) {
  if (uploading.size) { ElMessage.warning('请等待文件上传完成'); return }
  if (id !== 'origin') {
    if (siteAnchor.value && id === 'site') scrollWorkArea()
    siteAnchor.value = false
    openTab(id)
    return
  }
  openTab('site')
  siteAnchor.value = true
  await nextTick()
  document.getElementById('config-panel-origin')?.scrollIntoView({ block:'end' })
}
function scrollWorkArea() {
  const area = document.querySelector('.layout-main')
  if (area) area.scrollTop = 0
}
function openTab(id) {
  if (!allowedTabs.value.some(tab => tab.id === id) || id === active.value.id) return
  if (uploading.size) { ElMessage.warning('请等待文件上传完成'); return }
  siteAnchor.value = false
  picked.value = id
  scrollWorkArea()
  router.replace({ query: { ...route.query, tab: id } })
}
watch(() => route.query.tab, value => {
  picked.value = typeof value === 'string' && configTabs.some(tab => tab.id === value) ? value : defaultConfigTab
}, { immediate: true })
watch(active, tab => {
  lastVisited[tab.group] = tab.id
  if (tab.editor) visited.add(tab.id)
}, { immediate: true })

const savingCurrent = computed(() => submitting.value || !!editorBusy[active.value.id])
const showSaveBar = computed(() => active.value.editor
  ? active.value.editor === 'membership' || (active.value.editor === 'email' && emailAvailable.value)
  : displayedPanels.value.length > 0 && !loading.value && !loadError.value)
function setEditor(id, instance) {
  if (instance) editorRefs.set(id, instance)
  else editorRefs.delete(id)
}
function markSaved(id) {
  saveTimes[id] = new Intl.DateTimeFormat('sv-SE', { dateStyle:'short', timeStyle:'short' }).format(new Date())
}
async function saveCurrent() {
  if (props.readOnly || savingCurrent.value || uploading.size) return
  if (!active.value.editor) return handleSave()
  try { await editorRefs.get(active.value.id)?.save() } catch { /* Keep the editor draft after request failure. */ }
}
function discardCurrent() {
  if (props.readOnly || savingCurrent.value || uploading.size) return
  if (active.value.editor) { editorRefs.get(active.value.id)?.discard(); return }
  for (const item of dirtyFields(active.value.id)) item.configValue = previousValues.value[item.configKey]
}

async function fetchData() {
  if (props.readOnly || loading.value) return
  loading.value = true
  loadError.value = ''
  try {
    const { data } = await getSysConfigList({ silent: true })
    configList.value = (data || []).map(item => ({ ...item, configValue: item.configValue ?? '' }))
    previousValues.value = Object.fromEntries(configList.value.map(item => [item.configKey, item.configValue]))
    snapshotLoaded.value = true
  } catch {
    loadError.value = '读取配置失败，请重试。'
  } finally { loading.value = false }
}

const secretKeys = new Set(['wx.pay.api-v3-key', 'wx.pay.official-appsecret', 'sms.aliyun.access_key_secret', 'wechat.official.app_secret', 'risk.media.token', 'email.notifications.password'])
async function handleSave() {
  const tab = active.value
  const changes = dirtyFields(tab.id)
  if (props.readOnly || submitting.value || uploading.size || !changes.length) return
  if (changes.some(item => notificationValidity[item.configKey] === false)) return ElMessage.warning('请先修正通知模板中的错误')
  submitting.value = true
  const items = changes.map(({ id, configKey, configValue }) => ({ id, configKey, configValue: displayValue(configValue) }))
  try {
    await batchUpdateSysConfig(items)
    for (const item of items) {
      const stored = secretKeys.has(item.configKey) && item.configValue ? '********' : item.configValue
      previousValues.value[item.configKey] = stored
      const current = configList.value.find(entry => entry.id === item.id)
      if (current && displayValue(current.configValue) === item.configValue) current.configValue = stored
    }
    markSaved(tab.id)
    ElMessage.success(tab.title + '已保存')
  } catch { /* Managed by the request layer; keep the unsaved values. */ }
  finally { submitting.value = false }
}
function setUploading(key, busy) {
  if (busy) uploading.add(key)
  else uploading.delete(key)
}
async function handleCertificateUpload(item, options) {
  if (props.readOnly || submitting.value || uploading.has(item.configKey)) return
  uploading.add(item.configKey)
  try {
    const res = await uploadWxPayCertificate(item.configKey, options.file)
    item.configValue = res.data || ''
    options.onSuccess?.(res)
    ElMessage.success('文件已上传，请保存本页配置')
  } catch (error) { options.onError?.(error) }
  finally { uploading.delete(item.configKey) }
}

function warnUnload(event) {
  if (!hasDrafts.value && !uploading.size) return
  event.preventDefault()
  event.returnValue = ''
}
onBeforeRouteLeave(async to => {
  if (props.readOnly || to.path.endsWith('/login')) return true
  if (uploading.size) { ElMessage.warning('请等待文件上传完成'); return false }
  if (!hasDrafts.value) return true
  try { await ElMessageBox.confirm('部分页面有未保存的配置。离开后这些更改会丢失。','离开系统配置',{confirmButtonText:'离开',cancelButtonText:'继续编辑',type:'warning'}); return true }
  catch { return false }
})
onMounted(() => {
  if (props.readOnly) return
  loadPlatformFeatureVisibility()
  fetchData()
  window.addEventListener('beforeunload', warnUnload)
})
onUnmounted(() => window.removeEventListener('beforeunload', warnUnload))
</script>

<style scoped>
.settings-workspace { min-width:0; max-width:1480px; margin-inline:auto; display:flex; flex-direction:column; gap:18px; --settings-bar-offset:0px }
.settings-workspace-mobile { --settings-bar-offset:calc(62px + var(--app-safe-bottom, 0px)) }
.settings-page-head { display:flex; align-items:center; gap:14px }
.settings-page-head > svg { width:32px; height:32px; flex:none; color:var(--el-text-color-regular) }
.settings-page-head h1 { margin:0; font-size:26px; line-height:1.25 }
.settings-page-head p,.settings-header p { margin:4px 0 0; color:var(--el-text-color-secondary); font-size:13px }
.settings-categories { display:grid; grid-template-columns:repeat(5,minmax(0,1fr)); gap:12px }
.settings-category { position:relative; display:flex; align-items:center; gap:11px; min-width:0; min-height:66px; padding:10px 13px;
  background:var(--el-bg-color); color:var(--el-text-color-primary); border:1px solid var(--el-border-color-light); border-radius:12px; font:inherit; text-align:left; cursor:pointer }
.settings-category > svg { width:22px; height:22px; flex:none }
.settings-category strong,.settings-category small { display:block; line-height:1.4 }
.settings-category strong { font-size:14px }
.settings-category small { margin-top:3px; color:var(--el-text-color-secondary); font-size:12px }
.settings-category:is(:hover,:focus-visible) { border-color:var(--el-text-color-secondary) }
.settings-category[aria-current] { background:#252526; border-color:#252526; color:#fff }
.settings-category[aria-current] small { color:#d4d4d5 }
html[data-theme='dark'] .settings-category[aria-current] { background:#e9e9ed; border-color:#e9e9ed; color:#202023 }
html[data-theme='dark'] .settings-category[aria-current] small { color:#434348 }
.settings-body { display:grid; grid-template-columns:minmax(150px,19%) minmax(0,1fr); gap:14px; align-items:stretch }
.settings-nav { padding:12px 9px; border-radius:12px; background:var(--el-bg-color); border:1px solid var(--el-border-color-lighter) }
.settings-nav-item { display:flex; width:100%; min-height:42px; align-items:center; gap:9px; padding:7px 9px; margin-bottom:3px; border:1px solid transparent;
  border-radius:8px; color:var(--el-text-color-primary); background:transparent; text-align:left; font:inherit; cursor:pointer }
.settings-nav-item > svg { width:18px; height:18px; flex:none }
.settings-nav-item:is(:hover,[aria-current]) { background:var(--el-fill-color-light) }
.settings-nav-item[aria-current] { border-color:var(--el-text-color-primary); font-weight:650 }
.settings-nav-item .settings-dot,.settings-draft { margin-left:auto }
.settings-dot,.settings-category-dot { width:7px; height:7px; flex-shrink:0; border-radius:50%; background:var(--el-color-danger) }
.settings-category .settings-dot,.settings-category-dot { position:absolute; top:8px; right:8px }
.settings-category-dot { background:var(--el-color-warning) }
.settings-draft { color:var(--el-text-color-secondary); font-size:11px; white-space:nowrap }
.settings-content { display:grid; align-content:start; gap:12px; min-width:0; padding:15px; background:var(--el-bg-color); border-radius:12px; border:1px solid var(--el-border-color-lighter) }
.settings-header,.settings-panel-head,.settings-footer { display:flex; justify-content:space-between; align-items:center; gap:10px; flex-wrap:wrap }
.settings-header h2 { margin:0; font-size:21px; line-height:1.3 }
.settings-panel { border:1px solid var(--el-border-color-light); border-radius:10px; background:var(--el-bg-color); min-width:0; scroll-margin-bottom:85px }
.settings-panel-head { padding:8px 14px; background:var(--el-fill-color-light); border-radius:9px 9px 0 0 }
.settings-panel-head h3 { display:flex; align-items:center; gap:8px; margin:0; font-size:15px; line-height:1.4 }
.settings-panel-head h3 svg { width:17px; height:17px; flex:none }
.settings-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px 18px; margin:0; padding:12px 15px }
.settings-grid:empty { display:none }
.settings-form { display:grid; gap:12px; min-width:0 }
.settings-workspace .settings-form :deep(.el-form-item) { margin-bottom:0 }
.settings-tag { padding:2px 7px; border-radius:6px; background:var(--el-bg-color); color:var(--el-text-color-secondary); font-size:12px }
.settings-tag.incomplete { color:var(--el-color-danger); background:var(--el-color-danger-light-9) }
.settings-footer { position:sticky; z-index:5; bottom:var(--settings-bar-offset); min-height:52px; padding:9px 12px;
  margin-top:-6px; background:var(--el-bg-color); border:1px solid var(--el-border-color-light); border-radius:10px; box-shadow:0 -3px 12px rgba(0,0,0,.05) }
.settings-footer-actions { display:flex; justify-content:flex-end; gap:8px; margin-left:auto }
.settings-footer-actions .el-button { margin-left:0 }
.settings-footer-status { display:inline-flex; align-items:center; gap:6px; color:var(--el-text-color-secondary); font-size:12px }
.settings-note { display:flex; align-items:flex-start; gap:7px; margin:0; color:var(--el-text-color-secondary); font-size:12px; line-height:1.55; overflow-wrap:anywhere }
.settings-panel .settings-note { margin:9px 15px 0 }
.settings-note svg,.settings-footer-status svg { flex-shrink:0; width:14px; height:14px; margin-top:2px }
.settings-preview dt { color:var(--el-text-color-secondary); font-size:12px; margin-bottom:4px }
.settings-preview dd { margin:0; padding:7px 9px; border-radius:6px; background:var(--el-fill-color-light) }
.settings-template { display:grid; gap:10px; padding:12px 15px; min-width:0 }
.settings-template > label { display:flex; align-items:center; gap:8px; flex-wrap:wrap; font-size:13px }
.settings-template select { min-height:36px; max-width:100%; padding:6px 9px; border:1px solid var(--el-border-color); border-radius:8px; background:var(--el-fill-color-light); color:var(--el-text-color-primary); font:inherit }
.settings-special { min-width:0 }
@media(max-width:1050px) {
  .settings-category { gap:7px; padding:9px }
  .settings-category small { display:none }
}
@media(max-width:760px) {
  .settings-workspace { gap:12px }
  .settings-body { grid-template-columns:minmax(0,1fr); gap:8px }
  .settings-nav { display:flex; flex-wrap:wrap; gap:5px; padding:5px }
  .settings-nav-item { width:auto; flex:none; margin-bottom:0; white-space:nowrap; padding-inline:8px }
  .settings-content { padding:10px }
  .settings-categories { grid-template-columns:repeat(3,minmax(0,1fr)); gap:6px }
  .settings-category { min-height:44px; padding:7px 9px; gap:6px }
  .settings-category > svg { width:17px; height:17px }
  .settings-category strong { font-size:13px; overflow-wrap:anywhere }
  .settings-category .settings-dot,.settings-category-dot { top:4px; right:5px }
  .settings-page-head h1 { font-size:22px }
}
@media(max-width:360px) {
  .settings-categories { grid-template-columns:repeat(2,minmax(0,1fr)) }
}
@media(max-width:540px) {
  .settings-header h2 { font-size:18px }
  .settings-grid { grid-template-columns:minmax(0,1fr); padding:11px; gap:12px }
  .settings-footer .el-button { min-height:44px }
}
@media(max-height:480px) {
  .settings-workspace-mobile { --settings-bar-offset:calc(52px + var(--app-safe-bottom, 0px)) }
}
html[data-keyboard-open] .settings-workspace-mobile { --settings-bar-offset:0px }
</style>
