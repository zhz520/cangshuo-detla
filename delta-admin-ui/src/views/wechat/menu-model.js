const eventTypes = [
  ['click', '点击事件'], ['scancode_push', '扫码上报'], ['scancode_waitmsg', '扫码并等待回复'],
  ['pic_sysphoto', '拍照'], ['pic_photo_or_album', '拍照或相册'], ['pic_weixin', '微信相册'], ['location_select', '发送位置']
]

export const menuTypes = [
  { value: 'view', label: '打开网页', fields: ['url'] },
  { value: 'miniprogram', label: '打开小程序', fields: ['appid', 'pagepath', 'url'] },
  ...eventTypes.map(([value, label]) => ({ value, label, fields: ['key'] })),
  { value: 'media_id', label: '发送素材', fields: ['media_id'] },
  { value: 'view_limited', label: '打开图文素材', fields: ['media_id'] },
  { value: 'article_id', label: '发送已发布文章', fields: ['article_id'] },
  { value: 'article_view_limited', label: '打开已发布文章', fields: ['article_id'] }
]

export const menuFieldLabels = {
  url: '网页地址', appid: '小程序 AppID', pagepath: '小程序页面路径',
  key: '事件标识', media_id: '微信素材 ID', article_id: '文章 ID'
}
export const menuType = value => menuTypes.find(item => item.value === value)
export const utf8Size = text => new TextEncoder().encode(String(text || '')).length
export const menuLimit = level => level === 0 ? 16 : 60

let counter = 0
export function newMenuButton(raw = {}) {
  return {
    id: ++counter, name: raw.name || '', type: raw.type || 'view',
    ...Object.fromEntries(Object.keys(menuFieldLabels).map(key => [key, raw[key] == null ? '' : String(raw[key])])),
    children: Array.isArray(raw.sub_button) ? raw.sub_button.map(newMenuButton) : []
  }
}

export function readMenuButtons(value) {
  return (Array.isArray(value?.button) ? value.button : []).map(newMenuButton)
}

export function writeMenuButtons(roots) {
  const visit = node => {
    const name = node.name.trim()
    if (node.children.length) return { name, sub_button: node.children.map(visit) }
    const result = { name, type: node.type }
    for (const key of menuType(node.type)?.fields || []) result[key] = node[key].trim()
    return result
  }
  return { button: roots.map(visit) }
}

export function menuErrors(roots) {
  const errors = new Map()
  if (roots.length > 3) errors.set(null, ['最多配置 3 个一级菜单'])
  function visit(node, depth) {
    const issues = []
    if (!node.name.trim()) issues.push('请填写菜单名称')
    else if (utf8Size(node.name.trim()) > menuLimit(depth)) issues.push(`菜单名称不能超过 ${menuLimit(depth)} 字节`)
    if (node.children.length) {
      if (depth > 0 || node.children.length > 5) issues.push('只支持两级菜单，每组最多 5 个子菜单')
      if (depth === 0) node.children.forEach(child => visit(child, 1))
    } else {
      const action = menuType(node.type)
      if (!action) issues.push('此动作暂不支持，请换成列表中的动作')
      for (const key of action?.fields || []) {
        const value = node[key].trim()
        if (!value) issues.push(`请填写${menuFieldLabels[key]}`)
        else if (key === 'url' && !/^https?:\/\/[^\s/?#]+[^\s]*$/i.test(value)) issues.push('请输入有效的 HTTP 或 HTTPS 地址')
        else if (key === 'appid' && !/^wx[A-Za-z0-9]{16}$/.test(value)) issues.push('小程序 AppID 格式不正确')
      }
    }
    if (issues.length) errors.set(node.id, issues)
  }
  roots.forEach(node => visit(node, 0))
  return errors
}

export function moveMenuButton(nodes, index, step) {
  const destination = index + step
  if (index < 0 || index >= nodes.length || destination < 0 || destination >= nodes.length) return false
  const [item] = nodes.splice(index, 1)
  nodes.splice(destination, 0, item)
  return true
}
