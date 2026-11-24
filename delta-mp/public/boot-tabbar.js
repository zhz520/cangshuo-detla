(function () {
  var bar = document.getElementById('boot-tabbar')
  var app = document.getElementById('app')
  if (!bar || !app) return
  var tabRoute = /^#\/(?:pages\/(?:index|category|players|chat\/list|mine)|pages-player\/(?:hall|order\/list|chat\/list|mine)|pages-cs\/(?:dashboard|order\/list|complaint\/list|chat\/list|mine))/.test(location.hash)
  var count = /^#\/pages-player\//.test(location.hash) ? 4 : 5
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

