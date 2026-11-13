import test from 'node:test'
import assert from 'node:assert/strict'
import {readFile} from 'node:fs/promises'

test('admin player review shows the submitted application before approving', async () => {
  const source = await readFile(new URL('../src/views/player/PlayerList.vue', import.meta.url), 'utf8')
  for (const label of ['真实姓名', '游戏ID/段位', '擅长服务', '技能标签', '证明截图']) {
    assert.ok(source.includes(label), '详情弹窗缺少：' + label)
  }
  assert.ok(source.includes(':preview-src-list="proofImages"'))
  assert.ok(source.includes('const proofImages = computed'))
  assert.ok(source.includes('const skillTagsText = computed'))
  assert.ok(source.includes('detailData.serviceTypes'))
  assert.ok(source.includes('detailData.gameLevel'))
})

test('approving an application opens a confirmation dialog with applicant details and optional level', async () => {
  const source = await readFile(new URL('../src/views/player/PlayerList.vue', import.meta.url), 'utf8')
  assert.ok(source.includes('@click="handleApprove(row)"'))
  assert.ok(!source.includes('@click="handleApprove(row.id)"'))
  assert.ok(source.includes('v-model="approveVisible"'))
  assert.ok(source.includes('function handleApprove(row)'))
  assert.ok(source.includes("row.status !== 'PENDING'"))
  assert.ok(source.includes('@click="confirmApprove"'))
  assert.ok(source.includes('adminPlayerApprove(row.id, approveLevelId.value ?? undefined)'))
  assert.ok(source.includes('确认通过'))
  assert.ok(source.includes('@click="openRejectDialog(row)"'))
  assert.ok(source.includes('驳回「{{ rejectName }}」的入驻申请'))
  assert.ok(source.includes("if (!rejectReason.value.trim()) return ElMessage.warning('请填写驳回原因')"))
})
