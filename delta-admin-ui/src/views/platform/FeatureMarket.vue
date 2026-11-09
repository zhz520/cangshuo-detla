<template>
  <div class="platform-page" v-loading="loading">
    <el-card shadow="never">
      <template #header><div class="section-heading"><div><h2>功能商城</h2><p>授权期限和剩余天数由授权中心同步；是否实际启用由本系统管理员自行控制。</p></div><el-button :loading="syncing" :disabled="loading || !!changing || !!buying" @click="authorityConfigured ? sync() : load()">{{ authorityConfigured ? '同步授权' : '刷新' }}</el-button></div></template>
      <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <template v-if="overview">
        <el-descriptions :column="isCompact ? 1 : 2" border>
          <el-descriptions-item label="客户编号">{{ overview.customerId }}</el-descriptions-item>
          <el-descriptions-item label="当前版本">{{ overview.version }} <el-tag size="small" type="info">{{ overview.channel === 'preview' ? '预览版' : '正式版' }}</el-tag></el-descriptions-item>
          <el-descriptions-item label="实例编号">{{ overview.instanceId }}</el-descriptions-item>
          <el-descriptions-item label="授权状态">{{ licenseLabels[overview.licenseStatus] || overview.licenseStatus }}</el-descriptions-item>
          <el-descriptions-item label="维护期至">{{ overview.expiresAt ? formatTime(overview.expiresAt) : '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="authorityConfigured" label="上次成功同步"><span data-testid="last-sync">{{ lastSync ? formatTime(lastSync) : '尚未同步' }}</span></el-descriptions-item>
          <el-descriptions-item label="授权域名">{{ (overview.allowedDomains || []).join('、') || '未配置' }}</el-descriptions-item>
        </el-descriptions>
        <div class="license-actions"><el-button type="primary" :disabled="!overview.verifierConfigured || overview.managedByAuthority" @click="licenseVisible = true">导入授权</el-button></div>
        <p v-if="!overview.verifierConfigured" class="muted">授权服务尚未配置，请联系维护人员完成接入后开通付费模块。</p>
        <p v-else-if="overview.managedByAuthority" class="muted">授权状态由授权中心同步，开通或续费后可点击「同步授权」。</p>
      </template>
    </el-card>
    <div v-if="features.length" class="feature-grid">
      <el-card v-for="feature in features" :key="feature.id" shadow="never" class="feature-card">
        <div class="section-heading"><h3>{{ feature.name }}</h3><div class="feature-tags"><el-tag :type="feature.enabled ? 'success' : 'info'">{{ statusLabels[feature.status] || feature.status }}</el-tag><el-tag v-if="feature.managedByAuthority" type="warning" effect="plain">授权中心同步</el-tag></div></div>
        <p class="description">{{ feature.description }}</p>
        <ul><li v-for="capability in feature.capabilities" :key="capability">{{ capability }}</li></ul>
        <p class="commercial-terms">{{ purchaseTermsFor(feature) }}</p>
        <p v-if="feature.paid" class="license-period">{{ validityText(feature) }}</p>
        <p class="muted">最低版本 {{ feature.minVersion }}<span v-if="feature.dependencies.length"> · 依赖基础业务系统</span></p>
        <div class="feature-actions">
          <el-button v-if="feature.enabled" type="primary" @click="$router.push(feature.entryPath)">进入功能</el-button>
          <el-button v-else-if="!feature.entitled && !isPurchased(feature)" type="primary" :disabled="syncing || loading || !!changing || !!buying" :loading="buying===feature.id" @click="buy(feature)">立即购买</el-button>
          <el-button v-if="canRenew(feature)" plain type="warning" :disabled="syncing || loading || !!changing || !!buying" :loading="buying===feature.id" @click="buy(feature)">续费</el-button>
          <el-switch v-if="feature.paid" :model-value="feature.enabled" :aria-label="`启用${feature.name}`" :disabled="!feature.entitled || syncing || loading || !!changing || !!buying" :loading="changing === feature.id" active-text="启用" @change="value => toggle(feature, value)" />
        </div>
      </el-card>
    </div>
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
    || ['ENABLED', 'DISABLED', 'EXPIRED', 'REVOKED'].includes(feature.status))
}

export function purchaseTerms(feature, now = Date.now(), maintenanceUntil = null) {
  if (!feature?.paid) return '基础功能 · 已包含'
  const price = feature.priceCents == null ? '人工报价' : '¥' + (feature.priceCents / 100).toFixed(2)
  const termDays = Number(feature.termDays)
  if (!(termDays > 0)) return `付费扩展 · ${price} · 参考周期以授权中心为准 · 在线购买`
  const reference = `参考 ${termDays} 天`
  if (isPurchasedFeature(feature)) return `付费扩展 · ${price} · ${reference} · 在线续费`
  const maintenanceExpiry = Date.parse(maintenanceUntil)
  if (!Number.isFinite(maintenanceExpiry) || !Number.isFinite(now)) {
    return `付费扩展 · ${price} · ${reference} · 实际可购天数以维护期为准 · 在线购买`
  }
  const available = Math.max(0, Math.ceil((Math.min(now + termDays * DAY_MILLISECONDS, maintenanceExpiry) - now) / DAY_MILLISECONDS))
  const actual = available > 0 ? `本次最多可购 ${available} 天` : '当前维护期已无可购天数'
  return `付费扩展 · ${price} · ${reference} · ${actual} · 在线购买`
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
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useResponsive } from '@/utils/responsive'
import { getFeatureMarket, syncFeatureMarket, importPlatformLicense, setPlatformFeatureEnabled, createPlatformCheckout } from '@/api/platform'
import { applyPlatformFeatureVisibility } from '@/utils/platform-feature-visibility'
import { getToken } from '@/utils/auth'

const { isCompact } = useResponsive()
const overview = ref(null), features = ref([]), loading = ref(false), error = ref(''), changing = ref(''), buying = ref('')
const lastSync = ref(null), authorityConfigured = ref(false), syncing = ref(false)
let active = true
const checkoutReturnKey = 'detla:feature-market:checkout-return'
const licenseVisible = ref(false), importing = ref(false), licenseText = ref('')
const licenseLabels = { NONE: '尚未导入授权', VALID: '维护有效', INVALID: '授权验证失败', EXPIRED: '维护已到期', NOT_YET_VALID: '授权尚未生效' }
const statusLabels = { INCLUDED: '已包含', ENABLED: '已启用', DISABLED: '已授权，未启用', EXPIRED: '已到期', REVOKED: '授权已暂停', NOT_PURCHASED: '待开通' }
const formatTime = formatLicenseTime
const validityText = licensePeriodText
function purchaseTermsFor(feature) { return purchaseTerms(feature, Date.now(), overview.value?.expiresAt) }
function isPurchased(feature) { return isPurchasedFeature(feature) }
function canRenew(feature) { return isPurchased(feature) && feature.status !== 'REVOKED' }
function applyMarket(data, token) {
  if (!active || getToken() !== token) return false
  if (!data?.overview || !Array.isArray(data.features)) throw new Error('功能商城返回的数据不完整')
  overview.value = data.overview
  features.value = data.features
  authorityConfigured.value = data.authorityConfigured
  lastSync.value = data.lastSync || null
  applyPlatformFeatureVisibility(data.features, token)
  return true
}
async function load() {
  if (loading.value || syncing.value) return
  const token = getToken()
  loading.value = true
  error.value = ''
  try { applyMarket((await getFeatureMarket()).data, token) }
  catch { if (active && getToken() === token) error.value = '功能与授权信息加载失败，请刷新重试。' }
  finally { loading.value = false }
}
async function sync({ returning = false } = {}) {
  if (!authorityConfigured.value || syncing.value || loading.value || changing.value || buying.value) return
  const token = getToken()
  syncing.value = true
  error.value = ''
  try {
    if (applyMarket((await syncFeatureMarket()).data, token) && !returning) ElMessage.success('授权已同步')
  } catch (failure) {
    if (active && getToken() === token) {
      const message = failure.response?.data?.msg || (failure.requestHandled && failure.code ? failure.message : '')
      error.value = message || '同步授权失败，保留上次数据，请稍后重试。'
    }
  } finally { syncing.value = false }
}

function rememberCheckoutReturn() {
  try {
    if (overview.value?.instanceId) {
      sessionStorage.setItem(checkoutReturnKey, JSON.stringify({ instanceId: overview.value.instanceId, time: Date.now() }))
    }
  } catch { /* Sync is still available through the button if browser storage is disabled. */ }
}
function syncOnCheckoutReturn() {
  if (!active || !authorityConfigured.value || !overview.value || syncing.value || loading.value || buying.value || changing.value) return
  let checkout
  try {
    checkout = JSON.parse(sessionStorage.getItem(checkoutReturnKey) || 'null')
    if (checkout) sessionStorage.removeItem(checkoutReturnKey)
  } catch { return }
  if (checkout?.instanceId === overview.value.instanceId && Date.now() - checkout.time < 2 * 60 * 60 * 1000) {
    // Returning from checkout only triggers a read from the authority, never grants a feature.
    sync({ returning: true })
  }
}
function onPageShow(event) {
  if (event.persisted) syncOnCheckoutReturn()
}
async function toggle(feature, enabled) {
  if (syncing.value || loading.value || changing.value || buying.value) return
  const token = getToken()
  changing.value = feature.id
  try {
    const response = await setPlatformFeatureEnabled(feature.id, enabled)
    if (!active || getToken() !== token) return
    features.value = response.data
    applyPlatformFeatureVisibility(response.data, token)
    ElMessage.success(enabled ? '功能已启用' : '功能已关闭')
  }
  catch { /* Request interceptor displays the server error. */ }
  finally { changing.value = '' }
}
async function buy(feature) {
  if (buying.value || syncing.value || loading.value || changing.value) return
  buying.value = feature.id
  try {
    const response = await createPlatformCheckout(feature.id)
    const checkoutUrl = response.data?.checkoutUrl
    if (!checkoutUrl) throw new Error('授权中心未返回支付地址')
    const checkout = new URL(checkoutUrl)
    if (checkout.protocol !== 'https:' && !(checkout.protocol === 'http:' && ['localhost', '127.0.0.1'].includes(checkout.hostname))) {
      throw new Error('授权中心支付地址无效')
    }
    rememberCheckoutReturn()
    window.location.assign(checkout.href)
  } catch (error) {
    if (error?.message) ElMessage.error(error.message)
  } finally { buying.value = '' }
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
onMounted(async () => {
  window.addEventListener('pageshow', onPageShow)
  await load()
  syncOnCheckoutReturn()
})
onBeforeUnmount(() => {
  active = false
  window.removeEventListener('pageshow', onPageShow)
})
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
ul { padding-left: 20px; color: #555b63; line-height: 2; }
:deep(.el-descriptions__content) { overflow-wrap: anywhere; }
:deep(.el-dialog p) { line-height: 1.8; }
</style>
