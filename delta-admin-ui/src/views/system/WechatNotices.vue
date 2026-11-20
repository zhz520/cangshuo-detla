<template>
  <el-card>
    <template #header>
      <PageHeader title="通知发送记录" description="查看公众号和短信通知的发送、重试与失败结果。">
        <template #actions><el-button @click="load">刷新</el-button></template>
      </PageHeader>
    </template>
    <el-tabs v-model="active" @tab-change="onTabChange">
      <el-tab-pane v-if="officialEnabled" label="公众号通知" name="wechat"/>
      <el-tab-pane label="短信通知" name="sms"/>
    </el-tabs>
    <el-alert
      :title="active==='wechat' ? '接收人须先关注公众号并绑定账号。' : '短信受理后等待运营商回执，最终状态以授权中心为准。'"
      :closable="false"/>
    <el-alert v-if="platformFeatureError" :title="platformFeatureError" type="warning" :closable="false" />
    <div class="notice-summary">
      <span>共 {{ filteredRows.length }} 条</span>
      <span class="ok">已发送 {{ counts.SENT }}</span>
      <span class="warn">待处理 {{ counts.PENDING + counts.RETRY + counts.SENDING + counts.DELIVERY_UNKNOWN }}</span>
      <span class="bad">失败 {{ counts.FAILED + counts.EXPIRED }}</span>
    </div>
    <el-form :inline="true" class="notice-filter">
      <el-form-item><el-input v-model="filterEvent" clearable placeholder="通知节点" /></el-form-item>
      <el-form-item>
        <el-select v-model="filterStatus" clearable placeholder="状态">
          <el-option v-for="(label, key) in labels" :key="key" :label="label" :value="key" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-select v-model="filterRole" clearable placeholder="角色">
          <el-option label="用户" value="USER" /><el-option label="打手" value="PLAYER" /><el-option label="管理员" value="ADMIN" /><el-option label="客服" value="CS" />
        </el-select>
      </el-form-item>
      <el-form-item><el-button @click="resetFilter">重置</el-button></el-form-item>
    </el-form>
    <DataTable :loading="busy" :data="pagedRows" height="620">
      <el-table-column type="expand" width="44">
        <template #default="{ row }">
          <div class="notice-expand">
            <span><b>接收人：</b>{{ roleLabel(row.user_type) }} / {{ row.user_id }}</span>
            <span v-if="active==='sms'"><b>手机号：</b>{{ row.phone || '-' }}</span>
            <span><b>创建：</b>{{ row.created_at || '-' }}</span>
            <span v-if="active==='sms'"><b>发送：</b>{{ row.sent_at || '-' }}</span>
            <span><b>错误：</b>{{ row.last_error || '无' }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="event_type" label="通知节点" min-width="170"/>
      <el-table-column label="接收人" min-width="130">
        <template #default="{ row }">{{ roleLabel(row.user_type) }} / {{ row.user_id }}</template>
      </el-table-column>
      <el-table-column label="状态" min-width="110">
        <template #default="{row}"><el-tag :type="statusType(row.status)" size="small">{{labels[row.status]||row.status}}</el-tag></template>
      </el-table-column>
      <el-table-column prop="attempts" label="尝试次数" width="90"/>
      <el-table-column prop="created_at" label="创建时间" min-width="180"/>
      <el-table-column label="操作" width="96" fixed="right">
        <template #default="{row}"><el-button v-if="['RETRY','FAILED'].includes(row.status) || (active==='wechat' && row.status==='DELIVERY_UNKNOWN')" link @click="confirmRetry(row)">{{ row.status==='DELIVERY_UNKNOWN' ? '确认重发' : '重试' }}</el-button></template>
      </el-table-column>
    </DataTable>
    <el-pagination
      v-if="filteredRows.length > pageSize"
      v-model:current-page="page"
      v-model:page-size="pageSize"
      :page-sizes="[20, 50, 100]"
      :total="filteredRows.length"
      layout="total, sizes, prev, pager, next"
      class="notice-pagination"
    />
  </el-card>
</template>

<script setup>
import {computed,ref,onMounted,watch} from 'vue'
import { confirmAction } from '@/utils/feedback'
import { getWechatNotifications, getSmsNotifications, retryNotification } from '@/api/system'
import PageHeader from '@/components/PageHeader.vue'
import DataTable from '@/components/DataTable.vue'
import {enabledPlatformFeatures,loadPlatformFeatureVisibility,platformFeatureError} from '@/utils/platform-feature-visibility'
const active=ref('wechat'),rows=ref([]),busy=ref(false)
const filterEvent=ref(''),filterStatus=ref(''),filterRole=ref(''),page=ref(1),pageSize=ref(20)
const officialEnabled=computed(()=>enabledPlatformFeatures.value?.has('official_wechat_notifications')===true)
const labels={PENDING:'等待发送',SENDING:'发送中',SENT:'已发送',RETRY:'等待重试',FAILED:'发送失败',DELIVERY_UNKNOWN:'投递结果未知',EXPIRED:'已过期'}
const filteredRows=computed(()=>rows.value.filter(row=>{
  if(filterEvent.value && !String(row.event_type||'').toLowerCase().includes(filterEvent.value.toLowerCase()))return false
  if(filterStatus.value && row.status!==filterStatus.value)return false
  if(filterRole.value && row.user_type!==filterRole.value)return false
  return true
}))
const pagedRows=computed(()=>filteredRows.value.slice((page.value-1)*pageSize.value,page.value*pageSize.value))
const counts=computed(()=>filteredRows.value.reduce((acc,row)=>{acc[row.status]=(acc[row.status]||0)+1;return acc},{SENT:0,PENDING:0,RETRY:0,SENDING:0,DELIVERY_UNKNOWN:0,FAILED:0,EXPIRED:0}))
function resetFilter(){filterEvent.value='';filterStatus.value='';filterRole.value='';page.value=1}
function onTabChange(){page.value=1;load()}
watch([filterEvent,filterStatus,filterRole,pageSize],()=>{page.value=1})
function statusType(status){return status==='SENT'?'success':status==='FAILED'||status==='EXPIRED'?'danger':['RETRY','DELIVERY_UNKNOWN'].includes(status)?'warning':'info'}
function roleLabel(role){return ({USER:'用户',PLAYER:'打手',ADMIN:'管理员',CS:'客服'})[role]||role||'-'}
async function load(){
  busy.value=true
  const channel=active.value
  try {
    const {data}=await (channel==='wechat'?getWechatNotifications():getSmsNotifications())
    if (channel===active.value) rows.value=Array.isArray(data)?data:[]
  } catch {
    if (channel===active.value) rows.value=[]
  } finally { if(channel===active.value) busy.value=false }
}
async function retry(id){await retryNotification(active.value==='wechat'?'wechat':'sms', id);await load()}
async function confirmRetry(row){
  if(row.status==='DELIVERY_UNKNOWN'){
    if (!(await confirmAction('该记录投递结果未知，渠道可能已经送达。再次发送可能造成重复通知，确认继续？', '确认重发', {confirmButtonText:'确认重发'}))) return
  }
  await retry(row.id)
}
onMounted(async()=>{
  await loadPlatformFeatureVisibility()
  if(!officialEnabled.value)active.value='sms'
  load()
})
</script>

<style scoped>
.toolbar{display:flex;gap:16px;align-items:center;justify-content:space-between}.el-alert{margin-bottom:20px}
.notice-summary{display:flex;flex-wrap:wrap;gap:16px;margin:4px 0 14px;color:var(--el-text-color-secondary);font-size:13px}
.notice-summary .ok{color:var(--el-color-success)}.notice-summary .warn{color:var(--el-color-warning)}.notice-summary .bad{color:var(--el-color-danger)}
.notice-filter{margin-bottom:8px}.notice-filter .el-form-item{margin-bottom:8px}.notice-filter .el-input{width:190px}.notice-filter .el-select{width:130px}
.notice-pagination{display:flex;justify-content:flex-end;margin-top:14px}
.notice-expand{display:flex;flex-wrap:wrap;gap:12px 24px;padding:4px 18px;color:var(--el-text-color-secondary);font-size:12px;line-height:1.7}
.notice-expand b{color:var(--el-text-color-primary);font-weight:600}
</style>
