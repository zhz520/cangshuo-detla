<template>
  <el-card v-loading="busy">
    <template #header>
      <div class="toolbar"><strong>通知发送记录</strong><el-button @click="load">刷新</el-button></div>
    </template>
    <el-tabs v-model="active" @tab-change="onTabChange">
      <el-tab-pane v-if="officialEnabled" label="公众号通知" name="wechat"/>
      <el-tab-pane label="短信通知" name="sms"/>
    </el-tabs>
    <el-alert
      :title="active==='wechat' ? '接收人须先关注公众号并在对应客户端或工作人员后台完成身份绑定；已知失败最多重试5次，投递结果未知的记录需人工确认后再重试。' : '短信按角色和事件独立模板发送；已知失败自动重试，网络中断导致结果未知的记录需人工确认后再重试。'"
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
    <el-table :data="pagedRows" max-height="620">
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
        <template #default="{row}"><el-button v-if="['RETRY','FAILED','DELIVERY_UNKNOWN'].includes(row.status)" link @click="confirmRetry(row)">{{ row.status==='DELIVERY_UNKNOWN' ? '确认重发' : '重试' }}</el-button></template>
      </el-table-column>
    </el-table>
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
import { ElMessageBox } from 'element-plus'
import request from '@/utils/request'
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
  try{rows.value=(await request.get(active.value==='wechat'?'/admin/wechat/notifications':'/admin/sms/notifications')).data||[]}
  finally{busy.value=false}
}
async function retry(id){await request.post(`/admin/${active.value==='wechat'?'wechat':'sms'}/notifications/${id}/retry`);await load()}
async function confirmRetry(row){
  if(row.status==='DELIVERY_UNKNOWN'){
    try{await ElMessageBox.confirm('该记录投递结果未知，渠道可能已经送达。再次发送可能造成重复通知，确认继续？','确认重发',{type:'warning',confirmButtonText:'确认重发',cancelButtonText:'取消'})}catch(_){return}
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
.notice-summary{display:flex;flex-wrap:wrap;gap:16px;margin:4px 0 14px;color:#777;font-size:13px}
.notice-summary .ok{color:#347a5b}.notice-summary .warn{color:#996b30}.notice-summary .bad{color:#c94f59}
.notice-filter{margin-bottom:8px}.notice-filter .el-form-item{margin-bottom:8px}.notice-filter .el-input{width:190px}.notice-filter .el-select{width:130px}
.notice-pagination{display:flex;justify-content:flex-end;margin-top:14px}
.notice-expand{display:flex;flex-wrap:wrap;gap:12px 24px;padding:4px 18px;color:#666;font-size:12px;line-height:1.7}
.notice-expand b{color:#333;font-weight:600}
</style>
