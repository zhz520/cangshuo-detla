const wideLabels = /操作|商品|内容|原因|备注|说明|地址|订单号|手机号|昵称|标题|链接|凭证|资料|详情/

function headerLabels(table) {
  const rows = [...table.querySelectorAll('.el-table__header-wrapper thead tr')]
  const headerRow = rows[rows.length - 1]
  return [...(headerRow?.querySelectorAll('th') || [])]
    .map(cell => cell.querySelector('.cell')?.textContent?.trim() || '')
}

function decorateTable(table) {
  const labels = headerLabels(table)
  if (!labels.length) return
  table.classList.add('mobile-card-table')
  for (const row of table.querySelectorAll('.el-table__body-wrapper tbody tr')) {
    const cells = [...row.children].filter(cell => cell.matches('td'))
    cells.forEach((cell, index) => {
      const label = labels[index] || (index === 0 ? '选择' : '信息')
      cell.dataset.mobileLabel = label
      cell.classList.toggle('mobile-cell-wide', wideLabels.test(label) || Boolean(cell.querySelector('.el-input, .el-textarea, .el-select')))
    })
  }
}

export function installMobileTableCards(root, compactQuery = window.matchMedia('(max-width: 768px)')) {
  if (!root) return () => {}
  let frame = 0
  const apply = () => {
    frame = 0
    if (!compactQuery.matches) {
      for (const table of root.querySelectorAll('.mobile-card-table')) table.classList.remove('mobile-card-table')
      return
    }
    for (const table of root.querySelectorAll('.el-table')) decorateTable(table)
  }
  const schedule = () => { if (!frame) frame = requestAnimationFrame(apply) }
  const observer = new MutationObserver(schedule)
  observer.observe(root, { childList: true, subtree: true })
  const onMediaChange = () => schedule()
  if (compactQuery.addEventListener) compactQuery.addEventListener('change', onMediaChange)
  else compactQuery.addListener(onMediaChange)
  schedule()
  return () => {
    observer.disconnect()
    cancelAnimationFrame(frame)
    if (compactQuery.removeEventListener) compactQuery.removeEventListener('change', onMediaChange)
    else compactQuery.removeListener(onMediaChange)
  }
}
