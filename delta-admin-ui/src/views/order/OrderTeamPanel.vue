<template>
  <section class="order-team" aria-labelledby="order-team-title">
    <header class="team-head">
      <div>
        <h3 id="order-team-title">协同管理</h3>
        <span v-if="order.playerId">主打手：{{ order.playerName || '打手 #' + order.playerId }} · {{ accepted.length + 1 }} 人在队</span>
      </div>
      <el-button v-if="editable && occupied < capacity" type="primary" size="small" :disabled="busy || loading || !!loadError" @click="openInvite()">邀请队友</el-button>
    </header>
    <div v-if="loadError" class="team-empty" role="alert">{{ loadError }} <el-button link type="primary" @click="load">重试</el-button></div>
    <div v-else v-loading="loading">
      <div v-if="!active.length && !loading" class="team-empty">暂无协同队友</div>
      <div v-for="row in active" :key="row.id" class="team-row">
        <div class="team-member">
          <el-avatar :size="30" :src="row.avatar || undefined">{{ name(row)[0] }}</el-avatar>
          <div class="team-person">
            <strong>{{ name(row) }}</strong>
            <div>
              {{ splitText(row) }}
              <template v-if="row.status === 'INVITED' && row.replacesParticipantId"> · 待接替 {{ former(row) }}</template>
            </div>
          </div>
          <el-tag size="small" :type="row.status === 'ACCEPTED' ? 'success' : 'warning'">{{ statusText[row.status] }}</el-tag>
        </div>
        <div v-if="editable" class="team-actions">
          <template v-if="row.status === 'ACCEPTED'">
            <el-button v-if="!replacementFor(row)" link type="primary" :disabled="busy" @click="openInvite(row)">更换</el-button>
            <span v-else class="pending-label">等待新队友接受</span>
            <el-button link type="danger" :disabled="busy" @click="openRemove(row)">立即移除</el-button>
          </template>
          <el-button v-else link type="primary" :disabled="busy" @click="revoke(row)">撤回邀请</el-button>
        </div>
      </div>
      <details v-if="history.length" class="team-history">
        <summary>协同历史（{{ history.length }}）</summary>
        <div v-for="row in history" :key="row.id" class="team-row">
          <div class="team-person">
            <strong>{{ name(row) }}</strong>
            <div>{{ splitText(row) }}<template v-if="row.rejectedAt || row.invitedAt"> · {{ row.rejectedAt || row.invitedAt }}</template></div>
          </div>
          <el-tag size="small" type="info">{{ statusText[row.status] || row.status }}</el-tag>
        </div>
      </details>
    </div>
    <el-dialog v-model="dialog" append-to-body :title="dialogTitle" width="min(500px, calc(100vw - 32px))"
      :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
      <el-form v-if="dialog" label-position="top" @submit.prevent>
        <template v-if="mode !== 'remove'">
          <p v-if="candidateInfo" class="team-note">
            {{ candidateInfo.requiredLevelId == null ? '订单未限制打手等级' :
              '订单要求等级：' + (candidateInfo.requiredLevelName || '等级 #' + candidateInfo.requiredLevelId) }}
          </p>
          <el-form-item :label="target ? '新队友' : '邀请打手'" required>
            <el-select v-model="inviteeId" remote filterable clearable :remote-method="searchCandidates"
              :loading="searching" :disabled="busy" no-data-text="暂无符合条件的在线打手"
              placeholder="按昵称搜索在线打手" style="width:100%">
              <el-option v-for="candidate in candidates" :key="candidate.id" :value="candidate.id"
                :label="candidateLabel(candidate)"
                :disabled="candidate.activeOrders >= maxConcurrent" />
            </el-select>
          </el-form-item>
          <p v-if="candidateError" class="team-error" role="alert">
            {{ candidateError }} <el-button link type="primary" :disabled="busy" @click="searchCandidates(lastCandidateKeyword)">重试</el-button>
          </p>
          <el-form-item label="分成方式" required>
            <el-select v-model="splitType" :disabled="busy" style="width:100%">
              <el-option v-for="[key,label] in Object.entries(splitLabels)" :key="key" :value="key" :label="label" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="splitType === 'CUSTOM'" label="队友分成（元）" required>
            <el-input-number v-model="customAmount" :min="0.01" :max="1000000" :precision="2" :disabled="busy" />
          </el-form-item>
        </template>
        <el-form-item v-if="target" label="操作原因" required>
          <el-input v-model="reason" type="textarea" :rows="2" maxlength="200" :disabled="busy" />
        </el-form-item>
        <p v-if="mode === 'remove'" class="team-note">确认后 {{ name(target) }} 立即退出本单及分成，其他队友保留。</p>
        <p v-else-if="target" class="team-note">{{ name(target) }} 在新队友接受前仍在队伍中。</p>
        <p v-if="formError" class="team-error" role="alert">{{ formError }}</p>
      </el-form>
      <template #footer>
        <el-button :disabled="busy" @click="dialog = false">取消</el-button>
        <el-button :type="mode === 'remove' ? 'danger' : 'primary'" :loading="busy"
          :disabled="mode !== 'remove' && (searching || !!candidateError || !inviteeId || selectedCandidateFull)"
          @click="submit">
          {{ mode === 'remove' ? '确认移除' : target ? '确认发送更换邀请' : '发送邀请' }}
        </el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orderTeamRoster, orderTeamCandidates, orderTeamInvite, orderTeamRevoke, orderTeamRemove } from '@/api/order-team'

const props = defineProps({ order: { type: Object, required: true }, admin: Boolean, canManage: Boolean })
const emit = defineEmits(['changed'])
const members = ref([])
const loading = ref(false), loadError = ref(''), busy = ref(false)
const dialog = ref(false), mode = ref('invite'), target = ref(null), formError = ref('')
const inviteeId = ref(null), splitType = ref('FIFTY_FIFTY'), customAmount = ref(undefined), reason = ref('')
const candidateInfo = ref(null), candidates = ref([]), searching = ref(false), candidateError = ref('')
const maxConcurrent = computed(() => candidateInfo.value?.maxConcurrent ?? 5)
const selectedCandidateFull = computed(() => candidates.value.find(row => row.id === inviteeId.value)?.activeOrders >= maxConcurrent.value)
let loadSequence = 0, searchSequence = 0, lastCandidateKeyword = ''
function candidateLabel(candidate) {
  const level = candidate.levelName || (candidate.levelId == null ? '未分级' : '等级 #' + candidate.levelId)
  return [candidate.nickname || '打手 #' + candidate.id, level, candidate.activeOrders >= maxConcurrent.value && '订单已满']
    .filter(Boolean).join(' · ')
}
const statusText = { ACCEPTED:'在队', INVITED:'待接受', REJECTED:'已拒绝', CANCELLED:'已撤回或作废', EXPIRED:'已过期', REPLACED:'已退出', RELEASED:'已退出', SETTLED:'已结算' }
const splitLabels = { FIFTY_FIFTY:'平分可分配收益', FORTY_SIXTY:'队友 40%', THIRTY_SEVENTY:'队友 30%', CUSTOM:'自定义金额' }
const editable = computed(() => props.canManage && props.order.playerId && !props.order.settleTime
  && Number(props.order.settled || 0) !== 1 && ['ACCEPTED','WAITING_TEAMMATE','IN_PROGRESS'].includes(props.order.status))
const accepted = computed(() => members.value.filter(row => row.status === 'ACCEPTED'))
const active = computed(() => members.value.filter(row => ['ACCEPTED','INVITED'].includes(row.status)))
const history = computed(() => members.value.filter(row => !['ACCEPTED','INVITED'].includes(row.status)))
const occupied = computed(() => active.value.filter(row => row.status === 'ACCEPTED' || !row.replacesParticipantId).length)
const capacity = computed(() => Math.max(1, (props.order.requiredPlayerCount || 1) - 1))
const dialogTitle = computed(() => mode.value === 'remove' ? '立即移除队友' : target.value ? '更换队友' : '邀请队友')
const name = row => row?.nickname || (row?.playerId ? '打手 #' + row.playerId : '原队友')
const former = row => name(members.value.find(m => String(m.id) === String(row.replacesParticipantId)))
const replacementFor = row => active.value.find(m => m.status === 'INVITED' && String(m.replacesParticipantId) === String(row.id))
const splitText = row => row.splitType === 'CUSTOM' ? '分成 ¥' + Number(row.splitAmount || 0).toFixed(2)
  : splitLabels[row.splitType] || splitLabels.FIFTY_FIFTY
const message = (error, fallback) => error?.response?.data?.msg || error?.message || error?.msg || fallback
async function load() {
  const current = ++loadSequence
  loading.value = true; loadError.value = ''
  try {
    const res = await orderTeamRoster(props.admin, props.order.id)
    if (current === loadSequence) members.value = res.data || []
  } catch (error) {
    if (current === loadSequence) loadError.value = message(error, '协同名单加载失败')
  } finally { if (current === loadSequence) loading.value = false }
}
async function searchCandidates(keyword = '') {
  if (!dialog.value || mode.value === 'remove') return
  const current = ++searchSequence
  lastCandidateKeyword = keyword
  searching.value = true
  candidateError.value = ''
  try {
    const res = await orderTeamCandidates(props.admin, props.order.id, { keyword, pageNum: 1, pageSize: 20 })
    if (current === searchSequence) {
      candidateInfo.value = res.data
      candidates.value = res.data?.players?.records || []
    }
  } catch (error) {
    if (current === searchSequence) {
      candidates.value = []
      candidateError.value = message(error, '可邀请打手加载失败')
    }
  } finally { if (current === searchSequence) searching.value = false }
}
function openInvite(row = null) {
  if (busy.value) return
  mode.value = 'invite'; target.value = row; formError.value = ''; reason.value = ''; inviteeId.value = null
  splitType.value = row?.splitType && Object.hasOwn(splitLabels, row.splitType) ? row.splitType : 'FIFTY_FIFTY'
  customAmount.value = row?.splitAmount ?? undefined
  candidates.value = []
  candidateInfo.value = null
  dialog.value = true
  searchCandidates()
}
function openRemove(row) {
  ++searchSequence
  mode.value = 'remove'; target.value = row; reason.value = ''; formError.value = ''; dialog.value = true
}
async function submit() {
  if (busy.value || !editable.value) return
  formError.value = ''
  if (target.value && !reason.value.trim()) { formError.value = '请填写操作原因'; return }
  if (mode.value !== 'remove' && (searching.value || candidateError.value || !inviteeId.value || selectedCandidateFull.value)) {
    formError.value = '请选择当前可邀请的打手'; return
  }
  if (mode.value !== 'remove' && splitType.value === 'CUSTOM' && !(customAmount.value > 0)) {
    formError.value = '请填写有效分成金额'; return
  }
  busy.value = true
  try {
    if (mode.value === 'remove') {
      await orderTeamRemove(props.admin, props.order.id, target.value.id, reason.value.trim())
      ElMessage.success('队友已移除')
    } else {
      await orderTeamInvite(props.admin, props.order.id, {
        inviteePlayerId: inviteeId.value, replacedTeammateId: target.value?.playerId,
        splitType: splitType.value, customAmount: splitType.value === 'CUSTOM' ? customAmount.value : undefined,
        reason: reason.value.trim()
      })
      ElMessage.success('邀请已发送')
    }
    dialog.value = false
    await load()
    emit('changed')
  } catch (error) {
    formError.value = message(error, '提交失败，请重试')
    if (mode.value !== 'remove' && [403, 409].includes(error?.code ?? error?.response?.status)) {
      inviteeId.value = null
      await searchCandidates(lastCandidateKeyword)
    }
  } finally { busy.value = false }
}
async function revoke(row) {
  if (busy.value || !editable.value) return
  busy.value = true
  try {
    try { await ElMessageBox.confirm('撤回发给 ' + name(row) + ' 的邀请？', '撤回邀请', { type: 'warning' }) }
    catch (_) { return }
    await orderTeamRevoke(props.admin, props.order.id, row.id)
    ElMessage.success('邀请已撤回')
    await load()
    emit('changed')
  } catch (error) { ElMessage.error(message(error, '撤回失败，请刷新')) }
  finally { busy.value = false }
}
watch(dialog, value => { if (!value) { ++searchSequence; searching.value = false } })
onMounted(load)
onBeforeUnmount(() => { ++loadSequence; ++searchSequence })
</script>

<style scoped>
.order-team { margin-top: 20px; padding: 16px; border: 1px solid var(--el-border-color-lighter); border-radius: 12px; }
.team-head, .team-row, .team-member, .team-actions { display: flex; align-items: center; gap: 8px; }
.team-head, .team-row { justify-content: space-between; }
.team-head { margin-bottom: 8px; }
.team-head h3 { margin: 0 0 4px; font-size: 15px; }
.team-head span, .team-person div, .pending-label, .team-note, .team-empty { font-size: 12px; color: var(--el-text-color-secondary); }
.team-row { padding: 10px 0; border-top: 1px solid var(--el-border-color-lighter); flex-wrap: wrap; }
.team-member, .team-person { min-width: 0; }
.team-person strong { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.team-member .el-avatar { flex: none; }
.team-actions { flex-wrap: wrap; }
.team-actions .el-button + .el-button { margin-left: 0; }
.team-empty { padding: 12px 0; }
.team-history { margin-top: 8px; font-size: 13px; }
.team-history summary { cursor: pointer; padding: 8px 0; }
.team-error { color: var(--el-color-danger); }
</style>
