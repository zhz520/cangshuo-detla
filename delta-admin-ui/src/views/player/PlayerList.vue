<template>
  <div class="page-container">
    <el-card>
      <template #header>
        <PageHeader title="打手管理" description="审核、分级、冻结和查看打手的押金与接单状态。">
          <template #actions><el-button v-if="isAdmin" @click="$router.push('/player-level')">管理等级分类</el-button></template>
        </PageHeader>
      </template>
      <div v-if="query.taskId" style="margin-bottom:10px">已定位待办 #{{ query.taskId }}
        <el-button link @click="router.replace({ query: {} })">查看全部记录</el-button></div>
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item><el-input v-model="query.keyword" placeholder="搜索打手" clearable @keyup.enter="fetchData" /></el-form-item>
        <el-form-item>
          <el-select v-model="query.status" placeholder="状态" clearable>
            <el-option label="待审核" value="PENDING" /><el-option label="正常" value="ACTIVE" />
            <el-option label="已驳回" value="REJECTED" /><el-option label="已冻结" value="FROZEN" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" @click="fetchData">查询</el-button><el-button @click="query.keyword='';query.status='';fetchData()">重置</el-button></el-form-item>
      </el-form>
      <BatchActionBar v-if="isAdmin" :count="selectedRows.length" :disabled="batchBusy" hint="批量操作会逐项校验审核、等级和冻结状态" @clear="clearSelection">
        <el-button link :disabled="batchBusy" @click="batchApprove">批量通过</el-button>
        <el-button link :disabled="batchBusy" @click="batchRejectVisible = true">批量驳回</el-button>
        <el-button link :disabled="batchBusy" @click="batchLevelVisible = true">批量调整等级</el-button>
        <el-button link :disabled="batchBusy" @click="batchUnfreeze">批量解冻</el-button>
      </BatchActionBar>
      <el-table ref="tableRef" row-key="id" :data="list" v-loading="loading" stripe @selection-change="onSelectionChange">
        <el-table-column v-if="isAdmin" type="selection" width="48" :selectable="row => !batchBusy && row.id > 0" />
        <el-table-column label="头像" width="70">
          <template #default="{ row }">
            <el-avatar :src="thumbnailUrl(row.avatar,160)" :size="32">{{ (row.nickname || '')[0] || '打' }}</el-avatar>
          </template>
        </el-table-column>
        <el-table-column prop="nickname" label="昵称" min-width="120" />
        <el-table-column prop="phone" label="手机号" width="120" />
        <el-table-column prop="level" label="等级" width="80"><template #default="{ row }"><el-tag type="info" size="small">{{ levelLabel(row.level) }}</el-tag></template></el-table-column>
        <el-table-column prop="completedOrders" label="完成订单" width="90" />
        <el-table-column prop="balance" label="余额" width="90"><template #default="{ row }">¥{{ row.balance || 0 }}</template></el-table-column>
        <el-table-column label="押金" width="170">
          <template #default="{ row }">
            <el-tag :type="depositTagType(row.depositStatus)" size="small">{{ depositStatusText(row.depositStatus) }}</el-tag>
            <div class="deposit-amount">¥{{ money(row.depositPaidAmount) }} / ¥{{ money(row.depositRequiredAmount) }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" min-width="130">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
            <div v-if="row.status === 'FROZEN' && row.frozenUntil" class="deposit-time">解冻时间：{{ row.frozenUntil }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row.id)">详情</el-button>
            <el-dropdown trigger="click" @command="command => handleRowAction(command, row)">
              <el-button link type="primary">更多<el-icon><ArrowDown /></el-icon></el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit">{{ isAdmin ? '编辑' : '改名' }}</el-dropdown-item>
                  <el-dropdown-item command="orders">订单</el-dropdown-item>
                  <el-dropdown-item v-if="isAdmin" command="reviews">评价</el-dropdown-item>
                  <el-dropdown-item v-if="row.status === 'PENDING'" command="approve">通过</el-dropdown-item>
                  <el-dropdown-item v-if="row.status === 'PENDING'" command="reject">驳回</el-dropdown-item>
                  <el-dropdown-item v-if="row.isOnline === 1" command="offline">下班</el-dropdown-item>
                  <el-dropdown-item v-if="row.status === 'ACTIVE'" command="freeze">冻结</el-dropdown-item>
                  <el-dropdown-item v-if="row.status === 'FROZEN'" command="unfreeze">解冻</el-dropdown-item>
                  <el-dropdown-item v-if="isAdmin" command="delete" divided>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
      <Pagination :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="fetchData" />
    </el-card>
    <el-dialog v-model="batchRejectVisible" title="批量驳回入驻申请" width="min(460px, 94vw)">
      <p class="dialog-tip">将批量驳回已选的 {{ selectedRows.length }} 个打手，原因会写入通知。</p>
      <el-input v-model="batchRejectReason" type="textarea" :rows="4" maxlength="255" show-word-limit placeholder="请输入统一驳回原因" />
      <template #footer><el-button @click="batchRejectVisible = false">取消</el-button><el-button type="danger" :loading="batchBusy" :disabled="!batchRejectReason.trim() || batchBusy" @click="batchReject">确认驳回</el-button></template>
    </el-dialog>
    <el-dialog v-model="batchLevelVisible" title="批量调整打手等级" width="min(440px, 94vw)">
      <p class="dialog-tip">将已选的 {{ selectedRows.length }} 个打手统一调整到：</p>
      <el-select v-model="batchLevelId" placeholder="选择打手等级" style="width:100%"><el-option v-for="level in levelOptions.filter(item => item.status !== 0)" :key="level.id" :label="level.name" :value="level.id" /></el-select>
      <template #footer><el-button @click="batchLevelVisible = false">取消</el-button><el-button type="primary" :loading="batchBusy" :disabled="!batchLevelId || batchBusy" @click="batchLevel">确认调整</el-button></template>
    </el-dialog>
    <!-- 驳回弹窗 -->
    <el-dialog v-model="rejectDialogVisible" title="驳回入驻申请" width="min(420px, 94vw)">
      <p class="dialog-tip">驳回「{{ rejectName }}」的入驻申请，原因会通过站内通知发送给申请人。</p>
      <el-input v-model="rejectReason" type="textarea" :rows="3" maxlength="255" show-word-limit placeholder="请输入驳回原因，例如：证明截图不清晰，请重新上传" />
      <template #footer><el-button @click="rejectDialogVisible = false">取消</el-button><el-button type="primary" @click="handleReject">确定驳回</el-button></template>
    </el-dialog>
    <el-dialog v-model="approveVisible" title="通过入驻审核" width="min(520px, 94vw)" destroy-on-close>
      <p class="dialog-tip">请再次核对申请资料，通过后该打手可立即接单。</p>
      <el-descriptions v-if="approveRow" :column="1" border size="small">
        <el-descriptions-item label="接单名">{{ approveRow.nickname || '-' }}</el-descriptions-item>
        <el-descriptions-item label="真实姓名">{{ approveRow.realName || '未填写' }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ approveRow.phone || '-' }}</el-descriptions-item>
        <el-descriptions-item label="游戏ID/段位">{{ approveRow.gameLevel || '未填写' }}</el-descriptions-item>
        <el-descriptions-item label="擅长服务">{{ approveRow.serviceTypes || '未填写' }}</el-descriptions-item>
      </el-descriptions>
      <el-form v-if="isAdmin" label-width="88px" style="margin-top:16px">
        <el-form-item label="分配等级">
          <el-select v-model="approveLevelId" clearable placeholder="不选择则保持当前等级" style="width:100%">
            <el-option v-for="level in levelOptions.filter(item => item.status !== 0)" :key="level.id" :label="level.name" :value="level.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="approveVisible = false">取消</el-button>
        <el-button type="primary" :loading="approveSubmitting" @click="confirmApprove">确认通过</el-button>
      </template>
    </el-dialog>
    <!-- 打手详情弹窗（精简版） -->
    <el-dialog v-model="detailVisible" title="打手详情" width="980px" destroy-on-close>
      <div v-loading="detailLoading" class="player-detail">
        <template v-if="detailData">
        <div class="player-detail-header">
          <el-avatar :src="thumbnailUrl(detailData.avatar,320)" :size="56">{{ (detailData.nickname || '')[0] || '打' }}</el-avatar>
          <div class="player-detail-main">
            <div class="player-detail-name">{{ detailData.nickname || ('ID: ' + detailData.id) }}</div>
            <div class="player-detail-sub">
              <span class="player-detail-id">ID: {{ detailData.id }}</span>
              <el-tag :type="statusTagType(detailData.status)" size="small" style="margin-left:8px">
                {{ statusText(detailData.status) }}
              </el-tag>
            </div>
          </div>
        </div>
        <el-alert v-if="detailData.status === 'PENDING'" type="warning" :closable="false" show-icon
          title="待审核入驻申请" description="请核对真实姓名、手机号、擅长服务和证明截图后再通过或驳回。" style="margin-top:12px" />
        <el-divider content-position="left">入驻申请资料</el-divider>
        <el-descriptions :column="isNarrow ? 1 : 2" border size="small">
          <el-descriptions-item label="真实姓名">{{ detailData.realName || '未填写' }}</el-descriptions-item>
          <el-descriptions-item label="手机号">{{ detailData.phone || '-' }}</el-descriptions-item>
          <el-descriptions-item label="游戏ID/段位">{{ detailData.gameLevel || '未填写' }}</el-descriptions-item>
          <el-descriptions-item label="擅长服务">{{ detailData.serviceTypes || '未填写' }}</el-descriptions-item>
          <el-descriptions-item label="技能标签" :span="2">{{ skillTagsText }}</el-descriptions-item>
          <el-descriptions-item label="个人简介" :span="2"><span class="introduction-text">{{ detailData.introduction || '暂未填写' }}</span></el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ detailData.createdAt || '-' }}</el-descriptions-item>
          <el-descriptions-item label="押金状态">
            <el-tag :type="depositTagType(detailData.depositStatus)" size="small">{{ depositStatusText(detailData.depositStatus) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="押金应缴 / 已缴">¥{{ money(detailData.depositRequiredAmount) }} / ¥{{ money(detailData.depositPaidAmount) }}</el-descriptions-item>
          <el-descriptions-item label="押金待补">¥{{ money(detailData.depositOutstandingAmount) }}</el-descriptions-item>
          <el-descriptions-item label="最近到账">{{ detailData.depositLastPaidAt || '-' }}</el-descriptions-item>
          <el-descriptions-item v-if="detailData.rejectReason" label="驳回原因" :span="2">
            {{ detailData.rejectReason }}
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="isAdmin" class="deposit-override">
          <span class="deposit-override-label">单独押金应缴</span>
          <el-input-number v-model="depositOverrideDraft" :min="0" :max="1000000" :precision="2" :controls="false"
            placeholder="跟随统一配置" />
          <el-button type="primary" size="small" :loading="depositOverrideSaving" @click="saveDepositOverride">保存</el-button>
          <el-button size="small" :disabled="detailData.depositOverrideAmount == null" @click="clearDepositOverride">恢复统一</el-button>
          <span class="deposit-override-hint">未单独设置时使用全局或等级押金。</span>
        </div>
        <div class="proof-section">
          <div class="proof-title">证明截图<span v-if="proofImages.length" class="proof-count">（{{ proofImages.length }} 张，点击可放大）</span></div>
          <div v-if="proofImages.length" class="proof-list">
            <el-image v-for="(img, idx) in proofImages" :key="img + idx" :src="thumbnailUrl(img, 320)"
              :preview-src-list="proofImages" :initial-index="idx" preview-teleported fit="cover" class="proof-image" />
          </div>
          <div v-else class="proof-empty">{{ detailData.status === 'PENDING' ? '该申请未上传证明截图，请联系申请人补充资料' : '未上传证明截图' }}</div>
        </div>
        <el-divider content-position="left">账户概况</el-divider>
        <el-descriptions :column="isNarrow ? 1 : 2" border size="small">
          <el-descriptions-item label="等级">{{ levelLabel(detailData.level) }}</el-descriptions-item>
          <el-descriptions-item label="余额">¥{{ detailData.balance || 0 }}</el-descriptions-item>
          <el-descriptions-item label="完成订单">{{ detailData.completedOrders || 0 }}</el-descriptions-item>
          <el-descriptions-item label="评分">{{ detailData.avgRating || '-' }}</el-descriptions-item>
        </el-descriptions>
        <div class="deposit-history-head">
          <el-divider content-position="left">押金缴纳记录</el-divider>
          <el-button link type="primary" @click="router.push({ path: '/deposit-refunds', query: { keyword: String(detailData.id) } })">押金退款记录</el-button>
        </div>
        <el-table :data="playerDeposits" v-loading="depositLoading" stripe size="small" max-height="300">
          <el-table-column prop="createdAt" label="创建时间" width="170" />
          <el-table-column prop="paymentNo" label="支付单号" min-width="190" show-overflow-tooltip />
          <el-table-column prop="amount" label="金额" width="110"><template #default="{ row }">¥{{ money(row.amount) }}</template></el-table-column>
          <el-table-column prop="status" label="状态" width="100"><template #default="{ row }"><el-tag :type="depositTagType(row.status)" size="small">{{ paymentStatusText(row.status) }}</el-tag></template></el-table-column>
          <el-table-column prop="payMethod" label="支付方式" width="130" />
          <el-table-column prop="paidAt" label="到账时间" width="170" />
          <el-table-column prop="refundReason" label="退款说明" min-width="180" show-overflow-tooltip />
        </el-table>
        <div v-if="!depositLoading && !playerDeposits.length" class="proof-empty">暂无押金支付记录</div>
        <GrabVerificationStats v-if="isAdmin" :player-id="detailData.id" />
        <el-divider content-position="left">金额变化详情</el-divider>
        <el-table :data="playerTransactions" v-loading="transactionLoading" stripe size="small" max-height="360">
          <el-table-column prop="createdAt" label="时间" width="170" />
          <el-table-column prop="type" label="类型" width="130">
            <template #default="{ row }">{{ transactionTypeLabel(row.type) }}</template>
          </el-table-column>
          <el-table-column prop="amount" label="金额" width="110">
            <template #default="{ row }">
              <span :class="transactionAmountClass(row.amount)">¥{{ formatTransactionAmount(row.amount) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="balanceBefore" label="变动前" width="110">
            <template #default="{ row }">¥{{ formatTransactionAmount(row.balanceBefore) }}</template>
          </el-table-column>
          <el-table-column prop="balanceAfter" label="变动后" width="110">
            <template #default="{ row }">¥{{ formatTransactionAmount(row.balanceAfter) }}</template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="220" show-overflow-tooltip />
        </el-table>
        <Pagination
          :total="transactionTotal"
          v-model:page="transactionQuery.pageNum"
          v-model:limit="transactionQuery.pageSize"
          @pagination="fetchPlayerTransactions"
        />
        </template>
      </div>
    </el-dialog>
    <!-- 打手编辑弹窗 -->
    <el-dialog v-model="editVisible" :title="isAdmin ? '编辑打手信息' : '修改昵称'" width="720px" destroy-on-close>
      <el-form :model="editForm" label-width="80px">
        <el-form-item label="昵称">
          <el-input v-model="editForm.nickname" placeholder="请输入昵称" maxlength="20" />
        </el-form-item>
        <template v-if="isAdmin">
          <el-form-item label="真实姓名">
            <el-input v-model="editForm.realName" placeholder="请输入真实姓名" maxlength="20" />
          </el-form-item>
          <el-form-item label="抢单验证"><el-select v-model="editForm.grabCaptchaOverride"><el-option label="跟随等级" :value="-1"/><el-option label="必须图形验证" :value="1"/><el-option label="免验证" :value="0"/></el-select></el-form-item>
          <el-form-item label="等级">
            <el-select v-model="editForm.level" placeholder="请选择等级分类"><el-option v-for="level in levelOptions" :key="level.id" :label="level.name + (level.status===0?'（已停用）':'')" :value="level.id"/></el-select>
          </el-form-item>
          <el-form-item label="下单涨价">
            <el-select :model-value="editForm.priceIncreaseRate < 0 ? 'level' : 'custom'" @update:model-value="editForm.priceIncreaseRate=$event==='level'?-1:0">
              <el-option label="跟随等级涨价" value="level"/><el-option label="单独设置涨价" value="custom"/>
            </el-select>
            <div v-if="editForm.priceIncreaseRate >= 0" style="margin-top:8px;width:100%"><el-input-number v-model="editForm.priceIncreaseRate" :min="0" :max="100" :precision="2"/> %</div>
            <div class="inline-tip">仅指定该打手下单时生效，0% 表示不涨价；已有订单金额不变。</div>
          </el-form-item>
          <el-form-item label="个人简介">
            <el-input v-model="editForm.introduction" type="textarea" :rows="5" maxlength="1000" show-word-limit placeholder="公开展示在打手个人主页" />
          </el-form-item>
          <PlayerHomepageFields :model="editForm" @busy="mediaBusy=$event"/>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" :disabled="mediaBusy" @click="handleEditSave">保存</el-button>
      </template>
    </el-dialog>
    <!-- 打手订单弹窗 -->
    <el-dialog v-model="ordersVisible" :title="ordersPlayerName + ' 的订单'" width="800px" destroy-on-close>
      <el-table :data="playerOrders" v-loading="ordersLoading" stripe size="small">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="orderNo" label="订单号" width="180" />
        <el-table-column prop="productName" label="商品" min-width="140" show-overflow-tooltip />
        <el-table-column prop="amount" label="金额" width="90">
          <template #default="{ row }">¥{{ row.amount }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="orderTagType(row.status)" size="small">{{ orderStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="下单时间" width="170" />
      </el-table>
      <Pagination :total="ordersTotal" v-model:page="ordersQuery.pageNum" v-model:limit="ordersQuery.pageSize" @pagination="fetchPlayerOrders" />
    </el-dialog>
    <!-- 冻结弹窗 -->
    <el-dialog v-if="isAdmin" v-model="reviewsVisible" :title="reviewsPlayerName + ' 的评价'" width="850px" destroy-on-close>
      <el-table :data="playerReviews" v-loading="reviewsLoading" stripe empty-text="暂无评价">
        <el-table-column prop="userNickname" label="评价用户" width="100" />
        <el-table-column prop="rating" label="评分" width="70"><template #default="{ row }">{{ row.rating }} 星</template></el-table-column>
        <el-table-column prop="content" label="评价内容" min-width="240"><template #default="{ row }"><span class="introduction-text">{{ row.content }}</span></template></el-table-column>
        <el-table-column prop="createdAt" label="评价时间" width="170" />
        <el-table-column label="操作" width="90"><template #default="{ row }"><el-button link type="danger" @click="deleteReview(row)">删除</el-button></template></el-table-column>
      </el-table>
      <Pagination :total="reviewsTotal" v-model:page="reviewsQuery.pageNum" v-model:limit="reviewsQuery.pageSize" @pagination="fetchPlayerReviews" />
    </el-dialog>
    <el-dialog v-model="freezeVisible" title="冻结打手" width="450px">
      <p v-if="freezeRow" style="margin-bottom:16px">打手：<b>{{ freezeRow.nickname }}</b>（{{ freezeRow.phone || '-' }}）</p>
      <el-form label-width="100px">
        <el-form-item label="冻结时长">
          <el-select v-model="freezeDuration" placeholder="选择冻结时长" style="width:100%">
            <el-option label="1天" :value="1" />
            <el-option label="3天" :value="3" />
            <el-option label="7天" :value="7" />
            <el-option label="15天" :value="15" />
            <el-option label="30天" :value="30" />
            <el-option label="永久冻结" :value="-1" />
            <el-option label="自定义" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="freezeDuration === 0" label="解冻时间">
          <el-date-picker v-model="freezeCustomDate" type="datetime" placeholder="选择解冻时间" value-format="YYYY-MM-DD HH:mm:ss" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="freezeVisible = false">取消</el-button>
        <el-button type="primary" :loading="freezeSubmitting" @click="handleFreeze">确认冻结</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { useRoute, useRouter } from 'vue-router'
import { watch } from 'vue'
import request from '@/utils/request'
import { useResponsive } from '@/utils/responsive'
import GrabVerificationStats from '@/components/GrabVerificationStats.vue'
import PageHeader from '@/components/PageHeader.vue'
import { ArrowDown } from '@element-plus/icons-vue'
import { computed, ref, reactive, onMounted } from 'vue'
import { adminPlayerList, adminPlayerDetail, adminPlayerTransactions, adminPlayerDeposits, adminSetPlayerDepositAmount, adminPlayerApprove, adminPlayerReject, adminPlayerUpdateStatus, adminPlayerFreeze, adminPlayerUnfreeze, adminPlayerDelete, adminPlayerUpdate, adminPlayerBatchApprove, adminPlayerBatchReject, adminPlayerBatchLevel, adminPlayerBatchUnfreeze, csPlayerList, csPlayerDetail, csPlayerTransactions, csPlayerAudit, csPlayerFreeze, csPlayerUnfreeze, csPlayerUpdateNickname, adminOrderList, csOrderList } from '@/api/business'
import { useUserStore } from '@/stores/user'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminReviewList, adminReviewDelete } from '@/api/business'
import Pagination from '@/components/Pagination.vue'
import BatchActionBar from '@/components/BatchActionBar.vue'
import { showBatchResult } from '@/utils/batch-result'
import PlayerHomepageFields from '@/components/PlayerHomepageFields.vue'
import {playerLevelList} from '@/api/business'
import {thumbnailUrl} from '@/utils/image'
const { isCompact, isNarrow } = useResponsive()

const userStore = useUserStore()
const isAdmin = userStore.role === 'admin'
const levelOptions=ref([]),mediaBusy=ref(false)
function levelLabel(id){return levelOptions.value.find(l=>l.id===id)?.name || `Lv.${id||1}`}
onMounted(async()=>{if(isAdmin)levelOptions.value=(await playerLevelList()).data||[]})
async function setOffline(row){await ElMessageBox.confirm('确认让该打手下班？需要打手本人操作才能重新上班。','打手下班');await request.put('/cs/player/'+row.id+'/online-status',{online:false});await fetchData()}
const loading = ref(false), list = ref([]), total = ref(0)
const tableRef = ref(null), selectedRows = ref([]), batchBusy = ref(false)
const batchRejectVisible = ref(false), batchRejectReason = ref('')
const batchLevelVisible = ref(false), batchLevelId = ref(null)
const selectedIds = computed(() => selectedRows.value.map(row => row.id))
function onSelectionChange(rows) { selectedRows.value = rows }
async function handleRowAction(command, row) {
  if (command === 'edit') return openEditDialog(row)
  if (command === 'orders') return showPlayerOrders(row)
  if (command === 'reviews') return showPlayerReviews(row)
  if (command === 'approve') return handleApprove(row)
  if (command === 'reject') return openRejectDialog(row)
  if (command === 'offline') return setOffline(row)
  if (command === 'freeze') return openFreezeDialog(row)
  if (command === 'unfreeze') {
    try { await ElMessageBox.confirm('确认解冻？', '解冻打手', { type: 'warning' }) } catch (_) { return }
    return handleUnfreeze(row.id)
  }
  if (command === 'delete') return handleDelete(row)
}
function clearSelection() { tableRef.value?.clearSelection(); selectedRows.value = [] }
const route = useRoute()
const router = useRouter()
const query = reactive({ pageNum: 1, pageSize: 10, keyword: '', status: '', taskId: '' })
const statusMap = { PENDING: '待审核', ACTIVE: '正常', REJECTED: '已驳回', FROZEN: '已冻结' }
const statusTagMap = { PENDING: 'warning', ACTIVE: 'success', REJECTED: 'danger', FROZEN: 'info' }
function statusText(s) { return statusMap[s] || s }
function statusTagType(s) { return statusTagMap[s] || '' }
const depositStatusMap = { NONE: '无需押金', UNPAID: '未缴', PARTIAL: '部分缴纳', PAID: '已缴清', PAYING: '支付中', CLOSED: '已关闭', REFUNDING: '退款中', REFUNDED: '已退款', FAILED: '支付失败', NO_PLAYER: '未绑定打手' }
const depositTagMap = { NONE: 'info', UNPAID: 'danger', PARTIAL: 'warning', PAID: 'success', PAYING: 'warning', CLOSED: 'info', REFUNDING: 'warning', REFUNDED: 'danger', FAILED: 'danger', NO_PLAYER: 'info' }
function depositStatusText(s) { return depositStatusMap[s] || s || '-' }
function depositTagType(s) { return depositTagMap[s] || 'info' }
function paymentStatusText(s) { return ({ PAID: '已到账', PAYING: '支付中', CLOSED: '已关闭', REFUNDING: '退款中', REFUNDED: '已退款', FAILED: '支付失败' })[s] || s || '-' }
function money(value) { return Number(value || 0).toFixed(2) }
async function fetchData() {
  clearSelection()
  loading.value = true
  try {
    const fn = isAdmin ? adminPlayerList : csPlayerList
    const res = await fn(query)
    list.value = res.data.records; total.value = Number(res.data.total)
  } finally { loading.value = false }
}
async function runBatch(action, label) {
  if (!selectedIds.value.length || batchBusy.value) return
  batchBusy.value = true
  try {
    const response = await action(selectedIds.value)
    showBatchResult(response.data, label)
    clearSelection()
    await fetchData()
  } finally { batchBusy.value = false }
}
async function batchApprove() {
  try { await ElMessageBox.confirm('确认批量通过已选的 '+selectedIds.value.length+' 个入驻申请？系统会逐项校验资料。', '批量通过入驻审核', { type: 'warning', confirmButtonText: '确认通过' }) } catch (_) { return }
  await runBatch(ids => adminPlayerBatchApprove(ids), '批量通过入驻审核')
}
async function batchReject() {
  const reason = batchRejectReason.value.trim()
  if (!reason) return ElMessage.warning('请填写驳回原因')
  batchRejectVisible.value = false
  await runBatch(ids => adminPlayerBatchReject(ids, reason), '批量驳回入驻审核')
  batchRejectReason.value = ''
}
async function batchLevel() {
  if (!batchLevelId.value) return ElMessage.warning('请选择打手等级')
  batchLevelVisible.value = false
  await runBatch(ids => adminPlayerBatchLevel(ids, batchLevelId.value), '批量调整打手等级')
}
async function batchUnfreeze() {
  try { await ElMessageBox.confirm('确认批量解冻已选的 '+selectedIds.value.length+' 个打手？解冻后打手需要自行重新上班。', '批量解冻打手', { type: 'warning', confirmButtonText: '确认解冻' }) } catch (_) { return }
  await runBatch(ids => adminPlayerBatchUnfreeze(ids), '批量解冻打手')
}
const approveVisible = ref(false), approveRow = ref(null), approveLevelId = ref(null), approveSubmitting = ref(false)
function handleApprove(row) {
  if (!row || row.status !== 'PENDING') return
  approveRow.value = row
  approveLevelId.value = null
  approveVisible.value = true
}
async function confirmApprove() {
  if (!approveRow.value || approveSubmitting.value) return
  const row = approveRow.value
  const name = row.nickname || row.realName || ('ID ' + row.id)
  approveSubmitting.value = true
  try {
    if (isAdmin) await adminPlayerApprove(row.id, approveLevelId.value ?? undefined)
    else await csPlayerAudit(row.id, 'ACTIVE', '')
    ElMessage.success(`已通过「${name}」的入驻申请`)
    approveVisible.value = false
    await fetchData()
    if (detailVisible.value && detailData.value?.id === row.id) await showDetail(row.id)
  } finally {
    approveSubmitting.value = false
  }
}
const rejectDialogVisible = ref(false), rejectId = ref(null), rejectReason = ref(''), rejectName = ref('')
function openRejectDialog(row) {
  if (!row || row.status !== 'PENDING') return
  rejectId.value = row.id
  rejectName.value = row.nickname || row.realName || ('ID ' + row.id)
  rejectReason.value = ''
  rejectDialogVisible.value = true
}
async function handleReject() {
  if (!rejectReason.value.trim()) return ElMessage.warning('请填写驳回原因')
  if (isAdmin) await adminPlayerReject(rejectId.value, rejectReason.value)
  else await csPlayerAudit(rejectId.value, 'REJECTED', rejectReason.value)
  ElMessage.success(`已驳回「${rejectName.value}」的入驻申请`)
  rejectDialogVisible.value = false
  await fetchData()
  if (detailVisible.value && detailData.value?.id === rejectId.value) await showDetail(rejectId.value)
}
// 冻结弹窗
const freezeVisible = ref(false), freezeRow = ref(null), freezeDuration = ref(7), freezeCustomDate = ref(''), freezeSubmitting = ref(false)
function openFreezeDialog(row) {
  freezeRow.value = row; freezeDuration.value = 7; freezeCustomDate.value = ''; freezeVisible.value = true
}
async function handleFreeze() {
  let frozenUntil = null
  if (freezeDuration.value === -1) {
    frozenUntil = '2099-12-31 23:59:59'
  } else if (freezeDuration.value === 0) {
    if (!freezeCustomDate.value) return ElMessage.warning('请选择解冻时间')
    frozenUntil = freezeCustomDate.value
  } else {
    const d = new Date(); d.setDate(d.getDate() + freezeDuration.value)
    frozenUntil = d.getFullYear() + '-' + String(d.getMonth()+1).padStart(2,'0') + '-' + String(d.getDate()).padStart(2,'0') + ' ' + String(d.getHours()).padStart(2,'0') + ':' + String(d.getMinutes()).padStart(2,'0') + ':' + String(d.getSeconds()).padStart(2,'0')
  }
  freezeSubmitting.value = true
  try {
    if (isAdmin) await adminPlayerFreeze(freezeRow.value.id, { frozenUntil })
    else await csPlayerFreeze(freezeRow.value.id, { frozenUntil })
    ElMessage.success('已冻结'); freezeVisible.value = false; fetchData()
  } finally { freezeSubmitting.value = false }
}
async function handleUnfreeze(id) {
  if (isAdmin) await adminPlayerUnfreeze(id)
  else await csPlayerUnfreeze(id)
  ElMessage.success('已解冻'); fetchData()
}
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除打手“${row.nickname || row.id}”？删除后保留历史订单和资金记录，并释放手机号与登录标识；有余额、冻结金额、进行中订单、未完成提现、未处理投诉或待追回打赏时不能删除。`,
      '删除打手',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch (_) { return }
  await adminPlayerDelete(row.id)
  ElMessage.success('打手已删除')
  if (detailVisible.value && detailData.value?.id === row.id) detailVisible.value = false
  clearSelection()
  await fetchData()
}
const detailVisible = ref(false), detailData = ref(null), detailLoading = ref(false)
const playerDeposits = ref([]), depositLoading = ref(false)
const depositOverrideDraft = ref(null), depositOverrideSaving = ref(false)
const proofImages = computed(() => {
  const raw = detailData.value?.proofImages
  if (!raw) return []
  const list = Array.isArray(raw) ? raw : String(raw).split(',')
  return list.map(item => String(item || '').trim()).filter(Boolean)
})
const skillTagsText = computed(() => {
  const raw = detailData.value?.skillTags
  if (!raw) return '未填写'
  if (Array.isArray(raw)) return raw.filter(Boolean).join('、') || '未填写'
  const text = String(raw).trim()
  try {
    const parsed = JSON.parse(text)
    if (Array.isArray(parsed)) return parsed.filter(Boolean).join('、') || '未填写'
  } catch (_) { /* 普通文本 */ }
  return text.split(/[,，]/).map(item => item.trim()).filter(Boolean).join('、') || '未填写'
})
const transactionLoading = ref(false)
const playerTransactions = ref([])
const transactionTotal = ref(0)
const transactionQuery = reactive({ pageNum: 1, pageSize: 5, playerId: null })
const transactionTypeMap = {
  REWARD_PAY:'打赏支出', REWARD_INCOME:'打赏收入', REWARD_REFUND:'打赏退款', REWARD_REVERSAL:'打赏冲正',
  ADMIN_RECHARGE: '管理员充值',
  ADMIN_DEDUCT: '管理员扣款',
  INCOME: '收益入账',
  WITHDRAW: '提现申请',
  WITHDRAW_COMPLETE: '提现完成',
  WITHDRAW_REJECT: '提现驳回',
  CONSUMPTION: '订单扣款',
  REFUND: '退款入账'
}
function transactionTypeLabel(type) {
  return transactionTypeMap[type] || type || '-'
}
function formatTransactionAmount(amount) {
  const value = Number(amount || 0)
  return `${value >= 0 ? '+' : ''}${value.toFixed(2)}`
}
function transactionAmountClass(amount) {
  return Number(amount || 0) >= 0 ? 'amount-positive' : 'amount-negative'
}
async function fetchPlayerTransactions() {
  if (!transactionQuery.playerId) return
  transactionLoading.value = true
  try {
    const params = { pageNum: transactionQuery.pageNum, pageSize: transactionQuery.pageSize }
    const fn = isAdmin ? adminPlayerTransactions : csPlayerTransactions
    const res = await fn(transactionQuery.playerId, params)
    playerTransactions.value = res.data?.records || []
    transactionTotal.value = Number(res.data?.total || 0)
  } finally {
    transactionLoading.value = false
  }
}
async function showDetail(id) {
  detailVisible.value = true
  detailLoading.value = true
  detailData.value = null
  playerDeposits.value = []
  depositLoading.value = true
  transactionQuery.playerId = id
  transactionQuery.pageNum = 1
  playerTransactions.value = []
  transactionTotal.value = 0
  try {
    const detailFn = isAdmin ? adminPlayerDetail : csPlayerDetail
    await Promise.all([
      detailFn(id).then(res => {
        detailData.value = res.data
        depositOverrideDraft.value = res.data?.depositOverrideAmount ?? null
      }),
      fetchPlayerTransactions(),
      isAdmin
        ? adminPlayerDeposits(id).then(res => { playerDeposits.value = res.data || [] }).catch(() => { playerDeposits.value = [] })
        : Promise.resolve()
    ])
  } finally {
    detailLoading.value = false
    depositLoading.value = false
  }
}

async function saveDepositOverride() {
  if (!detailData.value?.id || depositOverrideSaving.value) return
  depositOverrideSaving.value = true
  try {
    const amount = depositOverrideDraft.value == null ? null : Number(depositOverrideDraft.value)
    await adminSetPlayerDepositAmount(detailData.value.id, amount)
    const res = await adminPlayerDetail(detailData.value.id)
    detailData.value = res.data
    depositOverrideDraft.value = res.data?.depositOverrideAmount ?? null
    ElMessage.success(amount == null ? '已恢复统一押金' : '单独押金已保存')
  } catch (e) {
    ElMessage.error(e?.msg || e?.message || '保存失败')
  } finally {
    depositOverrideSaving.value = false
  }
}

function clearDepositOverride() {
  depositOverrideDraft.value = null
  return saveDepositOverride()
}

// 编辑打手
const editVisible = ref(false)
const editSaving = ref(false)
const editForm = reactive({ id: null, nickname: '', realName: '', level: 1, grabCaptchaOverride:-1, priceIncreaseRate:-1, introduction: '', avatar:'',coverImage:'',albumImages:[],story:'',voiceUrl:'',voiceDuration:0 })
function openEditDialog(row) {
  editForm.id = row.id
  editForm.nickname = row.nickname || ''
  editForm.realName = row.realName || ''
  editForm.level = row.level || 1
  editForm.grabCaptchaOverride=row.grabCaptchaOverride??-1
  editForm.priceIncreaseRate=Number(row.priceIncreaseRate??-1)
  editForm.introduction = row.introduction || ''
  for(const key of ['avatar','coverImage','story','voiceUrl'])editForm[key]=row[key]||''
  editForm.voiceDuration=row.voiceDuration||0
  try{editForm.albumImages=Array.isArray(row.albumImages)?[...row.albumImages]:JSON.parse(row.albumImages||'[]')}catch(_){editForm.albumImages=[]}
  editVisible.value = true
}
async function handleEditSave() {
  if (!editForm.id) return
  if (!String(editForm.nickname || '').trim()) return ElMessage.warning('请输入昵称')
  editSaving.value = true
  try {
    if (isAdmin) {
      await adminPlayerUpdate(editForm.id, {
        nickname: editForm.nickname,
        realName: editForm.realName,
        level: editForm.level, grabCaptchaOverride:editForm.grabCaptchaOverride, priceIncreaseRate:editForm.priceIncreaseRate,
        introduction: editForm.introduction,avatar:editForm.avatar,coverImage:editForm.coverImage,albumImages:editForm.albumImages.filter(Boolean),story:editForm.story,voiceUrl:editForm.voiceUrl,voiceDuration:editForm.voiceDuration
      })
    } else {
      await csPlayerUpdateNickname(editForm.id, editForm.nickname)
    }
    ElMessage.success('保存成功')
    editVisible.value = false
    fetchData()
    if (detailVisible.value && detailData.value?.id === editForm.id) {
      showDetail(editForm.id)
    }
  } finally {
    editSaving.value = false
  }
}

// 打手订单
const reviewsVisible = ref(false), reviewsLoading = ref(false), reviewsPlayerName = ref('')
const playerReviews = ref([]), reviewsTotal = ref(0)
const reviewsQuery = reactive({ pageNum: 1, pageSize: 10, playerId: null })
function showPlayerReviews(row) {
  reviewsPlayerName.value = row.nickname || ('ID: ' + row.id)
  reviewsQuery.playerId = row.id; reviewsQuery.pageNum = 1
  playerReviews.value = []; reviewsTotal.value = 0; reviewsVisible.value = true
  fetchPlayerReviews()
}
async function fetchPlayerReviews() {
  reviewsLoading.value = true
  try {
    const res = await adminReviewList(reviewsQuery)
    playerReviews.value = res.data?.records || []; reviewsTotal.value = Number(res.data?.total || 0)
  } finally { reviewsLoading.value = false }
}
async function deleteReview(review) {
  try { await ElMessageBox.confirm('删除后该评价将不再公开显示，打手和商品评分会重新计算。', '删除评价', { type: 'warning' }) }
  catch (_) { return }
  await adminReviewDelete(review.id)
  ElMessage.success('评价已删除')
  if (playerReviews.value.length === 1 && reviewsQuery.pageNum > 1) reviewsQuery.pageNum--
  await Promise.all([fetchPlayerReviews(), fetchData()])
  if (detailVisible.value && detailData.value?.id === reviewsQuery.playerId) showDetail(reviewsQuery.playerId)
}
const orderStatusMap = {
  PENDING_PAYMENT: '待支付', PAID: '待接单', ASSIGNED: '已指派', ACCEPTED: '已接单',
  WAITING_TEAMMATE: '组队中', IN_PROGRESS: '进行中', COMPLETED: '待确认',
  CONFIRMED: '已完成', REVIEWED: '已评价', CANCELLED: '已取消',
  REFUNDING: '退款中', REFUNDED: '已退款', DISPUTED: '争议中', ARBITRATED: '已仲裁'
}
const orderTagTypeMap = {
  PENDING_PAYMENT: 'info', PAID: '', ASSIGNED: '', ACCEPTED: '',
  WAITING_TEAMMATE: '', IN_PROGRESS: 'warning', COMPLETED: 'success',
  CONFIRMED: 'success', REVIEWED: 'success', CANCELLED: 'info',
  REFUNDING: 'danger', REFUNDED: 'info', DISPUTED: 'danger', ARBITRATED: 'info'
}
function orderStatusLabel(s) { return orderStatusMap[s] || s }
function orderTagType(s) { return orderTagTypeMap[s] || '' }

const ordersVisible = ref(false), ordersLoading = ref(false)
const playerOrders = ref([]), ordersTotal = ref(0), ordersPlayerName = ref('')
const ordersQuery = reactive({ pageNum: 1, pageSize: 10, playerId: null })

function showPlayerOrders(row) {
  ordersPlayerName.value = row.nickname || ('ID: ' + row.id)
  ordersQuery.playerId = row.id
  ordersQuery.pageNum = 1
  playerOrders.value = []
  ordersVisible.value = true
  fetchPlayerOrders()
}
async function fetchPlayerOrders() {
  ordersLoading.value = true
  try {
    const params = { pageNum: ordersQuery.pageNum, pageSize: ordersQuery.pageSize }
    if (ordersQuery.playerId) params.playerId = ordersQuery.playerId
    const fn = isAdmin ? adminOrderList : csOrderList
    const res = await fn(params)
    playerOrders.value = res.data?.records || []
    ordersTotal.value = Number(res.data?.total || 0)
  } finally { ordersLoading.value = false }
}

watch(() => route.query.taskId, id => {
  query.taskId = typeof id === 'string' && /^\d{1,19}$/.test(id) ? id : ''
  query.pageNum = 1
  if (query.taskId) query.status = ''
  fetchData()
}, { immediate: true })
</script>
<style scoped>
.search-form { margin-bottom: 16px; }
.deposit-time { font-size:12px; color:var(--el-text-color-secondary); margin-top:2px; }
.inline-tip { width:100%; color:var(--el-text-color-secondary); font-size:12px; }
.dialog-tip { margin: 0 0 12px; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.7; }
</style>

<style scoped>
.player-header{display:flex;justify-content:space-between;align-items:center}
.player-detail {
  padding: 4px 0;
}
.deposit-amount {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.deposit-override { display:flex; align-items:center; gap:10px; flex-wrap:wrap; margin-top:12px; padding:12px 14px; background:var(--el-fill-color-light); border-radius:10px; }
.deposit-override-label { font-size:13px; color:var(--el-text-color-regular); }
.deposit-override-hint { font-size:12px; color:var(--el-text-color-secondary); }
.deposit-history-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.deposit-history-head :deep(.el-divider) { flex: 1; margin-right: 8px; }
.introduction-text { white-space: pre-wrap; overflow-wrap: anywhere; }
.proof-section { margin-top: 16px; }
.proof-title { font-size: 13px; color: var(--el-text-color-regular); margin-bottom: 10px; }
.proof-count { color: var(--el-text-color-secondary); font-size: 12px; }
.proof-list { display: flex; flex-wrap: wrap; gap: 10px; }
.proof-image {
  width: 96px;
  height: 96px;
  border-radius: 8px;
  border: 1px solid var(--el-border-color-light);
  background: var(--el-fill-color-light);
  cursor: zoom-in;
}
.proof-empty {
  padding: 14px 16px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  background: var(--el-fill-color-lighter);
}
.player-detail-header {
  display: flex;
  align-items: center;
  gap: 16px;
}
.player-detail-main {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.player-detail-name {
  font-size: 16px;
  font-weight: 600;
}
.player-detail-sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  display: flex;
  align-items: center;
}
.player-detail-id {
  font-size: 12px;
}
.amount-positive { color: var(--el-color-danger); font-weight: 600; }
.amount-negative { color: var(--el-color-success); font-weight: 600; }
@media (max-width: 768px) {
  .player-detail-header { gap: 10px; }
  .player-detail-main { min-width: 0; }
  .player-detail-name, .player-detail-id { overflow-wrap: anywhere; word-break: break-word; }
  .player-detail-sub { flex-wrap: wrap; gap: 6px; }
}
</style>
