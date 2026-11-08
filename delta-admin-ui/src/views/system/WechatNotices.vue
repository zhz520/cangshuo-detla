<template>
  <el-card v-loading="busy">
    <template #header>
      <div class="toolbar"><strong>通知发送记录</strong><el-button @click="load">刷新</el-button></div>
    </template>
    <el-tabs v-model="active" @tab-change="load">
      <el-tab-pane v-if="officialEnabled" label="公众号通知" name="wechat"/>
      <el-tab-pane label="短信通知" name="sms"/>
    </el-tabs>
    <el-alert
      :title="active==='wechat' ? '接收人须先关注公众号并在对应客户端或工作人员后台完成身份绑定；已知失败最多重试5次，投递结果未知的记录需人工确认后再重试。' : '短信按角色和事件独立模板发送；已知失败自动重试，网络中断导致结果未知的记录需人工确认后再重试。'"
      :closable="false"/>
    <el-alert v-if="platformFeatureError" :title="platformFeatureError" type="warning" :closable="false" />
    <el-table :data="rows">
      <el-table-column prop="event_type" label="通知节点" min-width="170"/>
      <el-table-column prop="user_type" label="角色" width="90"/>
      <el-table-column prop="user_id" label="接收人ID" width="110"/>
      <el-table-column v-if="active==='sms'" prop="phone" label="手机号" min-width="140"/>
      <el-table-column label="状态" min-width="110">
        <template #default="{row}"><el-tag :type="statusType(row.status)" size="small">{{labels[row.status]||row.status}}</el-tag></template>
      </el-table-column>
      <el-table-column prop="attempts" label="尝试次数" width="90"/>
      <el-table-column prop="last_error" label="错误代码" min-width="210"/>
      <el-table-column prop="created_at" label="创建时间" min-width="180"/>
      <el-table-column v-if="active==='sms'" prop="sent_at" label="发送时间" min-width="180"/>
      <el-table-column label="操作" width="90">
        <template #default="{row}"><el-button v-if="['RETRY','FAILED','DELIVERY_UNKNOWN'].includes(row.status)" link @click="retry(row.id)">{{ row.status==='DELIVERY_UNKNOWN' ? '确认重发' : '重试' }}</el-button></template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import {computed,ref,onMounted} from 'vue'
import request from '@/utils/request'
import {enabledPlatformFeatures,loadPlatformFeatureVisibility,platformFeatureError} from '@/utils/platform-feature-visibility'
const active=ref('wechat'),rows=ref([]),busy=ref(false)
const officialEnabled=computed(()=>enabledPlatformFeatures.value?.has('official_wechat_notifications')===true)
const labels={PENDING:'等待发送',SENDING:'发送中',SENT:'已发送',RETRY:'等待重试',FAILED:'发送失败',DELIVERY_UNKNOWN:'投递结果未知',EXPIRED:'已过期'}
function statusType(status){return status==='SENT'?'success':status==='FAILED'||status==='EXPIRED'?'danger':['RETRY','DELIVERY_UNKNOWN'].includes(status)?'warning':'info'}
async function load(){
  busy.value=true
  try{rows.value=(await request.get(active.value==='wechat'?'/admin/wechat/notifications':'/admin/sms/notifications')).data||[]}
  finally{busy.value=false}
}
async function retry(id){await request.post(`/admin/${active.value==='wechat'?'wechat':'sms'}/notifications/${id}/retry`);await load()}
onMounted(async()=>{
  await loadPlatformFeatureVisibility()
  if(!officialEnabled.value)active.value='sms'
  load()
})
</script>

<style scoped>
.toolbar{display:flex;gap:16px;align-items:center;justify-content:space-between}.el-alert{margin-bottom:20px}
</style>
