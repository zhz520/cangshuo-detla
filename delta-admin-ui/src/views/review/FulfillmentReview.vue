<template>
  <div class="page-container fulfillment-review">
    <el-card shadow="never">
      <template #header>
        <div class="review-header">
          <div><strong>履约审核</strong><span>统一处理接力与换人申请</span></div>
          <el-tag type="info">底层资金与订单状态规则保持独立</el-tag>
        </div>
      </template>
      <el-tabs v-model="active" @tab-change="syncQuery">
        <el-tab-pane label="接力审核" name="relay"/>
        <el-tab-pane label="换人审核" name="replace"/>
      </el-tabs>
      <KeepAlive>
        <RelayList v-if="active==='relay'"/>
        <ReplaceList v-else/>
      </KeepAlive>
    </el-card>
  </div>
</template>

<script setup>
import {ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import RelayList from '@/views/relay/RelayList.vue'
import ReplaceList from '@/views/replace/ReplaceList.vue'
const route=useRoute(),router=useRouter()
const active=ref(route.query.type==='replace'?'replace':'relay')
function syncQuery(){router.replace({query:{...route.query,type:active.value}})}
watch(()=>route.query.type,value=>{if(value==='replace'||value==='relay')active.value=value})
</script>

<style scoped>
.review-header{display:flex;align-items:center;justify-content:space-between;gap:16px;flex-wrap:wrap}.review-header strong{font-size:18px}.review-header span{display:block;margin-top:4px;color:#8a919b;font-size:13px}.fulfillment-review :deep(.el-tabs__content .el-card){box-shadow:none!important}.fulfillment-review :deep(.el-tabs__content .page-container){padding:0}.fulfillment-review :deep(.el-tabs__content .el-card__header){display:none}
</style>
