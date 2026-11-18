<template>
  <el-form-item :class="{ 'config-field-wide': wide }" :label="label">
    <NotificationTemplateEditor v-if="template" compact :model-value="item.configValue" :config-key="item.configKey"
      @update:model-value="$emit('change', $event)" @validity="$emit('validity', $event)" />
    <el-input v-else-if="item.valueType === 'file'" :model-value="item.configValue" readonly>
      <template #append>
        <el-upload :show-file-list="false" :disabled="busy" accept=".pem,.key,.txt" :http-request="upload">
          <el-button :disabled="busy">上传文件</el-button>
        </el-upload>
      </template>
    </el-input>
    <el-select v-else-if="choices" :model-value="item.configValue" :disabled="busy" @update:model-value="$emit('change', $event)">
      <el-option v-for="[value, name] in choices" :key="value" :value="value" :label="name" />
    </el-select>
    <el-input v-else-if="secret" :model-value="item.configValue === '********' ? '' : item.configValue" type="password"
      :placeholder="configured ? '已设置，留空不修改' : '未配置'" autocomplete="new-password" spellcheck="false" :disabled="busy"
      @update:model-value="$emit('change', $event || savedValue)" />
    <el-input-number v-else-if="depositAmount || item.valueType === 'number'" :model-value="numberValue" :controls="false" :disabled="busy"
      :min="depositAmount ? 0 : undefined" :max="depositAmount ? 1000000 : undefined" :precision="depositAmount ? 2 : undefined"
      @update:model-value="$emit('change', $event ?? '')" />
    <el-switch v-else-if="item.valueType === 'boolean'" :model-value="item.configValue" active-value="true" inactive-value="false"
      :aria-label="label" :disabled="busy" @update:model-value="$emit('change', $event)" />
    <ImageUpload v-else-if="item.valueType === 'image'" :model-value="item.configValue" :disabled="busy" :logo="item.configKey === 'site_logo'" @update:model-value="$emit('change', $event)" @uploading="$emit('busy', $event)" />
    <el-input v-else-if="item.valueType !== 'json'" :model-value="item.configValue" :type="item.valueType === 'textarea' ? 'textarea' : 'text'"
      :disabled="busy" @update:model-value="$emit('change', $event)" />
    <div v-if="help && !template" class="config-field-help">{{ help }}</div>
  </el-form-item>
</template>

<script setup>
import { computed } from 'vue'
import ImageUpload from '@/components/ImageUpload.vue'
import NotificationTemplateEditor from '@/components/NotificationTemplateEditor.vue'

const props = defineProps({ item: { type: Object, required: true }, savedValue: [String, Number], busy: Boolean, upload: Function })
defineEmits(['change', 'validity', 'busy'])
const secretKeys = new Set(['wx.pay.api-v3-key', 'wx.pay.official-appsecret', 'sms.aliyun.access_key_secret', 'wechat.official.app_secret', 'risk.media.token', 'email.notifications.password'])
const choiceMap = {
  'customer_service.mode': [['internal','系统内置客服'], ['external','第三方企业微信链接']],
  'player.deposit_mode': [['REQUIRED','入驻时缴押金'], ['NONE','无需押金'], ['BEFORE_WITHDRAW','入驻免押金，提现前按等级补交']],
  'risk.media.review_mode': [['AUTO','自动检测，失败转人工'], ['MANUAL','直接人工审核']],
  'auth.primary_method': [['PHONE','手机号优先'], ['WECHAT','微信优先']],
  'identity.prompt_mode': [['OFF','关闭'], ['REMIND','提醒'], ['REQUIRED','指定操作必须实名']],
  'wx.pay.h5-type': [['WAP','移动网页'], ['IOS','iOS'], ['ANDROID','Android']],
  'sms.aliyun.endpoint': [['dysmsapi.aliyuncs.com', 'dysmsapi.aliyuncs.com']],
}
const labelOverrides = {
  'player.deposit_mode': '押金政策',
  'player.deposit_amount': '入驻押金金额（元）',
  'auth.phone.enabled': '微信优先时提供手机号入口',
  'auth.password.enabled': '允许辅助密码登录',
  'wechat.official.app_id': '公众号 AppID',
  'wechat.official.app_secret': '公众号 AppSecret',
  'sms.aliyun.access_key_id': 'AccessKey ID',
  'sms.aliyun.access_key_secret': 'AccessKey Secret',
  'sms.aliyun.endpoint': 'Endpoint',
  'sms.aliyun.sign_name': '短信签名',
}
const hintOverrides = {
  'player.deposit_amount': '仅选择「入驻时缴押金」时生效。',
  'auth.primary_method': '微信未配置时自动使用手机号登录。',
  'auth.phone.enabled': '手机号优先或微信不可用时，始终提供手机号登录。',
  'wechat.official.app_id': '使用支持网页授权的公众号 AppID。',
  'wechat.official.app_secret': '',
  'wx.pay.public-key-id': '',
  'wx.pay.public-key-path': '',
  'auth.age_confirmation.enabled': '确认后记住 30 天，未满 18 岁时阻止进入。',
}
const label = computed(() => labelOverrides[props.item.configKey] || props.item.configName)
const help = computed(() => hintOverrides[props.item.configKey] ?? props.item.remark)
const choices = computed(() => choiceMap[props.item.configKey])
const secret = computed(() => secretKeys.has(props.item.configKey))
const configured = computed(() => props.savedValue === '********')
const template = computed(() => /^(wechat\.official\.templates|sms\.notification\.templates)\.(user|player|admin|cs)$/.test(props.item.configKey))
const wide = computed(() => template.value || ['textarea','image'].includes(props.item.valueType))
const depositAmount = computed(() => props.item.configKey === 'player.deposit_amount')
const numberValue = computed(() => props.item.configValue === '' || props.item.configValue == null ? undefined : Number(props.item.configValue))
</script>

<style scoped>
.el-form-item { min-width:0; margin:0 }
.el-form-item :deep(.el-form-item__label) { height:auto; white-space:normal; line-height:1.4; margin-bottom:6px }
.el-form-item :deep(.el-form-item__content) { align-items:flex-start; flex-direction:column; line-height:1.4 }
.el-form-item :deep(.el-select),.el-form-item :deep(.el-input-number) { width:100% }
.config-field-wide { grid-column:1/-1 }
.config-field-help { color:var(--el-text-color-secondary); font-size:12px; margin-top:5px; line-height:1.55; overflow-wrap:anywhere }
</style>
