// uni:h5-css appends CDN images *after* reading uni-h5's framework styles.
 // Process those two CSS modules before Vite hashes them, not the generated dist.
const colors = {
  grey: '40, 44, 52', blue: '0, 95, 210', green: '15, 135, 75',
  orange: '210, 110, 0', red: '200, 50, 50', yellow: '178, 148, 0'
}

export function localHeaderShadows() {
  return {
    postcssPlugin: 'delta-local-uni-header-shadows',
    Once(root) {
      const file = root.source?.input.file?.replaceAll('\\', '/') || ''
      const framework = '/@dcloudio/uni-h5/style/framework/'
      if (file.endsWith(framework + 'shadow.css')) {
        // This invisible body pseudo-element exists solely to warm the image cache.
        root.walkRules(/^body::?after$/, rule => {
          if (rule.nodes.some(n => n.prop === 'animation' && /\bshadow-preload\b/.test(n.value))) rule.remove()
        })
        root.walkAtRules('keyframes', rule => { if (rule.params === 'shadow-preload') rule.remove() })
      } else if (file.endsWith(framework + 'pageHead.css')) {
        root.walkRules(rule => {
          const match = /^\.uni-page-head-shadow-(\w+)::?after$/.exec(rule.selector)
          if (!match || !Object.hasOwn(colors,match[1])) return
          // Keep framework class selection, pseudo-element geometry and all other declarations.
          rule.walkDecls('background-image', decl => {
            decl.value = 'linear-gradient(to bottom, rgba(' + colors[match[1]] + ', .20), transparent)'
          })
        })
      }
    }
  }
}
