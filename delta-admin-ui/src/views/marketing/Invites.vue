<template>
  <div class="page-container">
    <el-card class="summary-card">
      <div class="summary-head"><div><strong>邀请奖励运营</strong><p>邀请关系只能在首次注册时归因一次；奖励在全额退款后自动冲正，余额不足的部分进入待追回台账。</p></div><el-button :loading="loading" @click="load">刷新</el-button></div>
      <div class="summary-grid"><div><span>邀请关系</span><strong>{{ relations.length }}</strong></div><div><span>奖励记录</span><strong>{{ rewards.length }}</strong></div><div><span>待追回</span><strong>{{ debts.filter(item => item.status === 'PENDING').length }}</strong></div></div>
    </el-card>
    <el-card>
      <el-tabs v-model="tab">
        <el-tab-pane label="邀请关系" name="relations">
          <el-table :data="relations" v-loading="loading" stripe>
            <el-table-column prop="inviter_nickname" label="邀请人" min-width="140" />
            <el-table-column prop="invitee_nickname" label="被邀请人" min-width="140" />
            <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="relationTag(row.status)">{{ relationText(row.status) }}</el-tag></template></el-table-column>
            <el-table-column label="创建时间" min-width="180"><template #default="{ row }">{{ formatTime(row.created_at) }}</template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="奖励记录" name="rewards">
          <el-table :data="rewards" v-loading="loading" stripe>
            <el-table-column prop="inviter_nickname" label="邀请人" min-width="140" />
            <el-table-column label="金额" width="110"><template #default="{ row }">¥{{ Number(row.amount || 0).toFixed(2) }}</template></el-table-column>
            <el-table-column label="状态" width="110"><template #default="{ row }"><el-tag :type="rewardTag(row.status)">{{ rewardText(row.status) }}</el-tag></template></el-table-column>
            <el-table-column prop="order_id" label="首单编号" width="110" />
            <el-table-column label="创建时间" min-width="180"><template #default="{ row }">{{ formatTime(row.created_at) }}</template></el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="待追回台账" name="debts">
          <el-alert title="余额不足的退款冲正会记录在这里；请通过线下沟通或后续钱包扣减处理后再标记已追回。" type="warning" :closable="false" show-icon />
          <el-table :data="debts" v-loading="loading" stripe>
            <el-table-column prop="inviter_nickname" label="邀请人" min-width="140" />
            <el-table-column label="待追回金额" width="130"><template #default="{ row }">¥{{ Number(row.amount || 0).toFixed(2) }}</template></el-table-column>
            <el-table-column prop="status" label="状态" width="110" />
            <el-table-column prop="reward_id" label="奖励记录" width="110" />
            <el-table-column label="创建时间" min-width="180"><template #default="{ row }">{{ formatTime(row.created_at) }}</template></el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { getInviteOperations } from '@/api/marketing'
const loading = ref(false), tab = ref('relations')
const relations = ref([]), rewards = ref([]), debts = ref([])
const relationText = status => ({ PENDING:'等待首单', REWARDED:'已奖励', REFUNDED:'已退款冲正' })[status] || status
const relationTag = status => status === 'REWARDED' ? 'success' : status === 'REFUNDED' ? 'info' : 'warning'
const rewardText = status => ({ PENDING:'待入账', PAID:'已发放', REVOKED:'已冲正', CANCELLED:'已取消' })[status] || status
const rewardTag = status => status === 'PAID' ? 'success' : status === 'PENDING' ? 'warning' : 'info'
const formatTime = value => value ? new Date(value).toLocaleString('zh-CN', { hour12:false }) : '—'
async function load() {
  loading.value = true
  try {
    const data = (await getInviteOperations()).data || {}
    relations.value = data.relations || []
    rewards.value = data.rewards || []
    debts.value = data.debts || []
  } finally { loading.value = false }
}
onMounted(load)
</script>

<style scoped>
.summary-card { margin-bottom:16px; }
.summary-head { display:flex; align-items:flex-start; justify-content:space-between; gap:16px; }
.summary-head p { margin:8px 0 0; color:#6b7280; font-size:13px; line-height:1.6; }
.summary-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:12px; margin-top:18px; }
.summary-grid > div { display:grid; gap:6px; padding:14px; border-radius:12px; background:#f7f8fa; }
.summary-grid span { color:#6b7280; font-size:13px; }
.summary-grid strong { font-size:24px; }
@media (max-width:600px){ .summary-head{flex-direction:column;} .summary-grid{grid-template-columns:1fr;} }
</style>
