<template><el-card v-loading="busy"><template #header><div class="toolbar"><strong>公众号通知记录</strong><el-button @click="load">刷新</el-button></div></template><el-alert title="在系统配置的“公众号通知”中设置AppID、密钥、回调页面和各节点模板映射。接收人须先在微信内绑定账号并关注公众号；失败通知最多重试5次，超过一天的待发消息自动过期。" :closable="false"/><el-table :data="rows"><el-table-column prop="event_type" label="通知节点" min-width="190"/><el-table-column prop="user_type" label="身份"/><el-table-column prop="user_id" label="接收人ID"/><el-table-column label="状态" min-width="130"><template #default="{row}">{{labels[row.status]||row.status}}</template></el-table-column><el-table-column prop="attempts" label="尝试次数"/><el-table-column prop="last_error" label="错误代码" min-width="220"/><el-table-column prop="created_at" label="创建时间" min-width="180"/><el-table-column label="操作"><template #default="{row}"><el-button v-if="['RETRY','FAILED'].includes(row.status)" link @click="retry(row.id)">重试</el-button></template></el-table-column></el-table></el-card></template>
<script setup>
import {ref,onMounted} from 'vue'
import request from '@/utils/request'
const rows=ref([]),busy=ref(false),labels={PENDING:'等待发送',SENDING:'发送中',SENT:'已发送',RETRY:'等待重试',FAILED:'发送失败',EXPIRED:'已过期'}
async function load(){busy.value=true;try{rows.value=(await request.get('/admin/wechat/notifications')).data}finally{busy.value=false}}
async function retry(id){await request.post('/admin/wechat/notifications/'+id+'/retry');await load()}
onMounted(load)
</script>
<style scoped>.toolbar{display:flex;gap:16px;align-items:center;justify-content:space-between}.el-alert{margin-bottom:20px}</style>
