<template>
  <div class="sms-account-page">
    <div class="page-head">
      <div>
        <h2>{{ label }}服务</h2>
        <p>{{ identity ? '自动核验姓名与身份证号；未启用时按原有人工审核流程处理。' : '可选择授权中心统一服务，或在本实例直接配置阿里云短信。' }}</p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>
    <el-card v-if="settings" shadow="never" class="account-card">
      <el-alert v-if="settings.legacyActive" title="当前使用阿里云短信，由本实例直接发送并按阿里云账单计费。" type="info" :closable="false" show-icon />
      <el-form label-position="top">
        <div class="settings-grid">
          <el-form-item label="服务提供方">
            <el-select v-model="settings.provider" :disabled="readOnly || saving">
              <el-option label="授权中心（统一套餐）" value="AUTHORITY" />
              <el-option v-if="!identity" label="阿里云短信（本实例配置）" value="ALIYUN" />
            </el-select>
          </el-form-item>
          <el-form-item :label="identity ? '自动二要素核验' : '启用短信服务'">
            <el-switch v-model="settings.enabled" :disabled="readOnly || saving || (settings.provider === 'AUTHORITY' && !settings.configured && !settings.enabled)" @change="settleToggles" />
          </el-form-item>
          <el-form-item v-if="!identity" label="启用短信验证码">
            <el-switch v-model="settings.otpEnabled" :disabled="readOnly || saving || !settings.enabled" />
          </el-form-item>
        </div>
        <div v-if="!identity && settings.provider === 'ALIYUN'" class="settings-grid aliyun-grid">
          <el-form-item label="AccessKey ID"><el-input v-model="settings.accessKeyId" autocomplete="off" /></el-form-item>
          <el-form-item label="AccessKey Secret">
            <el-input v-model="settings.accessKeySecret" type="password" show-password autocomplete="new-password"
              :placeholder="settings.hasAccessKeySecret ? '已保存，留空保留原值' : '填写阿里云 RAM 用户 Secret'" />
          </el-form-item>
          <el-form-item label="短信签名"><el-input v-model="settings.signName" placeholder="已审核通过的短信签名" /></el-form-item>
          <el-form-item label="Endpoint"><el-input v-model="settings.endpoint" readonly /></el-form-item>
          <el-form-item label="验证码模板 Code"><el-input v-model="settings.verificationTemplateCode" placeholder="SMS_xxx" /></el-form-item>
          <el-form-item label="通知老板结单模板 Code"><el-input v-model="settings.playerFinishTemplateCode" placeholder="可选" /></el-form-item>
          <el-form-item label="消息提醒模板 Code"><el-input v-model="settings.csMessageTemplateCode" placeholder="可选" /></el-form-item>
          <el-form-item label="邀请协同模板 Code"><el-input v-model="settings.teammateInvitedTemplateCode" placeholder="可选" /></el-form-item>
        </div>
        <el-alert v-if="!identity && settings.provider === 'ALIYUN'" type="info" :closable="false" show-icon
          title="阿里云短信由本实例直接发送，费用从阿里云账户扣取，不使用授权中心短信套餐。" />
        <el-tag v-if="!settings.configured" type="warning">尚未配置</el-tag>
        <el-button v-if="!readOnly" type="primary" :disabled="!dirty" :loading="saving" @click="save">保存设置</el-button>
      </el-form>
    </el-card>
    <el-card v-if="identity" shadow="never" class="account-card">
      <template #header><div class="card-head"><strong>同一账号失败次数限制</strong><span>防止连续输入错误实名信息</span></div></template>
      <el-form label-position="top">
        <div class="settings-grid">
          <el-form-item label="启用限制"><el-switch v-model="limitSettings.enabled" :disabled="readOnly || limitSaving" /></el-form-item>
          <el-form-item label="最大失败次数"><el-input-number v-model="limitSettings.maxAttempts" :min="1" :max="20" :disabled="readOnly || limitSaving" /></el-form-item>
          <el-form-item label="统计窗口（小时）"><el-input-number v-model="limitSettings.windowHours" :min="1" :max="720" :disabled="readOnly || limitSaving" /></el-form-item>
          <el-form-item label="锁定时长（小时）"><el-input-number v-model="limitSettings.lockHours" :min="1" :max="720" :disabled="readOnly || limitSaving" /></el-form-item>
        </div>
        <el-button v-if="!readOnly" type="primary" :loading="limitSaving" @click="saveLimits">保存限制</el-button>
      </el-form>
    </el-card>
    <LoadFeedback :loading="loading" :error="loadError" :label="label + '服务账户'" @retry="load" />
    <template v-if="account && (identity || settings.provider === 'AUTHORITY')">
      <el-card shadow="never" class="account-card">
        <div class="account-grid">
          <div><span>计费模式</span><strong>{{ modeLabel(account.billingMode) }}</strong></div>
          <div><span>单{{ unit }}价格</span><strong>{{ money(account.unitPriceCents) }}</strong></div>
          <div><span>可用余额</span><strong>{{ money(availableBalance) }}</strong></div>
          <div><span>预占金额</span><strong>{{ money(account.reservedCents) }}</strong></div>
        </div>
        <div v-if="account.usage" class="usage-line">
          <span>{{ identity ? '已核验' : '回执发送成功' }} <strong>{{ Number(account.usage.charged_count ?? account.usage.delivered_count ?? 0) }}</strong></span>
          <span>{{ identity ? '不匹配' : '失败' }} <strong>{{ Number(account.usage.mismatch_count ?? account.usage.failed_count ?? 0) }}</strong></span>
          <span>{{ identity ? '已释放' : '待确认' }} <strong>{{ Number(account.usage.released_count ?? account.usage.pending_count ?? 0) }}</strong></span>
        </div>
        <el-alert v-if="account.billingMode === 'PACKAGE' && !account.packages?.some(item => Number(item.remaining_count) > 0)" title="没有可用套餐额度" type="warning" :closable="false" show-icon />
      </el-card>

      <el-card shadow="never" class="account-card">
        <template #header><div class="card-head"><strong>余额充值</strong><span>支付成功后自动入账，可继续按量使用</span></div></template>
        <div v-if="rechargePackages.length" class="recharge-grid">
          <button v-for="item in rechargePackages" :key="item.id" class="recharge-item" :disabled="!!buying" @click="buy(item)">
            <strong>{{ money(item.credit_cents) }}</strong>
            <span>售价 {{ money(item.price_cents) }}</span>
            <small v-if="Number(item.credit_cents) > Number(item.price_cents)">赠 {{ money(Number(item.credit_cents) - Number(item.price_cents)) }}</small>
          </button>
        </div>
        <el-empty v-else description="暂无余额充值套餐，请联系平台运营配置" />
      </el-card>

      <el-card shadow="never" class="account-card">
        <template #header><div class="card-head"><strong>已购套餐</strong><span>优先使用最早到期的套餐</span></div></template>
        <el-table :data="account.packages || []" stripe>
          <el-table-column prop="name" label="套餐" min-width="180" />
          <el-table-column label="剩余额度" width="120"><template #default="{row}">{{ row.remaining_count }} {{ unit }}</template></el-table-column>
          <el-table-column label="有效期至" min-width="180"><template #default="{row}">{{ time(row.valid_until) }}</template></el-table-column>
        </el-table>
        <el-empty v-if="!account.packages?.length" description="暂无有效套餐" />
      </el-card>

      <el-card v-if="!identity" shadow="never" class="account-card">
        <template #header><div class="card-head"><strong>可用短信模板</strong><span>模板内容、签名和计费由授权中心统一维护</span></div></template>
        <div v-if="account.templates?.length" class="template-list">
          <el-tag v-for="item in account.templates" :key="item.template_key" effect="plain">{{ item.name || item.template_key }}<small>{{ item.template_key }}</small></el-tag>
        </div>
        <el-empty v-else description="暂无可用模板，请联系平台维护人员" />
      </el-card>

      <el-card shadow="never" class="account-card">
        <template #header><div class="card-head"><strong>可购买套餐</strong><span>微信支付，支付确认后套餐自动发放</span></div></template>
        <el-table :data="servicePackages" stripe>
          <el-table-column prop="name" label="套餐" min-width="180" />
          <el-table-column :prop="identity ? 'verification_count' : 'message_count'" :label="'额度（' + unit + '）'" width="110" />
          <el-table-column label="价格" width="120"><template #default="{row}">{{ money(row.price_cents) }}</template></el-table-column>
          <el-table-column prop="validity_days" label="有效天数" width="110" />
          <el-table-column v-if="!readOnly" label="操作" width="120"><template #default="{row}"><el-button type="primary" plain :loading="buying === row.id" :disabled="!!buying" @click="buy(row)">购买</el-button></template></el-table-column>
        </el-table>
        <el-empty v-if="!servicePackages.length" description="暂无上架套餐，请联系平台运营" />
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { isDemoSession } from '@/utils/staff-permissions'
import { ElMessage } from 'element-plus'
import { checkoutIdentityPackage, checkoutSmsPackage, getAuthoritySettings, getIdentityGatewayAccount, getIdentityLimits, getSmsGatewayAccount, saveAuthoritySettings, saveIdentityLimits } from '@/api/system'
import LoadFeedback from '@/components/LoadFeedback.vue'

const route = useRoute()
const identity = computed(() => route.meta.feature === 'identity_verification')
const kind = computed(() => identity.value ? 'identity' : 'sms')
const label = computed(() => identity.value ? '实名' : '短信')
const unit = computed(() => identity.value ? '次' : '条')
const account = ref(null), loading = ref(false), loadError = ref(''), settings = ref(null)
const saved = ref(null), saving = ref(false), buying = ref('')
const limitSettings = reactive({ enabled: true, maxAttempts: 5, windowHours: 24, lockHours: 24 })
const limitSaving = ref(false)
const editableFields = ['provider', 'enabled', 'otpEnabled', 'endpoint', 'accessKeyId', 'signName',
  'verificationTemplateCode', 'playerFinishTemplateCode', 'csMessageTemplateCode', 'teammateInvitedTemplateCode']
const dirty = computed(() => !!saved.value && editableFields.some(key => settings.value[key] !== saved.value[key])
  || !!settings.value?.accessKeySecret)
const availableBalance = computed(() => Math.max(0, Number(account.value?.balanceCents || 0) - Number(account.value?.reservedCents || 0)))
const rechargePackages = computed(() => (account.value?.availablePackages || []).filter(item => Number(item.credit_cents) > 0))
const servicePackages = computed(() => (account.value?.availablePackages || []).filter(item => !(Number(item.credit_cents) > 0)))
const readOnly = computed(() => isDemoSession())
let sequence = 0
const money = cents => '¥' + (Number(cents || 0) / 100).toFixed(2)
const time = value => value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
const modeLabel = mode => ({ METERED: '按量计费', PACKAGE: '只套餐', HYBRID: '套餐 + 按量' }[mode] || mode || '未配置')

async function load() {
  if (readOnly.value) return
  const run = ++sequence
  loading.value = true; loadError.value = ''
  try {
    const limitsPromise = identity.value ? getIdentityLimits().catch(() => null) : Promise.resolve(null)
    const current = await getAuthoritySettings(kind.value)
    const limits = await limitsPromise
    if (run !== sequence) return
    if (identity.value && limits?.data) Object.assign(limitSettings, limits.data)
    resetSettings(current.data)
    if (!identity.value && current.data.provider === 'ALIYUN') { account.value = null; return }
    if (!current.data.configured) { account.value = null; return }
    const { data } = await (identity.value ? getIdentityGatewayAccount() : getSmsGatewayAccount())
    if (run === sequence) account.value = normalize(data)
  } catch (error) {
    if (run === sequence) { loadError.value = error?.message || '加载服务账户失败'; account.value = null }
  } finally { if (run === sequence) loading.value = false }
}
function resetSettings(data) {
  const normalized = { endpoint: 'dysmsapi.aliyuncs.com', accessKeyId: '', accessKeySecret: '', signName: '',
    verificationTemplateCode: '', playerFinishTemplateCode: '', csMessageTemplateCode: '', teammateInvitedTemplateCode: '',
    ...data }
  settings.value = normalized
  saved.value = { ...normalized, accessKeySecret: '' }
}
function settleToggles(value) { if (!value) settings.value.otpEnabled = false }
function normalize(data) {
  return { ...data, billingMode: data.billingMode ?? data.billing_mode, unitPriceCents: data.unitPriceCents ?? data.unit_price_cents,
    balanceCents: data.balanceCents ?? data.balance_cents, reservedCents: data.reservedCents ?? data.reserved_cents }
}
async function save() {
  if (saving.value || readOnly.value) return
  const service = kind.value
  saving.value = true
  try {
    const s = settings.value
    const payload = service === 'sms' ? {
      provider: s.provider, enabled: s.enabled, otpEnabled: s.otpEnabled, endpoint: s.endpoint,
      accessKeyId: s.accessKeyId, accessKeySecret: s.accessKeySecret, signName: s.signName,
      verificationTemplateCode: s.verificationTemplateCode, playerFinishTemplateCode: s.playerFinishTemplateCode,
      csMessageTemplateCode: s.csMessageTemplateCode, teammateInvitedTemplateCode: s.teammateInvitedTemplateCode
    } : { provider: s.provider, enabled: s.enabled }
    const { data } = await saveAuthoritySettings(service, payload)
    if (service === kind.value) resetSettings(data)
    ElMessage.success('服务设置已保存')
  } catch (error) { ElMessage.error(error?.message || '保存服务设置失败') }
  finally { saving.value = false }
}
async function saveLimits() {
  if (limitSaving.value || readOnly.value) return
  limitSaving.value = true
  try {
    const { data } = await saveIdentityLimits({ ...limitSettings })
    Object.assign(limitSettings, data)
    ElMessage.success('实名失败次数限制已保存')
  } catch (error) { ElMessage.error(error?.message || '保存实名失败次数限制失败') }
  finally { limitSaving.value = false }
}
async function buy(item) {
  if (buying.value || readOnly.value) return
  const ua = navigator.userAgent
  const mobileWechat = /MicroMessenger/i.test(ua) && !/WindowsWechat|MacWechat|WeChatWin|WeChatMac|Windows NT|X11/i.test(ua) && /Android|iPhone|iPad|iPod|Mobile|HarmonyOS|OpenHarmony/i.test(ua)
  const popup = mobileWechat ? null : window.open('about:blank', '_blank')
  if (popup) popup.opener = null
  buying.value = item.id
  try {
    const result = await (identity.value ? checkoutIdentityPackage(item.id) : checkoutSmsPackage(item.id))
    const url = result?.data?.checkoutUrl
    if (!url) throw new Error('授权中心未返回套餐支付地址')
    if (popup) popup.location.replace(url)
    else window.location.assign(url)
  } catch (error) {
    popup?.close()
    ElMessage.error(error?.message || '套餐购买暂不可用')
  } finally { buying.value = '' }
}
watch(kind, () => { settings.value = null; account.value = null; load() }, { immediate: true })
</script>

<style scoped>
.sms-account-page { display: grid; gap: 16px; }
.page-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.page-head h2 { margin: 0 0 6px; font-size: 22px; }
.page-head p { margin: 0; color: #7b8798; font-size: 13px; }
.account-card { border-radius: 14px; }
.settings-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(200px, 100%), 1fr)); gap: 12px 18px; }
.aliyun-grid { margin-top: 6px; padding-top: 14px; border-top: 1px solid var(--el-border-color-lighter); }
.usage-line { display: flex; flex-wrap: wrap; gap: 8px 20px; padding: 12px 0; color: #657184; font-size: 13px; }
.account-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.account-grid div { display: grid; gap: 6px; padding: 14px; border-radius: 12px; background: #f7f9fc; }
.account-grid span { color: #7b8798; font-size: 12px; }
.account-grid strong { color: #1f2d3d; font-size: 22px; font-variant-numeric: tabular-nums; }
.recharge-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; }
.recharge-item { display: grid; gap: 5px; padding: 16px; border: 1px solid var(--el-border-color-light); border-radius: 12px; background: var(--el-fill-color-blank); color: var(--el-text-color-primary); text-align: left; cursor: pointer; }
.recharge-item:hover { border-color: var(--el-color-primary); }
.recharge-item:disabled { cursor: wait; opacity: .65; }
.recharge-item strong { font-size: 24px; font-variant-numeric: tabular-nums; }
.recharge-item span, .recharge-item small { color: var(--el-text-color-secondary); font-size: 12px; }
.card-head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.card-head span { color: #8a96a5; font-size: 12px; }
.template-list { display: flex; flex-wrap: wrap; gap: 10px; }
.template-list .el-tag { height: auto; padding: 8px 12px; }
.template-list small { display: block; margin-top: 2px; opacity: .62; font-size: 10px; }
@media (max-width: 760px) { .account-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } .page-head { flex-direction: column; } }
</style>
