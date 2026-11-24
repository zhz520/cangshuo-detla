(function () {
  var bar = document.getElementById('boot-tabbar')
  var app = document.getElementById('app')
  if (!bar || !app) return
  var path = location.hash.replace(/^#/, '').split('?')[0]
  var TAB_ROUTES = new Set([
    '/pages/index/index', '/pages/category/index', '/pages/players/index', '/pages/chat/list', '/pages/mine/index',
    '/pages-player/hall/index', '/pages-player/order/list', '/pages-player/chat/list', '/pages-player/mine/index',
    '/pages-cs/dashboard/index', '/pages-cs/order/list', '/pages-cs/complaint/list', '/pages-cs/chat/list', '/pages-cs/mine/index'
  ])
  var tabRoute = TAB_ROUTES.has(path)
  var count = path.startsWith('/pages-player/') ? 4 : 5
  for (var i = 0; i < count; i += 1) bar.appendChild(document.createElement('span'))
  var hidden = false
  var observer = new MutationObserver(function () { if (ready()) hide() })
  function hide () {
    if (hidden) return
    hidden = true
    observer.disconnect()
    bar.classList.add('is-hiding')
    setTimeout(function () { bar.remove() }, 140)
  }
  function ready () {
    return tabRoute ? document.querySelector('.custom-tabbar') : app.children.length > 0
  }
  observer.observe(document.documentElement, { childList: true, subtree: true })
  if (ready()) hide()
  setTimeout(hide, 12000)
})()

