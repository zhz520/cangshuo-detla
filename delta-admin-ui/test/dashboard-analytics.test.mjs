import test from 'node:test'
import assert from 'node:assert/strict'
import {
  compareMetric, comparisonText, formatMoney, funnelRows, maxMetric, metricBar, selectedTrend
} from '../src/utils/dashboard-analytics.js'

test('dashboard comparisons handle growth, decline, zero baselines and money formatting', () => {
  assert.deepEqual(compareMetric(120, 100), {
    current: 120, previous: 100, delta: 20, percent: 20, direction: 'UP'
  })
  assert.equal(comparisonText(compareMetric(80, 100)), '↓ 20.0%')
  assert.equal(compareMetric(4, 0).percent, 100)
  assert.equal(compareMetric(0, 0).direction, 'FLAT')
  assert.equal(formatMoney('12.5'), '¥12.50')
})

test('trend bars select 7 or 30 day data and never divide by zero', () => {
  const data = {
    trend7Days: [{ orders: 2 }, { orders: 5 }],
    trend30Days: [{ orders: 8 }]
  }
  assert.equal(selectedTrend(data, 7).length, 2)
  assert.equal(selectedTrend(data, 30)[0].orders, 8)
  assert.equal(maxMetric(data.trend7Days, 'orders'), 5)
  assert.equal(metricBar(5, 5), '100%')
  assert.equal(metricBar(0, 0), '0%')
})

test('funnel stages expose visit and step conversion without trusting malformed values', () => {
  const rows = funnelRows([
    { stage: 'VISIT', value: 100 },
    { stage: 'ORDER', value: 25 },
    { stage: 'EFFECTIVE', value: '20', rateFromVisits: '20.0' }
  ])
  assert.equal(rows[1].rateFromPrevious, 25)
  assert.equal(rows[2].value, 20)
  assert.equal(rows[2].rateFromVisits, 20)
  assert.equal(funnelRows([{ value: 2 }])[0].rateFromPrevious, 100)
})
