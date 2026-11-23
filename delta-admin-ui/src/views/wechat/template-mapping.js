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
