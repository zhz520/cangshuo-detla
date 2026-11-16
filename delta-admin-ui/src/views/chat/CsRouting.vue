<template>
  <div class="page-container cs-routing-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <div class="header-copy">
            <span>客服分配</span>
            <small>商品、分类和默认规则决定订单、售后与客服会话归谁处理</small>
          </div>
          <el-button type="primary" @click="openRule()">新增分配规则</el-button>
        </div>
      </template>

      <el-tabs v-model="activeTab">
        <el-tab-pane label="分配规则" name="rules">
          <el-alert
            class="rule-note"
            type="info"
            :closable="false"
            show-icon
            title="一条规则可以同时绑定多个商品或分类，分类支持同时勾选主分类和子分类；商品规则优先于分类规则，分类从最末级向上匹配。"
            description="规则目标可以是单个客服，也可以是客服组。客服组只从当前上班成员中选人，并优先分配给进行中订单较少的成员；组内无人上班时订单进入公共池。"
          />
          <el-table :data="rules" v-loading="loading" stripe>
            <el-table-column label="匹配方式" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.matchType === 'DEFAULT' ? 'info' : 'primary'">
                  {{ matchTypeLabel(row.matchType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="targetName" label="匹配对象" min-width="260">
              <template #default="{ row }">
                <div v-if="row.targets?.length" class="target-tags" :title="row.targetName">
                  <el-tag v-for="target in row.targets.slice(0, 4)" :key="target.id" size="small" effect="plain">
                    {{ target.name || target.id }}
                  </el-tag>
                  <el-tag v-if="row.targets.length > 4" size="small" effect="plain" type="info">
                    +{{ row.targets.length - 4 }}
                  </el-tag>
                </div>
                <span v-else class="muted">{{ row.matchType === 'DEFAULT' ? '未匹配商品/分类' : '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="归属" min-width="190">
              <template #default="{ row }">
                <el-tag size="small" :type="row.assigneeType === 'GROUP' ? 'success' : 'info'" style="margin-right:6px">
                  {{ row.assigneeType === 'GROUP' ? '客服组' : '客服' }}
                </el-tag>
                <span>{{ row.assigneeName || '-' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="启用" width="90">
              <template #default="{ row }">
                <el-switch :model-value="row.enabled === 1" @change="toggleRule(row)" />
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
            <el-table-column prop="updatedAt" label="更新时间" width="165" />
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openRule(row)">编辑</el-button>
                <el-button link type="danger" @click="removeRule(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!loading && !rules.length" description="还没有分配规则，当前所有新订单都会进入公共池" :image-size="80" />
        </el-tab-pane>

        <el-tab-pane label="客服组" name="groups">
          <div class="group-toolbar">
            <span>客服组用于排班场景：组内只从上班中的客服里分配，避免把订单压给已下班的人。</span>
            <el-button type="primary" plain @click="openGroup()">新建客服组</el-button>
          </div>
          <el-table :data="groups" v-loading="loading" stripe>
            <el-table-column prop="name" label="客服组" min-width="150" />
            <el-table-column label="组长" width="120">
              <template #default="{ row }">{{ row.leaderName || '-' }}</template>
            </el-table-column>
            <el-table-column label="成员" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">{{ memberNames(row) }}</template>
            </el-table-column>
            <el-table-column label="上班中" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="onlineCount(row) > 0 ? 'success' : 'info'">
                  {{ onlineCount(row) }}/{{ row.memberCount || 0 }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.enabled === 1 ? 'success' : 'info'">{{ row.enabled === 1 ? '启用' : '停用' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="remark" label="备注" min-width="150" show-overflow-tooltip />
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openGroup(row)">编辑</el-button>
                <el-button link type="danger" @click="removeGroup(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="评分统计" name="ratings">
          <div class="rating-summary" v-loading="ratingLoading">
            <div class="rating-summary-card">
              <span>平均评分</span>
              <strong>{{ Number(ratingSummary.average || 0).toFixed(2) }}</strong>
              <small>共 {{ ratingSummary.total || 0 }} 次评价</small>
            </div>
            <div class="rating-summary-card">
              <span>好评率</span>
              <strong>{{ Number(ratingSummary.positiveRate || 0).toFixed(1) }}%</strong>
              <small>4 星及以上</small>
            </div>
            <div class="rating-summary-card">
              <span>五星评价</span>
              <strong>{{ Number(ratingSummary.fiveStarRate || 0).toFixed(1) }}%</strong>
              <small>用户主动评分</small>
            </div>
            <div class="rating-summary-card">
              <span>低分评价</span>
              <strong>{{ ratingSummary.lowScoreCount || 0 }}</strong>
              <small>1–2 星，建议跟进</small>
            </div>
          </div>
          <el-table :data="ratingStaff" stripe>
            <el-table-column label="客服" min-width="170">
              <template #default="{ row }">{{ row.name || ('客服 ' + row.id) }}</template>
            </el-table-column>
            <el-table-column label="平均分" width="120">
              <template #default="{ row }">{{ Number(row.avgRating || 0).toFixed(2) }}</template>
            </el-table-column>
            <el-table-column prop="ratingCount" label="评价数" width="100" />
            <el-table-column prop="fiveStarCount" label="五星" width="90" />
            <el-table-column prop="positiveCount" label="好评" width="90" />
            <el-table-column prop="lowScoreCount" label="低分" width="90" />
            <el-table-column label="满意度" min-width="150">
              <template #default="{ row }">
                <el-progress
                  :percentage="Number(row.ratingCount) ? Math.round(Number(row.positiveCount || 0) / Number(row.ratingCount) * 100) : 0"
                  :stroke-width="7"
                  :show-text="false"
                />
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!ratingLoading && !ratingStaff.length" description="还没有用户评价，结束客服会话后可邀请用户评分" :image-size="80" />

          <div class="rating-recent-title">最近评价</div>
          <el-table :data="ratingRecent" stripe>
            <el-table-column prop="ratedAt" label="评价时间" width="180" />
            <el-table-column prop="csName" label="客服" width="130" />
            <el-table-column label="评分" width="120">
              <template #default="{ row }">
                <span class="rating-stars">{{ '★'.repeat(Number(row.rating || 0)) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="comment" label="评价内容" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">{{ row.comment || '未填写文字评价' }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-dialog v-model="ruleVisible" :title="ruleForm.id ? '编辑分配规则' : '新增分配规则'" width="560px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="匹配方式">
          <el-radio-group v-model="ruleForm.matchType" @change="ruleForm.targetIds = []; ruleForm.targetId = null">
            <el-radio-button label="PRODUCT">指定商品（可多选）</el-radio-button>
            <el-radio-button label="CATEGORY">商品分类（可多选）</el-radio-button>
            <el-radio-button label="DEFAULT">默认规则</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="ruleForm.matchType === 'PRODUCT'" label="选择商品" required>
          <el-select
            v-model="ruleForm.targetIds"
            multiple collapse-tags collapse-tags-tooltip
            filterable remote clearable
            :remote-method="searchProducts"
            :loading="productLoading"
            placeholder="输入商品名搜索"
            style="width:100%"
          >
            <el-option v-for="p in productOptions" :key="p.id" :label="p.name + '（ID ' + p.id + '）'" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="ruleForm.matchType === 'CATEGORY'" label="选择分类" required>
          <el-tree-select
            v-model="ruleForm.targetIds"
            :data="categoryTree"
            node-key="id"
            :props="{ label: 'name', children: 'children' }"
            multiple
            show-checkbox
            check-strictly
            default-expand-all
            collapse-tags
            collapse-tags-tooltip
            placeholder="可同时选择主分类和子分类"
            style="width:100%"
          />
        </el-form-item>
        <el-form-item label="分配给" required>
          <el-radio-group v-model="ruleForm.assigneeType" @change="ruleForm.assigneeId = null">
            <el-radio-button label="GROUP">客服组</el-radio-button>
            <el-radio-button label="CS">指定客服</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="ruleForm.assigneeType === 'GROUP'" label="客服组" required>
          <el-select v-model="ruleForm.assigneeId" placeholder="选择客服组" style="width:100%">
            <el-option
              v-for="g in enabledGroups"
              :key="g.id"
              :label="g.name + '（上班 ' + onlineCount(g) + '/' + (g.memberCount || 0) + '）'"
              :value="g.id"
            />
          </el-select>
          <div v-if="!enabledGroups.length" class="form-tip">还没有启用的客服组，请先到“客服组”标签创建。</div>
        </el-form-item>
        <el-form-item v-else label="指定客服" required>
          <el-select v-model="ruleForm.assigneeId" placeholder="选择客服" filterable style="width:100%">
            <el-option v-for="s in staff" :key="s.id" :label="staffLabel(s)" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="ruleForm.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="ruleForm.remark" maxlength="200" show-word-limit placeholder="可选，例如：A类商品固定由晚班组处理" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ruleVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveRule">保存规则</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="groupVisible" :title="groupForm.id ? '编辑客服组' : '新建客服组'" width="560px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="客服组名称" required>
          <el-input v-model="groupForm.name" maxlength="64" placeholder="例如：白班组、晚班组、售后组" />
        </el-form-item>
        <el-form-item label="组长">
          <el-select v-model="groupForm.leaderId" clearable placeholder="可选" style="width:100%">
            <el-option v-for="s in selectedMembers" :key="s.id" :label="staffLabel(s)" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="组内客服" required>
          <el-select v-model="groupForm.memberIds" multiple filterable placeholder="选择至少一名客服" style="width:100%">
            <el-option v-for="s in staff" :key="s.id" :label="staffLabel(s)" :value="s.id" />
          </el-select>
          <div class="form-tip">只有当前“上班中”的成员会参与新订单分配；组内无人上班时订单进入公共池。</div>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="groupForm.enabled" :active-value="1" :inactive-value="0" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="groupForm.remark" maxlength="200" show-word-limit placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="groupVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveGroup">保存客服组</el-button>
      </template>
    </el-dialog>
  </div>
</template>
<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getProductList, getCategoryTree } from '@/api/product'
import {
  csRoutingStaff, csRoutingRules, saveCsRoutingRule, deleteCsRoutingRule,
  csRoutingGroups, saveCsRoutingGroup, deleteCsRoutingGroup, csRatingStats
} from '@/api/cs-routing'

const activeTab = ref('rules')
const loading = ref(false)
const saving = ref(false)
const staff = ref([])
const rules = ref([])
const groups = ref([])
const categoryTree = ref([])
const productOptions = ref([])
const productLoading = ref(false)
const ruleVisible = ref(false)
const groupVisible = ref(false)
const ratingLoading = ref(false)
const ratingStats = ref({})
const ratingSummary = computed(() => ratingStats.value.summary || {})
const ratingStaff = computed(() => ratingStats.value.staff || [])
const ratingRecent = computed(() => ratingStats.value.recent || [])

const ruleForm = reactive({
  id: null, matchType: 'PRODUCT', targetId: null, targetIds: [],
  assigneeType: 'GROUP', assigneeId: null, enabled: 1, remark: ''
})
const groupForm = reactive({
  id: null, name: '', leaderId: null, memberIds: [], enabled: 1, remark: ''
})

const staffMap = computed(() => Object.fromEntries(staff.value.map(item => [item.id, item])))
const enabledGroups = computed(() => groups.value.filter(item => item.enabled === 1))
const selectedMembers = computed(() => groupForm.memberIds.map(id => staffMap.value[id]).filter(Boolean))

function matchTypeLabel(value) {
  return ({ PRODUCT: '指定商品', CATEGORY: '商品分类', DEFAULT: '默认规则' })[value] || value
}
function staffLabel(item) {
  const name = item.nickname || item.username || ('客服 ' + item.id)
  return name + (Number(item.online) === 1 ? ' · 上班中' : ' · 已下班')
}
function memberIds(row) {
  return String(row.memberIds || '').split(',').map(value => Number(value)).filter(Boolean)
}
function memberNames(row) {
  const names = memberIds(row).map(id => staffMap.value[id]).filter(Boolean).map(item => item.nickname || item.username)
  if (!names.length) return '-'
  return names.length > 3 ? names.slice(0, 3).join('、') + ' 等' + names.length + '人' : names.join('、')
}
function onlineCount(row) {
  return memberIds(row).filter(id => Number(staffMap.value[id]?.online) === 1).length
}

async function loadAll() {
  loading.value = true
  try {
    const [staffRes, ruleRes, groupRes, categoryRes] = await Promise.all([
      csRoutingStaff(), csRoutingRules(), csRoutingGroups(), getCategoryTree()
    ])
    staff.value = staffRes.data || []
    rules.value = ruleRes.data || []
    groups.value = groupRes.data || []
    categoryTree.value = categoryRes.data || []
    if (!productOptions.value.length) await searchProducts('')
  } catch (error) {
    ElMessage.error('客服分配配置加载失败，请重试')
  } finally {
    loading.value = false
  }
}

async function loadRatings() {
  ratingLoading.value = true
  try {
    ratingStats.value = (await csRatingStats()).data || {}
  } catch (_) {
    ratingStats.value = {}
  } finally {
    ratingLoading.value = false
  }
}

async function searchProducts(keyword) {
  productLoading.value = true
  try {
    const res = await getProductList({ pageNum: 1, pageSize: 30, keyword: keyword || '' })
    const merged = new Map(productOptions.value.map(item => [item.id, item]))
    for (const item of res.data?.records || []) merged.set(item.id, item)
    productOptions.value = [...merged.values()]
  } catch (_) {
    // Keep previously loaded options so existing multi-select values remain readable.
  } finally {
    productLoading.value = false
  }
}

function openRule(row = null) {
  Object.assign(ruleForm, {
    id: row?.id || null,
    matchType: row?.matchType || 'PRODUCT',
    targetId: row?.targetId ?? null,
    targetIds: Array.isArray(row?.targetIds) ? [...row.targetIds] : (row?.targetId ? [row.targetId] : []),
    assigneeType: row?.assigneeType || (enabledGroups.value.length ? 'GROUP' : 'CS'),
    assigneeId: row?.assigneeId ?? null,
    enabled: row?.enabled ?? 1,
    remark: row?.remark || ''
  })
  if (row?.matchType === 'PRODUCT') {
    for (let index = 0; index < ruleForm.targetIds.length; index += 1) {
      const targetId = ruleForm.targetIds[index]
      if (!productOptions.value.some(item => item.id === targetId)) {
        const target = row.targets?.[index]
        productOptions.value.unshift({ id: targetId, name: target?.name || ('商品 ' + targetId) })
      }
    }
  }
  ruleVisible.value = true
}

async function saveRule() {
  if (ruleForm.matchType !== 'DEFAULT' && !ruleForm.targetIds.length) {
    ElMessage.warning('请选择要匹配的商品或分类')
    return
  }
  if (!ruleForm.assigneeId) {
    ElMessage.warning('请选择客服或客服组')
    return
  }
  saving.value = true
  try {
    await saveCsRoutingRule({
      id: ruleForm.id,
      matchType: ruleForm.matchType,
      targetId: ruleForm.matchType === 'DEFAULT' ? 0 : (ruleForm.targetIds.length === 1 ? ruleForm.targetIds[0] : null),
      targetIds: ruleForm.matchType === 'DEFAULT' ? [0] : ruleForm.targetIds,
      assigneeType: ruleForm.assigneeType,
      assigneeId: ruleForm.assigneeId,
      enabled: ruleForm.enabled,
      remark: ruleForm.remark
    })
    ElMessage.success('分配规则已保存')
    ruleVisible.value = false
    rules.value = (await csRoutingRules()).data || []
  } catch (_) {
    // request interceptor shows the server message
  } finally {
    saving.value = false
  }
}

async function toggleRule(row) {
  try {
    await saveCsRoutingRule({
      id: row.id,
      matchType: row.matchType,
      targetIds: row.targetIds,
      assigneeType: row.assigneeType,
      assigneeId: row.assigneeId,
      enabled: row.enabled === 1 ? 0 : 1,
      remark: row.remark
    })
    rules.value = (await csRoutingRules()).data || []
  } catch (_) {}
}

async function removeRule(row) {
  try {
    await ElMessageBox.confirm('确定删除这条分配规则？删除后相关商品会进入公共池或命中下一条规则。', '删除规则', { type: 'warning' })
    await deleteCsRoutingRule(row.id)
    ElMessage.success('规则已删除')
    rules.value = (await csRoutingRules()).data || []
  } catch (_) {}
}

function openGroup(row = null) {
  Object.assign(groupForm, {
    id: row?.id || null,
    name: row?.name || '',
    leaderId: row?.leaderId || null,
    memberIds: row ? memberIds(row) : [],
    enabled: row?.enabled ?? 1,
    remark: row?.remark || ''
  })
  groupVisible.value = true
}

async function saveGroup() {
  if (!groupForm.name.trim()) return ElMessage.warning('请输入客服组名称')
  if (!groupForm.memberIds.length) return ElMessage.warning('请选择至少一名客服')
  saving.value = true
  try {
    await saveCsRoutingGroup({
      id: groupForm.id,
      name: groupForm.name.trim(),
      leaderId: groupForm.leaderId,
      memberIds: groupForm.memberIds,
      enabled: groupForm.enabled,
      remark: groupForm.remark
    })
    ElMessage.success('客服组已保存')
    groupVisible.value = false
    const [groupRes, staffRes] = await Promise.all([csRoutingGroups(), csRoutingStaff()])
    groups.value = groupRes.data || []
    staff.value = staffRes.data || []
  } catch (_) {
  } finally {
    saving.value = false
  }
}

async function removeGroup(row) {
  try {
    await ElMessageBox.confirm('确定删除客服组“' + row.name + '”？仍被规则使用的客服组不能删除。', '删除客服组', { type: 'warning' })
    await deleteCsRoutingGroup(row.id)
    ElMessage.success('客服组已删除')
    groups.value = (await csRoutingGroups()).data || []
  } catch (_) {}
}

onMounted(() => { loadAll(); loadRatings() })
</script>
<style scoped>
.cs-routing-page .card-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.header-copy span { color: #171717; font-size: 16px; font-weight: 800; }
.header-copy small { display: block; margin-top: 4px; color: #909399; font-size: 12px; font-weight: 400; }
.rule-note { margin-bottom: 16px; }
.target-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.group-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 14px; color: #777777; font-size: 12px; }
.form-tip { margin-top: 6px; color: #909399; font-size: 12px; line-height: 1.5; }
.rating-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 16px; }
.rating-summary-card { padding: 16px; border-radius: 12px; background: #f6f7f8; }
.rating-summary-card span { display: block; color: #777; font-size: 12px; }
.rating-summary-card strong { display: block; margin: 7px 0 3px; color: #171717; font-size: 26px; line-height: 1.1; }
.rating-summary-card small { color: #999; font-size: 11px; }
.rating-recent-title { margin: 22px 0 10px; color: #171717; font-size: 14px; font-weight: 700; }
.rating-stars { color: #c58a22; letter-spacing: 2px; }
@media (max-width: 768px) {
  .card-header { align-items: flex-start; }
  .group-toolbar { align-items: flex-start; flex-direction: column; }
  .rating-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
