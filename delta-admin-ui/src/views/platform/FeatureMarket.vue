<template>
  <div class="platform-page" v-loading="loading">
    <el-card shadow="never">
      <template #header><div class="section-heading"><div><h2>功能商城</h2><p>按需开通扩展功能；价格周期、到期时间和剩余天数由授权中心同步。</p></div><el-button @click="load">刷新</el-button></div></template>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <template v-if="overview">
        <el-descriptions :column="isCompact ? 1 : 2" border>
          <el-descriptions-item label="客户编号">{{ overview.customerId }}</el-descriptions-item>
          <el-descriptions-item label="当前版本">{{ overview.version }} <el-tag size="small" type="info">{{ overview.channel === 'preview' ? '预览版' : '正式版' }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="实例编号">{{ overview.instanceId }}</el-descriptions-item>
          <el-descriptions-item label="授权状态">{{ licenseLabels[overview.licenseStatus] || overview.licenseStatus }}</el-descriptions-item>
          <el-descriptions-item label="维护期至">{{ overview.expiresAt ? formatTime(overview.expiresAt) : '—' }}</el-descriptions-item>
          <el-descriptions-item label="授权域名">{{ (overview.allowedDomains || []).join('、') || '未配置' }}</el-descriptions-item>
        </el-descriptions>
        <div class="license-actions"><el-button @click="openActivation(null, 'open')">查看开通信息</el-button><el-button type="primary" :disabled="!overview.verifierConfigured || overview.managedByAuthority" @click="licenseVisible = true">导入授权</el-button></div>
        <p v-if="!overview.verifierConfigured" class="muted">授权服务尚未配置，请联系维护人员完成接入后开通付费模块。</p>
        <p v-else-if="overview.managedByAuthority" class="muted">授权中心已接管功能状态，请等待自动同步，无需手动导入。</p>
      </template>
    </el-card>
    <div v-if="!error" class="feature-grid">
      <el-card v-for="feature in features" :key="feature.id" shadow="never" class="feature-card">
        <div class="section-heading"><h3>{{ feature.name }}</h3><div class="feature-tags"><el-tag :type="feature.enabled ? 'success' : 'info'">{{ statusLabels[feature.status] || feature.status }}</el-tag><el-tag v-if="feature.managedByAuthority && feature.entitled" type="warning" effect="plain">授权中心控制</el-tag></div></div>
        <p class="description">{{ feature.description }}</p>
        <ul><li v-for="capability in feature.capabilities" :key="capability">{{ capability }}</li></ul>
        <p class="commercial-terms">{{ purchaseTermsFor(feature) }}</p>
        <p v-if="feature.paid" class="license-period">{{ validityText(feature) }}</p>
        <p class="muted">最低版本 {{ feature.minVersion }}<span v-if="feature.dependencies.length"> · 依赖基础业务系统</span></p>
        <div class="feature-actions">
          <el-button v-if="feature.enabled" type="primary" @click="$router.push(feature.entryPath)">进入功能</el-button>
          <el-button v-else-if="!feature.entitled && !isPurchased(feature)" @click="openActivation(feature, 'open')">查看开通信息</el-button>
          <el-button v-if="isPurchased(feature)" plain type="warning" @click="openActivation(feature, 'renew')">续费</el-button>
          <el-switch v-if="feature.paid" :model-value="feature.enabled" :aria-label="`启用${feature.name}`" :disabled="!feature.entitled || feature.managedByAuthority || !!changing" :loading="changing === feature.id" active-text="启用" @change="value => toggle(feature, value)" />
          <span v-if="feature.managedByAuthority && feature.entitled" class="authority-note">启用状态由授权中心控制</span>
        </div>
      </el-card>
    </div>
    <el-dialog v-model="activationVisible" :title="activationTitle" width="min(560px, 94vw)">
      <p v-if="activationMode === 'renew'">请联系运营人员确认续费金额与授权期限。当前版本采用人工收款，确认到账后由运营人员重新签发授权，不会在此页面自动扣款或发起支付。</p>
      <p v-else>将下方客户编号、实例编号及需要开通的功能发送给运营人员，确认价格与可用天数后获取本实例的签名授权文件。</p>
      <p v-if="activationFeature" class="activation-target">目标功能：{{ activationFeature.name }} · {{ statusLabels[activationFeature.status] || activationFeature.status }}</p>
      <el-input :model-value="identityText" type="textarea" :rows="5" readonly />
      <p class="muted">授权文件导入后，远程托管的启用状态以授权中心为准；未托管的旧授权仍可在本页按原有方式启用模块。</p>
      <template #footer><el-button @click="activationVisible = false">关闭</el-button><el-button type="primary" @click="copyIdentity">复制实例信息</el-button></template>
    </el-dialog>
    <el-dialog v-model="licenseVisible" title="导入签名授权" width="min(620px, 94vw)" @closed="licenseText = ''">
      <p>粘贴维护人员提供的完整授权文件内容。系统会校验签名、客户编号、实例编号及有效期。</p>
      <el-input v-model="licenseText" type="textarea" :rows="9" maxlength="32768" placeholder="粘贴授权 JSON 内容" />
      <template #footer><el-button @click="licenseVisible = false">取消</el-button><el-button type="primary" :loading="importing" :disabled="!licenseText.trim()" @click="importLicense">校验并导入</el-button></template>
    </el-dialog>
  </div>
</template>

<script>
const DAY_MILLISECONDS = 86400000

export function remainingDays(expiresAt, now = Date.now()) {
  const expiry = Date.parse(expiresAt)
  if (!Number.isFinite(expiry) || !Number.isFinite(now)) return null
  return Math.max(0, Math.ceil((expiry - now) / DAY_MILLISECONDS))
}

export function isPurchasedFeature(feature) {
  return !!feature?.paid && (feature.entitled === true || !!feature.expiresAt
    || ['ENABLED', 'DISABLED', 'EXPIRED'].includes(feature.status))
}

export function purchaseTerms(feature, now = Date.now(), maintenanceUntil = null) {
  if (!feature?.paid) return '基础功能 · 已包含'
  const price = feature.priceCents == null ? '人工报价' : '¥' + (feature.priceCents / 100).toFixed(2)
  const termDays = Number(feature.termDays)
  if (!(termDays > 0)) return `付费扩展 · ${price} · 参考周期以授权中心为准 · 人工开通`
  const reference = `参考 ${termDays} 天`
  if (isPurchasedFeature(feature)) return `付费扩展 · ${price} · ${reference} · 人工续费`
  const maintenanceExpiry = Date.parse(maintenanceUntil)
  if (!Number.isFinite(maintenanceExpiry) || !Number.isFinite(now)) {
    return `付费扩展 · ${price} · ${reference} · 实际可购天数以维护期为准 · 人工开通`
  }
  const available = Math.max(0, Math.ceil((Math.min(now + termDays * DAY_MILLISECONDS, maintenanceExpiry) - now) / DAY_MILLISECONDS))
  const actual = available > 0 ? `本次最多可购 ${available} 天` : '当前维护期已无可购天数'
  return `付费扩展 · ${price} · ${reference} · ${actual} · 人工开通`
}

export function licensePeriodText(feature, now = Date.now()) {
  if (!feature?.paid) return ''
  if (!isPurchasedFeature(feature)) return '授权期限：未授权'
  const start = feature.startsAt ? `生效 ${formatLicenseTime(feature.startsAt)}` : '生效时间待同步'
  if (!feature.expiresAt) return feature.entitled === true ? `${start} · 长期有效` : '授权期限：未授权'
  const remaining = remainingDays(feature.expiresAt, now)
  const suffix = remaining == null ? '到期时间待确认' : remaining > 0 ? `剩余 ${remaining} 天` : '已到期'
  return `${start} · 授权至 ${formatLicenseTime(feature.expiresAt)} · ${suffix}`
}

export function formatLicenseTime(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false })
}
</script>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useResponsive } from '@/utils/responsive'
import { getPlatformOverview, getPlatformFeatures, importPlatformLicense, setPlatformFeatureEnabled } from '@/api/platform'
import {loadPlatformFeatureVisibility} from '@/utils/platform-feature-visibility'

const { isCompact } = useResponsive()
const overview = ref(null), features = ref([]), loading = ref(false), error = ref(''), changing = ref('')
const activationVisible = ref(false), licenseVisible = ref(false), importing = ref(false), licenseText = ref('')
const activationFeature = ref(null), activationMode = ref('open')
const licenseLabels = { NONE: '尚未导入授权', VALID: '维护有效', INVALID: '授权验证失败', EXPIRED: '维护已到期', NOT_YET_VALID: '授权尚未生效' }
const statusLabels = { INCLUDED: '已包含', ENABLED: '已启用', DISABLED: '已授权，未启用', EXPIRED: '已到期', NOT_PURCHASED: '待开通' }
const activationTitle = computed(() => activationMode.value === 'renew' ? '续费扩展功能' : '人工开通扩展功能')
const identityText = computed(() => {
  if (!overview.value) return ''
  const target = activationFeature.value ? `\n目标功能：${activationFeature.value.name}（${activationFeature.value.id}）\n当前期限：${licensePeriodText(activationFeature.value)}` : ''
  return `客户编号：${overview.value.customerId}\n实例编号：${overview.value.instanceId}\n当前版本：${overview.value.version}${target}`
})
const formatTime = formatLicenseTime
const validityText = licensePeriodText
function purchaseTermsFor(feature) { return purchaseTerms(feature, Date.now(), overview.value?.expiresAt) }
function isPurchased(feature) { return isPurchasedFeature(feature) }
function openActivation(feature = null, mode = 'open') {
  activationFeature.value = feature
  activationMode.value = mode
  activationVisible.value = true
}
async function load() {
  loading.value = true; error.value = ''
  try { const [info, modules] = await Promise.all([getPlatformOverview(), getPlatformFeatures({ silent: true })]); overview.value = info.data; features.value = modules.data }
  catch { error.value = '功能与授权信息加载失败，请刷新重试。'; overview.value = null; features.value = [] }
  finally { loading.value = false }
}
async function toggle(feature, enabled) {
  if (feature.managedByAuthority) return ElMessage.info('功能启用状态由授权中心管理')
  changing.value = feature.id
  try { const response = await setPlatformFeatureEnabled(feature.id, enabled); features.value = response.data; await loadPlatformFeatureVisibility(true); ElMessage.success(enabled ? '功能已启用' : '功能已关闭') }
  catch { /* Request interceptor displays the server error. */ }
  finally { changing.value = '' }
}
async function copyIdentity() {
  try { await navigator.clipboard.writeText(identityText.value); ElMessage.success('实例信息已复制') }
  catch { ElMessage.info('请手动选中并复制上方实例信息') }
}
async function importLicense() {
  let envelope
  try { envelope = JSON.parse(licenseText.value) } catch { return ElMessage.error('授权文件不是有效的 JSON，请粘贴完整内容') }
  if (!envelope || typeof envelope !== 'object' || Array.isArray(envelope)) return ElMessage.error('授权文件格式不正确')
  importing.value = true
  try { await importPlatformLicense(envelope); licenseVisible.value = false; licenseText.value = ''; await load(); ElMessage.success('授权已导入，请启用需要使用的模块') }
  catch { /* Keep the dialog open for correction. */ }
  finally { importing.value = false }
}
onMounted(load)
</script>

<style scoped>
.platform-page { display: grid; gap: 18px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 14px; flex-wrap: wrap; }
h2, h3 { margin: 0; color: #17191c; }
h2 { font-size: 20px; } h3 { font-size: 17px; }
.section-heading p, .muted { color: #73777e; font-size: 13px; line-height: 1.7; }
.feature-tags { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
.license-actions, .feature-actions { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin-top: 16px; }
.feature-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 340px), 1fr)); gap: 18px; }
.feature-card :deep(.el-card__body) { display: flex; flex-direction: column; height: 100%; box-sizing: border-box; }
.feature-actions { margin-top: auto; padding-top: 14px; }
.description { color: #555b63; line-height: 1.8; }
.commercial-terms { color: #3c4652; font-weight: 600; line-height: 1.7; }
.license-period { margin: 8px 0; padding: 10px 12px; color: #5b4a20; background: #fff8e8; border: 1px solid #f1dfb6; border-radius: 8px; line-height: 1.7; }
.authority-note { color: #8a6415; font-size: 13px; }
.activation-target { padding: 10px 12px; color: #3c4652; background: #f6f7f9; border-radius: 8px; }
ul { padding-left: 20px; color: #555b63; line-height: 2; }
:deep(.el-descriptions__content) { overflow-wrap: anywhere; }
:deep(.el-dialog p) { line-height: 1.8; }
</style>
