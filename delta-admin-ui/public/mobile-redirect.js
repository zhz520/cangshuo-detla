(function () {
  var path = window.location.pathname
  if (path !== '/mobile' && path.indexOf('/mobile/') !== 0) return
  var suffix = path.slice('/mobile'.length)
  window.location.replace('/h5' + suffix + window.location.search + window.location.hash)
})()
