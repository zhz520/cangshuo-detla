function number(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

export function compareMetric(current, previous) {
  const now = number(current)
  const before = number(previous)
  const delta = now - before
  const percent = before === 0 ? (now === 0 ? 0 : 100) : (delta / before) * 100
  return {
    current: now,
    previous: before,
    delta,
    percent: Math.round(percent * 10) / 10,
    direction: delta > 0 ? 'UP' : delta < 0 ? 'DOWN' : 'FLAT'
  }
}

export function comparisonText(comparison) {
  const value = comparison || {}
  if (value.direction === 'UP') return `↑ ${Math.abs(number(value.percent)).toFixed(1)}%`
  if (value.direction === 'DOWN') return `↓ ${Math.abs(number(value.percent)).toFixed(1)}%`
  return '持平'
}

export function maxMetric(rows, key) {
  return Math.max(...(Array.isArray(rows) ? rows : []).map(row => number(row?.[key])), 1)
}

export function metricBar(value, maximum) {
  return `${Math.max(0, Math.min(100, Math.round((number(value) / Math.max(1, number(maximum))) * 100)))}%`
}

export function formatMoney(value) {
  return `¥${number(value).toFixed(2)}`
}

export function funnelRows(funnel) {
  const rows = Array.isArray(funnel) ? funnel : []
  return rows.map((row, index) => ({
    ...row,
    value: number(row?.value),
    rateFromVisits: number(row?.rateFromVisits),
    rateFromPrevious: index === 0
      ? 100
      : number(rows[index - 1]?.value) === 0
        ? 0
        : Math.round((number(row?.value) / number(rows[index - 1]?.value)) * 1000) / 10
  }))
}

export function selectedTrend(dashboard, days) {
  if (!dashboard) return []
  return days === 30 ? dashboard.trend30Days || [] : dashboard.trend7Days || []
}
