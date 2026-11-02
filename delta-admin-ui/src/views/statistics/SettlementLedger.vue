<template><el-card v-loading="busy"><template #header><strong>自动分账台账</strong></template><el-alert title="每单结算保存俱乐部净抽成、打手总分成、客服提成。客服提成按下单时的比例快照从俱乐部抽成中计算，未绑定客服的订单不计客服提成。台账不直接发起银行转账。" :closable="false"/><div class="ledger-toolbar"><el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期"/><el-button @click="load">查询</el-button><el-button v-if="can('finance.export')" type="primary" :loading="exporting" @click="download">导出对账Excel</el-button></div><el-table :data="rows"><el-table-column prop="order_no" label="订单号" min-width="220"/><el-table-column v-for="column in columns" :key="column.key" :prop="column.key" :label="column.name" min-width="140"/><el-table-column prop="player_name" label="打手" min-width="130"/><el-table-column prop="cs_name" label="客服" min-width="130"/><el-table-column prop="created_at" label="结算时间" min-width="180"/></el-table><p>页面最多显示1000条；导出最多10000条，超出时请缩小日期范围。</p></el-card></template>
<script setup>
import {ref,onMounted} from 'vue'
import request from '@/utils/request'
import {getToken} from '@/utils/auth'
import {can} from '@/utils/staff-permissions'
import {ElMessage} from 'element-plus'
const date=new Date(),today=date.toISOString().slice(0,10);date.setDate(1)
const range=ref([date.toISOString().slice(0,10),today]),rows=ref([]),busy=ref(false),exporting=ref(false)
const columns=[{key:'amount',name:'订单金额'},{key:'club_amount',name:'俱乐部净抽成'},{key:'player_amount',name:'打手总分成'},{key:'cs_amount',name:'客服提成'},{key:'refund_amount',name:'实际退款'},{key:'club_refund',name:'俱乐部冲正'},{key:'player_refund',name:'打手冲正'},{key:'cs_refund',name:'客服冲正'},{key:'pending_recovery',name:'待追回款'}]
async function load(){if(!range.value?.length)return;busy.value=true;try{rows.value=(await request.get('/admin/settlements',{params:{from:range.value[0],to:range.value[1]}})).data}finally{busy.value=false}}
async function download(){if(!range.value?.length)return;exporting.value=true;try{const response=await fetch((import.meta.env.VITE_API_BASE_URL||'/api')+'/admin/settlements/export?'+new URLSearchParams({from:range.value[0],to:range.value[1]}),{headers:{Authorization:'Bearer '+getToken()}});if(!response.ok||!response.headers.get('content-type')?.includes('spreadsheetml')){let result;try{result=await response.json()}catch{}throw new Error(result?.msg||'导出失败')}const url=URL.createObjectURL(await response.blob()),a=document.createElement('a');a.href=url;a.download='订单分账对账.xlsx';a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}catch(e){ElMessage.error(e.message)}finally{exporting.value=false}}
onMounted(load)
</script>
<style scoped>.ledger-toolbar{display:flex;gap:12px;flex-wrap:wrap;margin:20px 0}.ledger-toolbar .el-date-editor{max-width:100%}p{font-size:13px;color:#858b94}</style>
