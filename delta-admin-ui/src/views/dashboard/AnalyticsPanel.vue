<template>
  <section class="analytics-panel" aria-labelledby="analytics-title" :aria-busy="loading || playerLoading">
    <div class="analytics-heading">
      <div>
        <p class="eyebrow">OPERATIONS ANALYTICS</p>
        <h3 id="analytics-title">运营数据分析</h3>
        <p>今日数据与昨日对比，点击在线、离线或冻结人数可查看具体打手。</p>
      </div>
      <el-button :loading="loading" @click="refresh">刷新分析</el-button>
    </div>

    <LoadFeedback :loading="loading" :error="error" label="运营分析" @retry="refresh" />

    <div v-if="analytics" class="presence-row">
      <button type="button" class="presence-card" @click="openPlayers('ONLINE')">
        <span>在线打手</span><strong>{{ presence.onlinePlayers }}</strong><small>查看具体打手 ›</small>
      </button>
      <button type="button" class="presence-card" @click="openPlayers('OFFLINE')">
        <span>离线打手</span><strong>{{ presence.offlinePlayers }}</strong><small>查看具体打手 ›</small>
      </button>
      <button type="button" class="presence-card muted" @click="openPlayers('FROZEN')">
        <span>冻结打手</span><strong>{{ presence.frozenPlayers }}</strong><small>查看具体打手 ›</small>
      </button>
      <div class="presence-card static">
        <span>当班客服</span><strong>{{ presence.onDutyCs }}</strong><small>当前上班状态</small>
      </div>
    </div>

    <div v-if="analytics" class="analytics-columns">
      <el-card shadow="never" class="analytics-card trend-card">
        <template #header>
          <div class="card-heading">
            <div><strong>访问与订单趋势</strong><span>按北京时间自然日汇总</span></div>
            <el-radio-group v-model="trendDays" size="small">
              <el-radio-button :value="7">近 7 天</el-radio-button>
              <el-radio-button :value="30">近 30 天</el-radio-button>
            </el-radio-group>
          </div>
        </template>
        <el-table :data="trendRows" size="small" stripe max-height="420">
          <el-table-column prop="date" label="日期" width="112" />
          <el-table-column prop="uniqueVisitors" label="访客" width="72" />
          <el-table-column prop="activeUsers" label="日活" width="72" />
          <el-table-column prop="orders" label="订单" width="72" />
          <el-table-column prop="effectiveOrders" label="有效" width="72" />
          <el-table-column label="成交额" min-width="110">
            <template #default="{ row }">{{ formatMoney(row.revenue) }}</template>
          </el-table-column>
          <el-table-column label="订单趋势" min-width="110">
            <template #default="{ row }">
              <div class="bar-track"><i :style="{ width: metricBar(row.orders, maxTrendOrders) }" /></div>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-card shadow="never" class="analytics-card funnel-card">
        <template #header>
          <div class="card-heading"><div><strong>今日订单漏斗</strong><span>从访问到完成</span></div></div>
        </template>
        <div class="funnel-list">
          <div v-for="stage in funnel" :key="stage.stage" class="funnel-stage">
            <div class="funnel-copy">
              <span>{{ stage.label }}</span>
              <strong>{{ stage.value }}</strong>
            </div>
            <div class="funnel-track"><i :style="{ width: Math.min(100, Math.max(stage.rateFromVisits, stage.value ? 3 : 0)) + '%' }" /></div>
            <small>访问转化 {{ stage.rateFromVisits.toFixed(1) }}% · 上一步转化 {{ stage.rateFromPrevious.toFixed(1) }}%</small>
          </div>
        </div>
      </el-card>
    </div>

    <el-drawer v-model="drawerOpen" :size="drawerSize" :title="scopeTitle + '打手'">
      <div class="drawer-toolbar">
        <el-input v-model="keyword" clearable placeholder="搜索昵称或打手 ID" @keyup.enter="searchPlayers" @clear="searchPlayers" />
        <el-button type="primary" @click="searchPlayers">查询</el-button>
      </div>
      <LoadFeedback :loading="playerLoading" :error="playerError" label="打手列表" @retry="loadPlayers" />
      <el-table :data="playerRows" size="small" stripe>
        <el-table-column label="打手" min-width="180">
          <template #default="{ row }">
            <div class="player-cell">
              <el-avatar :size="34" :src="row.avatar">{{ String(row.nickname || '打').slice(0, 1) }}</el-avatar>
              <div><strong>{{ row.nickname || ('打手 ' + row.id) }}</strong><span>ID {{ row.id }}</span></div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="levelName" label="等级" min-width="90" />
        <el-table-column prop="activeOrderCount" label="进行中订单" width="105" />
        <el-table-column label="最后在线" min-width="155">
          <template #default="{ row }">{{ row.lastOnlineAt || '—' }}</template>
        </el-table-column>
      </el-table>
      <el-pagination
        v-if="playerTotal > playerPageSize"
        class="player-pagination"
        layout="prev, pager, next"
        :total="playerTotal"
        :page-size="playerPageSize"
        :current-page="playerPage"
        @current-change="changePage"
      />
    </el-drawer>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import LoadFeedback from '@/components/LoadFeedback.vue'
import { useLoadTask } from '@/composables/useLoadTask'
import { getDashboardAnalytics, getDashboardPlayers } from '@/api/dashboard'
import { useResponsive } from '@/utils/responsive'
import {
  compareMetric, comparisonText, formatMoney, funnelRows, maxMetric, metricBar, selectedTrend
} from '@/utils/dashboard-analytics'

const { isNarrow } = useResponsive()
const trendDays = ref(7)
const { data: analytics, loading, error, run: fetchAnalytics } = useLoadTask(
  async () => (await getDashboardAnalytics({ silent: true, loading: false })).data || null,
  null
)

const today = computed(() => analytics.value?.today || {})
const yesterday = computed(() => analytics.value?.yesterday || {})
const comparison = computed(() => analytics.value?.comparison || {})
const presence = computed(() => analytics.value?.presence || {
  onlinePlayers: 0, offlinePlayers: 0, frozenPlayers: 0, onDutyCs: 0
})
const trendRows = computed(() => selectedTrend(analytics.value, trendDays.value))
const maxTrendOrders = computed(() => maxMetric(trendRows.value, 'orders'))
const funnel = computed(() => funnelRows(analytics.value?.funnel))

const metricCards = computed(() => {
  const definitions = [
    ['uniqueVisitors', '今日访客', value => value],
    ['activeUsers', '登录日活', value => value],
    ['newUsers', '新增用户', value => value],
    ['orders', '今日订单', value => value],
    ['effectiveOrders', '有效订单', value => value],
    ['revenue', '今日成交额', formatMoney],
    ['refunds', '今日退款', formatMoney],
    ['completedOrders', '完成订单', value => value]
  ]
  return definitions.map(([key, label, format]) => {
    const itemComparison = comparison.value[key] || compareMetric(today.value[key], yesterday.value[key])
    return {
      key,
      label,
      value: format(today.value[key] ?? 0),
      previous: format(yesterday.value[key] ?? 0),
      comparison: itemComparison
    }
  })
})

const drawerOpen = ref(false)
const drawerScope = ref('ONLINE')
const keyword = ref('')
const playerLoading = ref(false)
const playerError = ref('')
const playerRows = ref([])
const playerTotal = ref(0)
const playerPage = ref(1)
const playerPageSize = 20
let playerRequestVersion = 0
const drawerSize = computed(() => isNarrow.value ? '100%' : '660px')
const scopeTitle = computed(() => ({ ONLINE: '在线', OFFLINE: '离线', FROZEN: '冻结' }[drawerScope.value] || '全部'))

function comparisonClass(item) {
  return item?.direction === 'UP' ? 'up' : item?.direction === 'DOWN' ? 'down' : 'flat'
}

function refresh() {
  fetchAnalytics()
}

function openPlayers(scope) {
  drawerScope.value = scope
  keyword.value = ''
  playerPage.value = 1
  drawerOpen.value = true
  loadPlayers()
}

async function loadPlayers() {
  const version = ++playerRequestVersion
  playerLoading.value = true
  playerError.value = ''
  try {
    const response = await getDashboardPlayers({
      scope: drawerScope.value,
      page: playerPage.value,
      pageSize: playerPageSize,
      keyword: keyword.value.trim()
    }, { silent: true, loading: false })
    if (version !== playerRequestVersion) return
    playerRows.value = response.data?.rows || []
    playerTotal.value = response.data?.total || 0
  } catch (error) {
    if (version === playerRequestVersion) playerError.value = error?.message || '加载失败，请稍后重试'
  } finally {
    if (version === playerRequestVersion) playerLoading.value = false
  }
}

function searchPlayers() {
  playerPage.value = 1
  loadPlayers()
}

function changePage(page) {
  playerPage.value = page
  loadPlayers()
}

onMounted(refresh)
</script>

<style scoped>
.analytics-panel { margin: 18px 0 20px; padding: 20px; border: 1px solid #e3e6eb; border-radius: 12px; background: #fff; }
.analytics-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
.analytics-heading h3 { margin: 2px 0 5px; font-size: 20px; color: #17191c; }
.analytics-heading p { margin: 0; color: #68707c; font-size: 13px; line-height: 1.55; }
.analytics-heading .eyebrow { margin-bottom: 2px; color: #8b919a; font-size: 10px; font-weight: 800; letter-spacing: .12em; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-top: 16px; }
.metric-card { min-width: 0; padding: 14px; border-radius: 10px; background: #f6f7f9; border: 1px solid #eceef1; }
.metric-kicker { color: #68707c; font-size: 12px; }
.metric-card > strong { display: block; margin: 7px 0 5px; color: #17191c; font-size: clamp(22px, 2.4vw, 30px); line-height: 1.1; }
.metric-foot { display: flex; justify-content: space-between; gap: 6px; color: #868c95; font-size: 11px; }
.metric-foot b { white-space: nowrap; }
.metric-foot .up { color: #b63842; }
.metric-foot .down { color: #287653; }
.metric-foot .flat { color: #757c85; }
.presence-row { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-top: 12px; }
.presence-card { display: grid; text-align: left; gap: 3px; padding: 13px 14px; border: 1px solid #e3e6eb; border-radius: 10px; background: #fff; color: inherit; cursor: pointer; transition: border-color .18s ease, transform .18s ease; }
.presence-card:hover { transform: translateY(-1px); border-color: #bfc4cb; }
.presence-card.static { cursor: default; }
.presence-card.static:hover { transform: none; border-color: #e3e6eb; }
.presence-card span { color: #68707c; font-size: 12px; }
.presence-card strong { color: #17191c; font-size: 24px; }
.presence-card small { color: #8b919a; font-size: 11px; }
.presence-card.muted { background: #fafafa; }
.analytics-columns { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(280px, .75fr); gap: 12px; margin-top: 12px; }
.analytics-card { border-radius: 10px; }
.card-heading { display: flex; justify-content: space-between; align-items: center; gap: 10px; }
.card-heading > div { display: grid; gap: 3px; }
.card-heading strong { color: #17191c; font-size: 15px; }
.card-heading span { color: #858c95; font-size: 11px; font-weight: 400; }
.bar-track { height: 7px; border-radius: 999px; background: #edf0f2; overflow: hidden; }
.bar-track i { display: block; height: 100%; border-radius: inherit; background: #272b30; }
.funnel-list { display: grid; gap: 15px; }
.funnel-copy { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.funnel-copy span { color: #5f6670; font-size: 13px; }
.funnel-copy strong { color: #17191c; font-size: 22px; }
.funnel-track { height: 10px; margin: 7px 0 5px; border-radius: 999px; background: #eef0f2; overflow: hidden; }
.funnel-track i { display: block; height: 100%; min-width: 3px; border-radius: inherit; background: linear-gradient(90deg, #727982, #202429); }
.funnel-stage small { color: #858c95; font-size: 11px; }
.drawer-toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.player-cell { display: flex; align-items: center; gap: 9px; min-width: 0; }
.player-cell > div { min-width: 0; display: grid; }
.player-cell strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #24272b; }
.player-cell span { color: #858c95; font-size: 11px; }
.player-pagination { justify-content: center; margin-top: 16px; }
@media (max-width: 1100px) {
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .analytics-columns { grid-template-columns: 1fr; }
}
@media (max-width: 680px) {
  .analytics-panel { padding: 14px; }
  .analytics-heading { flex-direction: column; }
  .presence-row { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .metric-grid { grid-template-columns: 1fr; }
  .card-heading { align-items: flex-start; flex-direction: column; }
}
</style>
