// Keys such as thing3 belong to the account's actual WeChat template.
const labels = {
  orderNo: /订单(?:编?号|编号)/,
  productName: /(?:商品|服务|项目)(?:名称|名)/,
  orderAmount: /(?:订单|支付|实付)金额/,
  status: /(?:状态|进度|审核结果|处理结果)/,
  refundNo: /退款(?:单号|编号)/,
  refundAmount: /退款金额/,
  complaintType: /投诉类型/,
  time: /^(?:通知时间|处理时间|更新时间|时间)$/
}

export function existingFieldsForTemplate(templateId, oldTemplateId, fields) {
  if (!oldTemplateId || templateId !== oldTemplateId) return {}
  return Object.fromEntries(fields.map(field => [field.key, field.source]))
}

export function suggestWechatFields(content, suggestions, previous = {}) {
  const rows = []
  for (const line of String(content || '').split(/\r?\n/)) {
    for (const match of line.matchAll(/([^{}]{0,50}){{([A-Za-z_]+?)[0-9]*\.DATA}}/g)) {
      const label = match[1].trim().replace(/[：:\s]+$/,'')
      const key = match[0].match(/{{([A-Za-z_]+[0-9]*)\.DATA}}/)[1]
      if (rows.some(row => row.key === key)) continue
      const type = match[2]
      const candidates = suggestions.filter(item => item.type === type && (item.label === label || labels[item.source]?.test(label)))
      rows.push({ key, source: previous[key] || (candidates.length === 1 ? candidates[0].source : '') })
    }
  }
  return rows.slice(0,20)
}

// A hint is not a template ID. Only preselect a unique exact title match from
// the current account with entirely recognizable fields. Saving is explicit.
export function suggestWechatTemplate(catalog, scheme) {
  if (!scheme || !Array.isArray(catalog)) return null
  const normalize = value => String(value || '').normalize('NFKC').replace(/\s+/g, '').toLowerCase()
  const hints = new Set([scheme.name, ...String(scheme.templateHint || '')
    .replace(/^搜索关键词\s*[:：]\s*/, '').split(/[、,，;；]/)].map(normalize).filter(Boolean))
  if (!hints.size) return null
  const candidates = catalog.filter(item => item?.templateId && hints.has(normalize(item.title)))
  if (candidates.length !== 1) return null
  const template = candidates[0]
  const allKeys = new Set([...String(template.content || '').matchAll(/{{([A-Za-z_]+[0-9]*)\.DATA}}/g)].map(match => match[1]))
  const fields = suggestWechatFields(template.content, scheme.fieldSuggestions || [])
  if (fields.length === 0 || fields.length !== allKeys.size || fields.some(row => !row.source)) return null
  return { templateId: template.templateId, fields }
}

// Only the keyword, declared WeChat type and placeholder are portable; no account ID, template ID or body.
export function exportKeywordStructure(content) {
  const rows = [], keys = new Set(), labels = new Set()
  for (const line of String(content || '').split(/\r?\n/)) {
    if (!line.includes('{{') && !line.includes('}}')) continue
    const m = line.trim().match(/^([^{}:：\r\n]{1,64})\s*[:：]\s*\{\{((thing|character_string|time|amount|phone_number|car_number|const)\d*)\.DATA\}\}$/)
    if (!m) return null
    const label = m[1].trim(), normalized = label.normalize('NFKC').replace(/\s+/g,'').toLowerCase()
    if (!normalized || labels.has(normalized) || keys.has(m[2])) return null
    labels.add(normalized); keys.add(m[2]); rows.push({key:m[2],label,type:m[3]})
  }
  return rows.length && rows.length<=20 ? {schema:'wechat-template-labels-v1',fields:rows} : null
}
