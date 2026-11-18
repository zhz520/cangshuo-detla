<template>
  <view v-if="authorized" class="privacy-center">
    <view class="card">
      <text class="heading">隐私与账号</text>
      <text class="tip">注销前需处理完订单、余额、提现和售后。审核通过后清除个人资料并退出登录，保留必要的交易审计记录。</text>
      <textarea v-model="note" :disabled="busy || pending" maxlength="500" placeholder="申请说明（选填）" />
      <button :disabled="busy || pending || loading || !!failure" @click="submit('ERASE_IDENTITY')">申请删除实名资料</button>
      <button class="danger" :disabled="busy || pending || loading || !!failure" @click="submit('CLOSE_ACCOUNT')">申请注销账号</button>
    </view>
    <view class="card">
      <text class="heading">申请记录</text>
      <PageState v-if="loading || failure || !rows.length" :loading="loading" :error="failure" :empty="!rows.length" empty-text="暂无申请" @retry="load" />
      <view v-for="row in rows" v-else :key="row.id" class="record">
        <text>{{ row.kind === 'CLOSE_ACCOUNT' ? '注销账号' : '删除实名资料' }} · {{ statuses[row.status] || row.status }}</text>
        <text v-if="row.note" class="tip">申请：{{ row.note }}</text>
        <text v-if="row.review_note" class="tip">审核：{{ row.review_note }}</text>
      </view>
    </view>
  </view>
</template>

<script setup>
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import PageState from '@/components/PageState.vue'
import { get, post } from '@/api/request'
import { getUserToken } from '@/utils/auth'
const rows = ref([]), note = ref(''), busy = ref(false), authorized = ref(false), failure = ref(''), loading = ref(false)
const statuses = { PENDING:'待处理', APPROVED:'已批准', REJECTED:'已驳回' }
const pending = computed(() => rows.value.some(row => row.status === 'PENDING'))
async function load() {
  if (loading.value) return
  if (!getUserToken()) {
    authorized.value = false
    uni.redirectTo({ url:'/pages/login/index?redirect=' + encodeURIComponent('/pages/mine/privacy-center') })
    return
  }
  authorized.value = true
  loading.value = true
  failure.value = ''
  try { rows.value = (await get('/user/privacy/requests', {}, { role:'user', silent:true, loading:false })).data || [] }
  catch (_) { failure.value = '申请记录加载失败' }
  finally { loading.value = false }
}
async function submit(kind) {
  if (busy.value || pending.value || loading.value || failure.value) return
  busy.value = true
  try {
    const result = await uni.showModal({
      title: kind === 'CLOSE_ACCOUNT' ? '申请注销账号' : '删除实名资料',
      content: '确认提交申请？平台将核对身份及未完成业务后处理。'
    })
    if (!result.confirm) return
    await post('/user/privacy/requests', { kind, note:note.value.trim() }, { role:'user', loading:false })
    note.value = ''
    uni.showToast({ title:'申请已提交', icon:'none' })
    await load()
  } catch (error) {
    if (Number(error?.code) === 409) await load()
  } finally { busy.value = false }
}
onShow(load)
</script>

<style scoped>
.privacy-center{padding:24rpx;background:var(--store-bg,var(--store-soft));min-height:100vh}
.card{padding:28rpx;margin-bottom:24rpx;background:var(--store-surface,var(--store-surface));border-radius:16rpx}
.heading{font-size:32rpx;font-weight:700;display:block}
.tip{display:block;color:var(--store-regular,#777);font-size:25rpx;line-height:1.8;margin:16rpx 0}
.record{border-bottom:1rpx solid var(--store-line,var(--store-line));padding:20rpx 0}
textarea{width:100%;background:var(--store-soft,var(--store-soft));padding:20rpx;box-sizing:border-box;font-size:26rpx}
button{margin-top:24rpx;font-size:28rpx}.danger{color:#cb4141}
</style>
