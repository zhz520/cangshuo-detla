<template>
  <view class="edit-page">
    <PageState :loading="loading" :error="loadError" @retry="load" />
    <template v-if="!loading && !loadError">
    <view class="form-card">
      <view class="form-item">
        <text class="label">账户类型</text>
        <view class="type-selector">
          <view v-for="t in types" :key="t.value" class="type-opt" :class="{active: form.type===t.value}" @click="selectType(t.value)">
            <text>{{ t.icon }} {{ t.label }}</text>
          </view>
        </view>
      </view>
      <view class="form-item">
        <text class="label">账号</text>
        <input v-model="form.accountNo" :disabled="saving || saved" maxlength="256" placeholder="请输入账号" class="input" />
      </view>
      <view class="form-item">
        <text class="label">真实姓名</text>
        <input v-model="form.accountName" :disabled="saving || saved" maxlength="128" placeholder="请输入真实姓名" class="input" />
      </view>
      <view v-if="form.type==='ALIPAY'||form.type==='WECHAT'" class="form-item form-item-col">
        <text class="label">收款码图片</text>
        <view class="qrcode-upload">
          <view v-if="form.qrcodeUrl" class="qrcode-preview" @click="chooseQrcode">
            <AppImage :src="form.qrcodeUrl" mode="aspectFit" class="qrcode-img" />
            <text class="qrcode-tip">{{ uploading ? '上传中…' : '点击更换' }}</text>
          </view>
          <view v-else class="qrcode-placeholder" @click="chooseQrcode">
            <text class="placeholder-icon">📷</text>
            <text class="placeholder-text">{{ uploading ? '上传中…' : '上传收款码' }}</text>
          </view>
        </view>
      </view>
      <view class="form-item">
        <text class="label">设为默认</text>
        <switch :disabled="saving || saved" :checked="form.isDefault===1" @change="form.isDefault=$event.detail.value?1:0" />
      </view>
    </view>
    <view class="btn-area">
      <text v-if="submitError" class="submit-error" role="alert">{{ submitError }}</text>
      <button class="submit-btn" :disabled="saving || saved || uploading" :loading="saving" @click="doSave">{{ saved ? '已保存' : saving ? '保存中…' : uploading ? '收款码上传中…' : '保存' }}</button>
    </view>
    </template>
  </view>
</template>
<script setup>
import AppImage from '@/components/AppImage.vue'
import { reactive, ref } from 'vue'
import { onLoad, onUnload } from '@dcloudio/uni-app'
import { addAccount, updateAccount, getAccountList } from '@/api/player'
import { upload } from '@/api/file'
import PageState from '@/components/PageState.vue'

const types = [
  { value: 'ALIPAY', label: '支付宝', icon: '💙' },
  { value: 'WECHAT', label: '微信', icon: '💚' },
  { value: 'BANK', label: '银行卡', icon: '🏦' }
]
const isEdit = ref(false)
const form = reactive({ id: '', type: 'ALIPAY', accountNo: '', accountName: '', qrcodeUrl: '', isDefault: 0 })
const loading = ref(false), loadError = ref(''), saving = ref(false), saved = ref(false), uploading = ref(false), submitError = ref('')
let disposed = false, active, version = 0, accountId = '', backTimer

onLoad((opts) => {
  if (opts?.id) {
    isEdit.value = true
    accountId = opts.id
    load()
  }
})
onUnload(() => { disposed = true; version++; clearTimeout(backTimer) })
function load() {
  if (disposed || !isEdit.value) return Promise.resolve()
  if (active) return active
  loading.value = true
  loadError.value = ''
  const current = ++version
  active = (async () => {
    try {
      if (!/^\d+$/.test(String(accountId))) throw new Error('账户编号无效，请返回列表重新进入')
      const { data } = await getAccountList({ loading: false, silent: true })
      if (!Array.isArray(data)) throw new Error('账户数据异常，请重试')
      const account = data.find(row => String(row.id) === String(accountId))
      if (!account) throw new Error('该账户不存在，请返回列表查看')
      if (!disposed && current === version) Object.assign(form, { id: account.id, type: account.type, accountNo: account.accountNo || '', accountName: account.accountName || '', qrcodeUrl: account.qrcodeUrl || '', isDefault: Number(account.isDefault) === 1 ? 1 : 0 })
    } catch (e) {
      if (!disposed && current === version) loadError.value = e?.msg || e?.message || '账户加载失败，请重试'
    } finally {
      if (!disposed && current === version) { loading.value = false; active = null }
    }
  })()
  return active
}
function selectType(type) { if (!saving.value && !saved.value && !uploading.value) form.type = type }

function chooseQrcode() {
  if (disposed || uploading.value || saving.value || saved.value) return
  uni.chooseImage({
    count: 1, sizeType: ['compressed'], sourceType: ['album', 'camera'],
    success: result => uploadQrcode(result.tempFilePaths?.[0]),
    fail: e => { if (!disposed && !/cancel/i.test(e?.errMsg || '')) submitError.value = '图片选择失败，请重试' }
  })
}
async function uploadQrcode(path) {
  if (!path) return
  if (disposed || uploading.value || saving.value || saved.value) return
  uploading.value = true
  submitError.value = ''
  try {
    const url = await upload(path)
    if (!disposed) form.qrcodeUrl = url
  } catch (e) {
    if (!disposed && !/cancel/i.test(e?.errMsg || e?.message || '')) submitError.value = e?.msg || e?.message || '收款码上传失败，请点击图片重试'
  } finally {
    uploading.value = false
  }
}

async function doSave() {
  if (disposed || loading.value || loadError.value || saving.value || saved.value || uploading.value) return
  const accountNo = form.accountNo.trim(), accountName = form.accountName.trim()
  if (!accountNo) return uni.showToast({ title: '请输入账号', icon: 'none' })
  if (!accountName) return uni.showToast({ title: '请输入姓名', icon: 'none' })
  if ((form.type === 'ALIPAY' || form.type === 'WECHAT') && !form.qrcodeUrl) return uni.showToast({ title: '请上传收款码图片', icon: 'none' })
  const data = { type: form.type, accountNo, accountName, qrcodeUrl: form.type === 'BANK' ? '' : form.qrcodeUrl, isDefault: form.isDefault }
  if (isEdit.value) data.id = form.id
  saving.value = true
  submitError.value = ''
  try {
    if (isEdit.value) { await updateAccount(data) } else { await addAccount(data) }
    if (disposed) return
    saved.value = true
    uni.showToast({ title: '保存成功' })
    backTimer = setTimeout(() => { if (!disposed) uni.navigateBack() }, 1000)
  } catch (e) {
    if (!disposed) submitError.value = e?.msg || e?.message || '保存失败，请重试'
  } finally {
    saving.value = false
  }
}
</script>
<style lang="scss" scoped>
.edit-page { background: #f7f8fa; min-height: var(--app-page-height, 100vh); box-sizing: border-box; padding: 1rpx 0 var(--app-safe-bottom, env(safe-area-inset-bottom)); }
.form-card { margin: 24rpx; background: #fff; border: 1rpx solid #edf0f2; border-radius: 20rpx; padding: 0 24rpx; }
.form-item { display: flex; align-items: center; padding: 24rpx 0; border-bottom: 1rpx solid rgba(0,0,0,0.04);
  .label { font-size: 28rpx; color: rgba(0,0,0,0.85); width: 160rpx; flex-shrink: 0; } .input { flex: 1; font-size: 28rpx; }
}
.form-item-col { flex-direction: column; align-items: flex-start; }
.qrcode-upload { margin-top: 12rpx; }
.qrcode-preview, .qrcode-placeholder { width: 200rpx; height: 200rpx; border: 2rpx dashed rgba(0,0,0,0.15); border-radius: 12rpx; display: flex; flex-direction: column; align-items: center; justify-content: center; background: #f7f7f7; }
.qrcode-img { width: 180rpx; height: 180rpx; }
.qrcode-tip { font-size: 22rpx; color: #777777; margin-top: 8rpx; }
.placeholder-icon { font-size: 48rpx; margin-bottom: 8rpx; }
.placeholder-text { font-size: 24rpx; color: #777777; }
.type-selector { display: flex; flex-wrap: wrap; gap: 12rpx; flex: 1; }
.type-opt { padding: 12rpx 20rpx; background: rgba(0,0,0,0.05); border-radius: 8rpx; font-size: 24rpx; &.active { background: #171717; color: #ffffff; font-weight:bold; } }
.btn-area { padding: 40rpx 24rpx; }
.submit-error { display: block; color: #b84b55; font-size: 25rpx; line-height: 1.6; margin-bottom: 20rpx; }
.submit-btn { background: #111111; color: #fff; font-weight:bold; text-align: center; line-height: 1.6; padding: 24rpx; border-radius: 999rpx; font-size: 30rpx; &::after { border: 0; } &[disabled] { opacity: .5; } }
</style>
