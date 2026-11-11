<template>
  <div class="page-container">
    <el-card>
      <template #header><div class="card-header"><span>系统配置</span><el-button type="primary" :loading="submitting" @click="handleSave">保存配置</el-button></div></template>
      <div class="config-layout">
        <nav v-if="groupNames.length > 1 && !isCompact" class="config-group-nav" aria-label="系统配置分组">
          <button
            v-for="g in groupNames"
            :key="g"
            type="button"
            class="config-group-item"
            :class="{ active: g === activeGroup }"
            :aria-pressed="g === activeGroup"
            @click="activeGroup = g"
          >
            <span class="config-group-name">{{ g }}</span>
            <span class="config-group-count">{{ groupCounts.get(g) || 0 }}</span>
          </button>
        </nav>
        <div class="config-main">
          <div v-if="groupNames.length > 1 && isCompact" class="config-group-select">
            <span class="config-group-select-label">配置分组</span>
            <el-select v-model="activeGroup" style="width:100%">
              <el-option v-for="g in groupNames" :key="g" :label="`${g}（${groupCounts.get(g) || 0}）`" :value="g" />
            </el-select>
          </div>
          <el-alert v-if="activeGroup === '微信支付配置'" title="公众号支付配置提醒" type="info" :closable="false" show-icon
            description="每个支付开关独立生效，支付页面不再根据浏览器类型自动隐藏已启用方式。微信支付公钥和公钥 ID 必须从当前微信支付商户号的“账户中心 > API安全 > 微信支付公钥”成对下载；两者不匹配会产生“WechatPay signature is incorrect”。公众平台仍需配置网页授权域名，商户平台需配置 JSAPI 支付授权目录。" />
          <el-alert v-if="activeGroup === '微信支付配置'" title="客户端业务支付与功能购买分开" type="warning" :closable="false" show-icon
            description="本页只配置客户自己的订单、充值和押金收款。客户端功能商城的购买款由授权中心统一收取，使用授权中心的微信商户和回调，不读取本页配置。" />
          <el-alert v-if="activeGroup === '公众号通知'" title="四个角色分别使用独立模板" type="info" :closable="false" show-icon
            description="客户、打手、管理员、客服分别按五个事件直接填写模板 ID 和字段，系统自动生成配置。模板变量、系统数据源和字段长度限制均有提示；旧版通用模板仅作为兼容回退。" />
          <el-alert v-if="activeGroup === '短信配置'" title="业务短信也按角色独立模板" type="info" :closable="false" show-icon
            description="客户、打手、管理员、客服分别填写阿里云模板 Code 和参数映射；事件分组与公众号通知一致，页面会自动生成底层配置。"/>
          <el-form v-loading="loading" :label-position="isCompact ? 'top' : 'right'" :label-width="isCompact ? 'auto' : '200px'" class="config-form">
            <el-form-item v-for="item in filteredList" :key="item.configKey" :label="item.configName || item.configKey">
              <NotificationTemplateEditor v-if="isNotificationTemplateKey(item.configKey)" v-model="item.configValue" :config-key="item.configKey" @validity="value => setNotificationValidity(item.configKey, value)" />
              <el-input v-else-if="item.valueType === 'file'" v-model="item.configValue" readonly>
                <template #append>
                  <el-upload
                    :show-file-list="false"
                    :auto-upload="true"
                    accept=".pem,.key,.txt"
                    :http-request="(options) => handleCertificateUpload(item, options)"
                  >
                    <el-button>上传文件</el-button>
                  </el-upload>
                </template>
              </el-input>
              <el-select v-else-if="item.configKey === 'customer_service.mode'" v-model="item.configValue" :style="isCompact ? {width:'100%'} : {width:'240px'}">
                <el-option label="系统内置客服" value="internal" />
                <el-option label="第三方企业微信链接" value="external" />
              </el-select>
              <el-select v-else-if="item.configKey === 'player.deposit_mode'" v-model="item.configValue" :style="isCompact ? {width:'100%'} : {width:'280px'}">
                <el-option label="必须交押金（原配置）" value="REQUIRED" />
                <el-option label="全部不需要押金" value="NONE" />
                <el-option label="入驻免押金，提现前补交" value="BEFORE_WITHDRAW" />
              </el-select>
              <el-select v-else-if="item.configKey === 'risk.media.review_mode'" v-model="item.configValue" :style="isCompact ? {width:'100%'} : {width:'280px'}">
                <el-option label="自动检测，失败时转人工" value="AUTO" />
                <el-option label="直接人工审核" value="MANUAL" />
              </el-select>
              <el-input v-else-if="item.valueType === 'text' || !item.valueType" v-model="item.configValue" :type="isSecretKey(item.configKey)?'password':'text'" :show-password="isSecretKey(item.configKey)" autocomplete="new-password" />
              <el-input-number v-else-if="item.valueType === 'number'" v-model.number="item.configValue" :controls="false" style="width:200px" />
              <el-switch v-else-if="item.valueType === 'boolean'" v-model="item.configValue" active-value="true" inactive-value="false" />
              <el-input v-else-if="item.valueType === 'textarea' || item.valueType === 'json'" v-model="item.configValue" type="textarea" :rows="item.valueType==='json'?6:3" />
              <ImageUpload v-else-if="item.valueType === 'image'" v-model="item.configValue" />
              <div class="config-desc" v-if="item.remark && !isNotificationTemplateKey(item.configKey)">{{ item.remark }}</div>
              <NotificationConfigGuide v-if="isNotificationTemplateKey(item.configKey)" :config-key="item.configKey" />
            </el-form-item>
            <el-empty v-if="!loading && filteredList.length === 0" description="暂无配置项" />
          </el-form>
        </div>
      </div>
    </el-card>
  </div>
</template>
<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { getSysConfigList, batchUpdateSysConfig, uploadWxPayCertificate } from '@/api/system'
import { ElMessage } from 'element-plus'
import ImageUpload from '@/components/ImageUpload.vue'
import NotificationConfigGuide from '@/components/NotificationConfigGuide.vue'
import NotificationTemplateEditor from '@/components/NotificationTemplateEditor.vue'
import {enabledPlatformFeatures,loadPlatformFeatureVisibility} from '@/utils/platform-feature-visibility'
import {configGroupCounts,editableSystemConfigs,orderedConfigGroups} from '@/utils/system-config'
import {useResponsive} from '@/utils/responsive'
const {isCompact}=useResponsive()
const loading = ref(false), submitting = ref(false)
function isSecretKey(key){return ['wx.pay.api-v3-key','wx.pay.official-appsecret','sms.aliyun.access_key_secret','wechat.official.app_secret','risk.media.token','email.notifications.password'].includes(key)}
const configList = ref([])
const activeGroup = ref('')
const notificationValidity = ref({})
function setNotificationValidity(key, valid) { notificationValidity.value[key] = valid }
const editableConfigList = computed(() => editableSystemConfigs(configList.value, enabledPlatformFeatures.value))
const groupNames = computed(() => orderedConfigGroups(editableConfigList.value))
const groupCounts = computed(() => configGroupCounts(editableConfigList.value))
const filteredList = computed(() => {
  // 只显示有 configName 的配置项
  const named = editableConfigList.value
  if (groupNames.value.length <= 1) return named
  const g = groupNames.value.includes(activeGroup.value) ? activeGroup.value : groupNames.value[0]
  return named.filter(c => (c.configGroup || '默认') === g)
})
watch(groupNames, names => {
  if (!names.length) {
    activeGroup.value = ''
    return
  }
  if (!names.includes(activeGroup.value)) activeGroup.value = names[0]
}, { immediate: true })
async function fetchData() {
  loading.value = true
  try {
    const res = await getSysConfigList()
    configList.value = res.data || []
  } finally { loading.value = false }
}
async function handleSave() {
  if (Object.values(notificationValidity.value).some(valid => valid === false)) {
    ElMessage.warning('请先修正通知模板中的错误')
    return
  }
  submitting.value = true
  try { await batchUpdateSysConfig(editableConfigList.value); ElMessage.success('保存成功') } finally { submitting.value = false }
}
async function handleCertificateUpload(item, options) {
  try {
    const res = await uploadWxPayCertificate(item.configKey, options.file)
    item.configValue = res.data || ''
    options.onSuccess?.(res)
    ElMessage.success('证书上传成功，请点击保存配置')
  } catch (e) {
    // request 已统一提示错误
    options.onError?.(e)
  }
}
function isNotificationTemplateKey(key) {
  return /^(wechat\.official\.templates|sms\.notification\.templates)\.(user|player|admin|cs)$/.test(String(key || ''))
}
onMounted(()=>{loadPlatformFeatureVisibility();fetchData()})
</script>
<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
.config-layout { display: flex; align-items: flex-start; gap: 24px; }
.config-group-nav {
  position: sticky;
  top: 12px;
  flex: 0 0 176px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: calc(100vh - 180px);
  overflow: auto;
  padding: 2px 4px 2px 0;
}
.config-group-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: #fff;
  color: #374151;
  font: inherit;
  line-height: 1.3;
  text-align: left;
  cursor: pointer;
}
.config-group-item:hover { border-color: #c7cbd1; background: #f8fafc; }
.config-group-item:focus-visible { outline: 2px solid #1f2937; outline-offset: 2px; }
.config-group-item.active { border-color: #1f2937; background: #1f2937; color: #fff; }
.config-group-name { min-width: 0; overflow-wrap: anywhere; }
.config-group-count {
  min-width: 22px;
  padding: 1px 6px;
  border-radius: 999px;
  background: #f1f5f9;
  color: #64748b;
  font-size: 12px;
  text-align: center;
}
.config-group-item.active .config-group-count { background: rgba(255,255,255,.16); color: #fff; }
.config-main { flex: 1; min-width: 0; }
.config-group-select { margin-bottom: 16px; }
.config-group-select-label { display: block; margin-bottom: 6px; color: #6b7280; font-size: 13px; }
.config-form { max-width: 980px; }
.config-desc { color: #999; font-size: 12px; line-height: 1.4; margin-top: 4px; }
@media (max-width: 1024px) {
  .config-layout { display: block; }
  .config-group-nav { display: none; }
}
</style>
