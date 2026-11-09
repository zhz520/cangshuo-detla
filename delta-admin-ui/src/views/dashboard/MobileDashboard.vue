<template>
  <section class="mobile-dashboard" :aria-busy="loading || analyticsLoading">
    <LoadFeedback :loading="loading" :error="error" label="概览" @retry="refreshAll" />

    <section class="mobile-hero">
      <div class="hero-top">
        <div class="hero-person">
          <el-avatar :size="46" :src="userStore.avatar || undefined">{{ displayName.slice(0, 1) }}</el-avatar>
          <div>
            <span>{{ roleLabel }} · {{ greeting }}</span>
            <strong>{{ displayName }}</strong>
            <small>今天也把每一单稳稳接住</small>
          </div>
        </div>
        <button type="button" class="hero-refresh" :class="{ spinning: loading || analyticsLoading || shiftLoading }" :disabled="loading || analyticsLoading || shiftLoading" aria-label="刷新工作台" @click="refreshAll">↻</button>
      </div>
      <div class="hero-shift" :class="{ online: shift.online }">
        <div class="hero-shift-copy">
          <div><i /><strong>{{ shiftTitle }}</strong><small v-if="shiftLoading">同步中</small></div>
          <p v-if="shiftError" @click="loadShift">{{ shiftError }} · 点击重试</p>
          <p v-else>{{ shiftHint }}</p>
        </div>
        <button v-if="!isAdmin" type="button" class="shift-toggle" :class="{ active: shift.online }" :disabled="!shiftReady || shiftLoading" @click="changeShift">
          <i /><span>{{ shift.online ? '上班中' : '已下班' }}</span>
        </button>
        <div v-else class="on-duty-readonly"><strong>{{ shift.onDutyCs || 0 }}</strong><span>当班客服</span></div>
      </div>
    </section>

    <section class="mobile-card status-card">
      <div class="card-heading">
        <div><h2>今日待办</h2><p>{{ summary?.urgent || 0 }} 项紧急 · {{ summary?.unread || 0 }} 项未读</p></div>
        <button type="button" class="plain-action" @click="openTasks('')">全部 ›</button>
      </div>
      <div class="status-grid" :class="`count-${statusTiles.length}`">
        <button v-for="item in statusTiles" :key="item.key" type="button" :class="{ alert: item.alert }" @click="item.kind ? openTasks(item.kind) : go(item.route)">
          <strong>{{ item.count }}</strong>
          <span>{{ item.label }}</span>
          <i v-if="item.alert" />
        </button>
      </div>
      <div class="workbench-note">
        <span>{{ isAdmin ? '运营管理后台' : '客服工作台' }} · 数据自动更新</span>
        <button type="button" :class="{ spinning: loading || analyticsLoading }" :disabled="loading || analyticsLoading" aria-label="刷新工作台" @click="refreshAll">刷新</button>
      </div>
    </section>

    <section v-if="analyticsAllowed" class="mobile-card data-card">
      <div class="card-heading"><div><h2>今日数据</h2><p>按北京时间自然日汇总</p></div><button type="button" class="plain-action" @click="loadAnalytics">刷新</button></div>
      <LoadFeedback :loading="analyticsLoading" :error="analyticsError" label="运营数据" @retry="loadAnalytics" />
      <div class="daily-extra"><span>登录日活 <strong>{{ formatNumber(today.activeUsers) }}</strong></span><span>新增用户 <strong>{{ formatNumber(today.newUsers) }}</strong></span></div>
      <div class="today-grid">
        <div v-for="item in todayMetrics" :key="item.key"><strong :class="{ money: item.money }">{{ item.value }}</strong><span>{{ item.label }}</span></div>
      </div>
      <div class="presence-line">
        <button type="button" @click="openPlayers('ONLINE')"><strong>{{ presence.onlinePlayers || 0 }}</strong><span>在线打手</span></button>
        <button type="button" @click="openPlayers('OFFLINE')"><strong>{{ presence.offlinePlayers || 0 }}</strong><span>离线打手</span></button>
        <button type="button" @click="openPlayers('FROZEN')"><strong>{{ presence.frozenPlayers || 0 }}</strong><span>冻结打手</span></button>
        <div><strong>{{ presence.onDutyCs || 0 }}</strong><span>当班客服</span></div>
      </div>
    </section>

    <section v-if="productActions.length" class="mobile-card">
      <div class="card-heading"><div><h2>我的商品</h2><p>商品内容统一在此维护</p></div></div>
      <div class="icon-grid product-grid">
        <button v-for="action in productActions" :key="action.key" type="button" @click="go(action.route)">
          <span class="tool-icon"><el-icon :size="23"><component :is="action.icon" /></el-icon></span>
          <strong>{{ action.label }}</strong>
        </button>
      </div>
    </section>

    <section v-for="group in operationGroups" :key="group.key" class="mobile-card">
      <div class="card-heading"><div><h2>{{ group.title }}</h2><p>{{ group.caption }}</p></div></div>
      <div class="icon-grid">
        <button v-for="action in group.items" :key="action.key" type="button" @click="go(action.route)">
          <span class="tool-icon"><el-icon :size="23"><component :is="action.icon" /></el-icon><i v-if="action.badge" class="tool-badge">{{ action.badge }}</i></span>
          <strong>{{ action.label }}</strong>
          <small v-if="action.hint">{{ action.hint }}</small>
        </button>
      </div>
    </section>

    <div class="dashboard-footnote">
      <span>{{ isAdmin ? '管理员' : '客服' }} · {{ summary?.handledToday || 0 }} 项今日已处理</span>
      <button type="button" @click="openTasks('')">待办记录 ›</button>
    </div>

    <WorkbenchCenter v-model="centerOpen" :initial-kind="centerKind" />

    <el-drawer v-model="playersOpen" size="100%" :title="scopeTitle + '打手'" append-to-body>
      <div class="player-search">
        <el-input v-model="playerKeyword" clearable placeholder="搜索昵称或打手 ID" @keyup.enter="searchPlayers" @clear="searchPlayers" />
        <el-button type="primary" @click="searchPlayers">查询</el-button>
      </div>
      <LoadFeedback :loading="playersLoading" :error="playersError" label="打手列表" @retry="loadPlayers" />
      <div v-if="playerRows.length" class="player-list">
        <article v-for="row in playerRows" :key="row.id">
          <el-avatar :size="42" :src="row.avatar">{{ String(row.nickname || '打').slice(0, 1) }}</el-avatar>
          <div><strong>{{ row.nickname || ('打手 ' + row.id) }}</strong><span>{{ row.levelName || '未分等级' }} · 进行中 {{ row.activeOrderCount || 0 }}</span></div>
        </article>
      </div>
      <p v-else-if="!playersLoading" class="empty-copy">没有符合条件的打手</p>
      <div v-if="playerTotal > playerPageSize" class="player-pager">
        <el-button :disabled="playerPage <= 1 || playersLoading" @click="changePlayerPage(playerPage - 1)">上一页</el-button>
        <span>{{ playerPage }} / {{ Math.ceil(playerTotal / playerPageSize) }}</span>
        <el-button :disabled="playerPage * playerPageSize >= playerTotal || playersLoading" @click="changePlayerPage(playerPage + 1)">下一页</el-button>
      </div>
    </el-drawer>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getDashboardAnalytics, getDashboardPlayers } from '@/api/dashboard'
import LoadFeedback from '@/components/LoadFeedback.vue'
import WorkbenchCenter from '@/components/WorkbenchCenter.vue'
import { can } from '@/utils/staff-permissions'
import { platformFeatureAllowed } from '@/utils/platform-feature-visibility'
import { buildNavigation } from '@/utils/navigation'
import { buildMobileNavigationSections } from '@/utils/mobile-navigation'
import { useWorkbenchStore } from '@/stores/workbench'
import { useUserStore } from '@/stores/user'
import { getShiftStatus, toggleShift } from '@/api/shift'
import { CircleCheck, List, Picture } from '@element-plus/icons-vue'

const props = defineProps({
  stats: { type: Object, default: () => ({}) },
  isAdmin: Boolean,
  loading: Boolean,
  error: String
})
const emit = defineEmits(['refresh'])
const router = useRouter()
const workbench = useWorkbenchStore()
const userStore = useUserStore()
const summary = computed(() => workbench.summary)
const centerOpen = ref(false)
const centerKind = ref('')
const displayName = computed(() => userStore.nickname || (props.isAdmin ? '平台管理员' : '客服'))
const roleLabel = computed(() => props.isAdmin ? '管理员' : '客服')
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 11) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})
const shift = ref({ online: false, onDutyCs: 0, changedAt: null })
const shiftReady = ref(false)
const shiftLoading = ref(false)
const shiftError = ref('')
const shiftTitle = computed(() => {
  if (props.isAdmin) return '客服排班'
  if (!shiftReady.value) return '排班状态同步中'
  return shift.value.online ? '当前正在当班' : '当前已下班'
})
const shiftHint = computed(() => {
  if (props.isAdmin) return '管理员不参与客服排班，始终接收管理通知'
  return shift.value.online ? '当班期间接收订单、审核、投诉和退款通知' : '下班后暂停人工业务通知'
})

async function loadShift() {
  shiftLoading.value = true
  shiftError.value = ''
  try {
    const { data } = await getShiftStatus()
    shift.value = { ...shift.value, ...(data || {}) }
    shiftReady.value = true
  } catch (loadError) {
    shiftError.value = loadError?.message || '排班状态暂不可用'
  } finally {
    shiftLoading.value = false
  }
}

async function changeShift() {
  if (props.isAdmin || shiftLoading.value || !shiftReady.value) return
  shiftLoading.value = true
  shiftError.value = ''
  try {
    const { data } = await toggleShift(!shift.value.online)
    shift.value = { ...shift.value, ...(data || {}) }
    emit('refresh')
  } catch (loadError) {
    shiftError.value = loadError?.message || '无法切换上班状态'
  } finally {
    shiftLoading.value = false
  }
}

function firstNumber(...values) {
  for (const value of values) {
    const number = Number(value)
    if (Number.isFinite(number)) return number
  }
  return 0
}

function category(kind) {
  return (summary.value?.categories || []).find(item => item.kind === kind) || {}
}

const statusTiles = computed(() => {
  const definitions = [
    { key: 'assign', label: '待指派', count: firstNumber(props.stats.pendingAssign, category('ASSIGN').pending), route: '/order/list?status=PAID', alert: firstNumber(category('ASSIGN').urgent) > 0, allowed: can('order.read') },
    { key: 'progress', label: '服务中', count: firstNumber(props.stats.inProgress), route: '/order/list?status=IN_PROGRESS', allowed: can('order.read') },
    { key: 'refund', label: '退款审批', count: firstNumber(category('REFUND').pending, props.stats.pendingRefunds), kind: 'REFUND', alert: firstNumber(category('REFUND').urgent) > 0, allowed: can('order.refund') },
    { key: 'complaint', label: '投诉处理', count: firstNumber(props.stats.pendingComplaints, category('COMPLAINT').pending), route: '/complaint', alert: firstNumber(category('COMPLAINT').urgent) > 0, allowed: can('order.manage') },
    { key: 'withdraw', label: '提现审核', count: firstNumber(props.stats.pendingWithdraws, category('WITHDRAW').pending), route: '/withdraw', allowed: can('withdraw.review') },
    { key: 'audit', label: '待审核', count: ['ENROLLMENT', 'MATERIAL', 'IDENTITY', 'PRIVACY', 'RELAY', 'REPLACE'].reduce((sum, kind) => sum + firstNumber(category(kind).pending), 0), kind: '', allowed: can('player.manage') || can('media.review') || can('user.manage') }
  ]
  return definitions.filter(item => item.allowed).slice(0, 4)
})

const analyticsAllowed = computed(() => can('order.read'))
const analytics = ref(null)
const analyticsLoading = ref(false)
const analyticsError = ref('')
let analyticsRequest = 0
const today = computed(() => analytics.value?.today || {})
const presence = computed(() => analytics.value?.presence || {})
const todayMetrics = computed(() => [
  { key: 'visitors', label: '访客数', value: formatNumber(today.value.uniqueVisitors) },
  { key: 'orders', label: '有效订单', value: formatNumber(today.value.effectiveOrders) },
  { key: 'revenue', label: '已收款', value: formatMoney(today.value.revenue), money: true }
])

async function loadAnalytics() {
  if (!analyticsAllowed.value) return
  const requestId = ++analyticsRequest
  analyticsLoading.value = true
  analyticsError.value = ''
  try {
    const response = await getDashboardAnalytics({ silent: true, loading: false })
    if (requestId === analyticsRequest) analytics.value = response.data || null
  } catch (loadError) {
    if (requestId === analyticsRequest) analyticsError.value = loadError?.message || '运营数据暂不可用'
  } finally {
    if (requestId === analyticsRequest) analyticsLoading.value = false
  }
}

const productActions = computed(() => {
  if (!props.isAdmin || !can('product.manage')) return []
  return [
    { key: 'products', label: '商品管理', route: '/product/list', icon: List },
    { key: 'categories', label: '分类管理', route: '/product/category', icon: Picture },
    { key: 'new-product', label: '添加商品', route: '/product/create', icon: CircleCheck }
  ]
})

const operationGroups = computed(() => {
  const role = props.isAdmin ? 'admin' : 'cs'
  const groups = buildNavigation(router.options.routes, role, can, platformFeatureAllowed)
  return buildMobileNavigationSections(groups, [
    ...statusTiles.value.map(item => item.route),
    ...productActions.value.map(item => item.route)
  ])
})

function formatNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toLocaleString('zh-CN', { maximumFractionDigits: 0 }) : '—'
}

function formatMoney(value) {
  const number = Number(value)
  return Number.isFinite(number) ? `¥${number.toFixed(2)}` : '¥—'
}

function go(route) {
  router.push(route)
}

function openTasks(kind) {
  centerKind.value = kind
  centerOpen.value = true
}

function refreshAll() {
  emit('refresh')
  workbench.refresh()
  loadAnalytics()
  loadShift()
}

const playersOpen = ref(false)
const playerScope = ref('ONLINE')
const scopeTitle = computed(() => ({ ONLINE: '在线', OFFLINE: '离线', FROZEN: '冻结' }[playerScope.value] || '全部'))
const playerKeyword = ref('')
const playerRows = ref([])
const playerTotal = ref(0)
const playerPage = ref(1)
const playerPageSize = 20
const playersLoading = ref(false)
const playersError = ref('')
let playerRequest = 0

function openPlayers(scope) {
  playerScope.value = scope
  playerKeyword.value = ''
  playerPage.value = 1
  playersOpen.value = true
  loadPlayers()
}

async function loadPlayers() {
  const requestId = ++playerRequest
  playersLoading.value = true
  playersError.value = ''
  try {
    const response = await getDashboardPlayers({ scope: playerScope.value, page: playerPage.value, pageSize: playerPageSize, keyword: playerKeyword.value.trim() }, { silent: true, loading: false })
    if (requestId !== playerRequest) return
    playerRows.value = response.data?.rows || []
    playerTotal.value = Number(response.data?.total || 0)
  } catch (loadError) {
    if (requestId === playerRequest) playersError.value = loadError?.message || '打手列表暂不可用'
  } finally {
    if (requestId === playerRequest) playersLoading.value = false
  }
}

function searchPlayers() {
  playerPage.value = 1
  loadPlayers()
}

function changePlayerPage(page) {
  playerPage.value = page
  loadPlayers()
}

onMounted(() => { loadAnalytics(); loadShift() })
</script>

<style lang="scss" scoped>
.mobile-dashboard { display:grid; gap:10px; min-width:0; }
.mobile-hero { min-width:0; overflow:hidden; padding:19px; border-radius:18px; color:#fff; background:radial-gradient(circle at 88% 4%,rgba(111,137,171,.28),transparent 34%),linear-gradient(145deg,#171a1f 0%,#242a32 100%); box-shadow:0 12px 30px rgba(22,27,34,.14); }
.hero-top { display:flex; align-items:flex-start; justify-content:space-between; gap:14px; }
.hero-person { display:flex; align-items:center; gap:12px; min-width:0; }
.hero-person > div { display:grid; min-width:0; gap:3px; }
.hero-person span { color:#9ea9b9; font-size:9px; font-weight:700; letter-spacing:.08em; }
.hero-person strong { overflow:hidden; color:#fff; font-size:21px; line-height:1.2; text-overflow:ellipsis; white-space:nowrap; }
.hero-person small { color:#aab3bf; font-size:10px; }
.hero-refresh { width:34px; height:34px; flex:none; border:1px solid rgba(255,255,255,.16); border-radius:50%; background:rgba(255,255,255,.06); color:#fff; font-size:19px; cursor:pointer; }
.hero-refresh.spinning { animation:hero-spin .9s linear infinite; }
@keyframes hero-spin { to { transform:rotate(360deg); } }
.hero-shift { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-top:17px; padding-top:14px; border-top:1px solid rgba(255,255,255,.1); }
.hero-shift-copy { min-width:0; }
.hero-shift-copy > div { display:flex; align-items:center; gap:7px; }
.hero-shift-copy i { width:7px; height:7px; flex:none; border-radius:50%; background:#b3bbc6; }
.hero-shift.online .hero-shift-copy i { background:#73c895; box-shadow:0 0 0 4px rgba(115,200,149,.12); }
.hero-shift-copy strong { color:#edf1f5; font-size:12px; }
.hero-shift-copy small { color:#8290a1; font-size:9px; }
.hero-shift-copy p { margin:5px 0 0; color:#a5aeba; font-size:10px; line-height:1.5; }
.shift-toggle { display:flex; align-items:center; gap:7px; flex:none; min-height:34px; padding:5px 10px 5px 6px; border:1px solid rgba(255,255,255,.14); border-radius:999px; background:rgba(255,255,255,.06); color:#c5cbd3; cursor:pointer; }
.shift-toggle i { width:24px; height:24px; border-radius:50%; background:#59616c; transition:.2s; }
.shift-toggle.active { color:#d9f2e2; background:rgba(63,142,96,.2); }
.shift-toggle.active i { background:#66bb89; }
.shift-toggle span { font-size:10px; white-space:nowrap; }
.on-duty-readonly { flex:none; text-align:right; }
.on-duty-readonly strong,.on-duty-readonly span { display:block; }
.on-duty-readonly strong { color:#fff; font-size:22px; }
.on-duty-readonly span { margin-top:2px; color:#9ca7b5; font-size:9px; }
.mobile-card { min-width:0; padding:18px; border:1px solid #eceef1; border-radius:16px; background:#fff; box-shadow:0 6px 20px rgba(24,28,34,.025); }
.card-heading { display:flex; align-items:flex-start; justify-content:space-between; gap:14px; margin-bottom:16px; }
.card-heading h2 { margin:0; color:#181b1f; font-size:19px; line-height:1.25; font-weight:800; }
.card-heading p { margin:5px 0 0; color:#989da4; font-size:11px; }
.plain-action { flex:0 0 auto; padding:2px 0; border:0; background:transparent; color:#7d838b; font-size:11px; cursor:pointer; }
.status-grid { display:grid; border-top:1px solid #eff1f3; }
.status-grid.count-1 { grid-template-columns:1fr; }
.status-grid.count-2 { grid-template-columns:repeat(2,minmax(0,1fr)); }
.status-grid.count-3 { grid-template-columns:repeat(3,minmax(0,1fr)); }
.status-grid.count-4 { grid-template-columns:repeat(4,minmax(0,1fr)); }
.status-grid button { position:relative; min-width:0; min-height:78px; padding:14px 3px 10px; border:0; border-right:1px solid #eff1f3; background:#fff; color:inherit; text-align:center; cursor:pointer; }
.status-grid button:last-child { border-right:0; }
.status-grid button:active { background:#f7f8f9; }
.status-grid strong { display:block; color:#171a1e; font-size:27px; line-height:1; font-weight:800; font-variant-numeric:tabular-nums; }
.status-grid span { display:block; margin-top:8px; color:#747a82; font-size:11px; white-space:nowrap; }
.status-grid i { position:absolute; top:10px; right:calc(50% - 22px); width:6px; height:6px; border-radius:50%; background:#c7535c; }
.status-grid button.alert strong { color:#b74750; }
.workbench-note { display:flex; align-items:center; justify-content:space-between; gap:10px; margin-top:10px; padding-top:11px; border-top:1px solid #f1f2f4; color:#9a9fa6; font-size:10px; }
.workbench-note button { padding:2px 0; border:0; background:transparent; color:#797f87; font-size:10px; cursor:pointer; }
.workbench-note button.spinning { opacity:.5; }
.today-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); border-top:1px solid #eff1f3; }
.daily-extra { display:flex; gap:18px; margin:-3px 0 12px; color:#969ba2; font-size:10px; }
.daily-extra strong { margin-left:4px; color:#5f656d; font-weight:700; }
.today-grid > div { min-width:0; padding:16px 6px 13px; text-align:center; }
.today-grid > div + div { border-left:1px solid #eff1f3; }
.today-grid strong { display:block; color:#171a1e; font-size:25px; line-height:1.1; font-weight:800; font-variant-numeric:tabular-nums; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.today-grid strong.money { font-size:19px; letter-spacing:-.03em; }
.today-grid span { display:block; margin-top:7px; color:#92979e; font-size:10px; }
.presence-line { display:grid; grid-template-columns:repeat(4,minmax(0,1fr)); gap:0; margin-top:4px; padding:10px 0 0; border-top:1px solid #eff1f3; }
.presence-line button, .presence-line > div { min-width:0; padding:4px 2px; border:0; background:transparent; color:inherit; text-align:center; }
.presence-line button { cursor:pointer; }
.presence-line button:active { opacity:.6; }
.presence-line strong { display:block; color:#363a40; font-size:15px; font-weight:750; }
.presence-line span { display:block; margin-top:3px; color:#a0a5ac; font-size:9px; white-space:nowrap; }
.icon-grid { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:19px 8px; }
.icon-grid button { min-width:0; padding:2px; border:0; background:transparent; color:inherit; text-align:center; cursor:pointer; }
.icon-grid button:active { opacity:.58; }
.tool-icon { position:relative; display:flex; align-items:center; justify-content:center; width:48px; height:48px; margin:0 auto 8px; border-radius:15px; background:#f4f5f6; color:#33383f; }
.tool-badge { position:absolute; top:-5px; right:-6px; min-width:17px; height:17px; padding:0 4px; border-radius:999px; background:#c85059; color:#fff; font:10px/17px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif; text-align:center; box-sizing:border-box; }
.icon-grid strong { display:block; color:#34383e; font-size:12px; line-height:1.35; font-weight:650; }
.icon-grid small { display:block; margin-top:2px; color:#a2a7ad; font-size:9px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.product-grid .tool-icon { background:#eef5ff; color:#4d78b5; }
.dashboard-footnote { display:flex; align-items:center; justify-content:space-between; gap:10px; padding:12px 4px 3px; color:#a0a5ac; font-size:10px; }
.dashboard-footnote button { border:0; background:transparent; color:#7e848c; font-size:10px; cursor:pointer; }
@media(max-width:430px) {
  .icon-grid { grid-template-columns:repeat(2,minmax(0,1fr)); gap:18px 10px; }
  .icon-grid button { padding:4px 2px; }
}
.player-search { display:flex; gap:8px; margin-bottom:12px; }
.player-search .el-input { flex:1; }
.player-list { display:grid; gap:8px; }
.player-list article { display:flex; align-items:center; gap:11px; min-width:0; padding:11px; border:1px solid #eceef1; border-radius:12px; background:#fff; }
.player-list article > div { min-width:0; display:grid; gap:4px; }
.player-list strong { color:#272b30; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.player-list span { color:#8a9097; font-size:12px; }
.player-pager { display:flex; align-items:center; justify-content:center; gap:12px; margin-top:16px; color:#747a82; font-size:12px; }
.empty-copy { margin:8px 0; color:#979ca3; font-size:12px; }
@media(max-width:360px) { .mobile-card { padding:15px; } .status-grid strong { font-size:23px; } .today-grid strong { font-size:21px; } .today-grid strong.money { font-size:15px; letter-spacing:-.05em; } .icon-grid { gap:17px 5px; } .tool-icon { width:44px; height:44px; } }
@media(prefers-reduced-motion:reduce) { .status-grid button:active, .icon-grid button:active { opacity:1; } }
</style>
