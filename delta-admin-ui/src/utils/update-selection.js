export const updateItemKey = (release, item) => `${release.version}:${item.id}`

export function compareReleaseVersions(left, right) {
  if (![left, right].every(validReleaseVersion)) return 0
  const parse = value => String(value).split('.').map(Number)
  const a = parse(left), b = parse(right)
  if (a.length !== 3 || b.length !== 3 || [...a, ...b].some(value => !Number.isSafeInteger(value))) return 0
  for (let index = 0; index < 3; index++) if (a[index] !== b[index]) return Math.sign(a[index] - b[index])
  return 0
}

const validReleaseVersion = value => /^\d+\.\d+\.\d+$/.test(String(value || ''))

export function isUpdateItemInstalled(release, item, componentVersions = {}, installedKeys = []) {
  const version = componentVersions?.[item.component]
  // Actual deployed component versions take precedence over historical plans,
  // which can still contain an item after a rollback or a partial installation.
  if (validReleaseVersion(version) && validReleaseVersion(release.version)) return compareReleaseVersions(version, release.version) >= 0
  return installedKeys.includes(updateItemKey(release, item))
}

export function applicableUpdateReleases(releases = [], currentVersion = '', componentVersions = {}, installedKeys = []) {
  return (releases || []).filter(release => {
    if (!validReleaseVersion(release.version)) return false
    if (!release.items?.length) return compareReleaseVersions(release.version, currentVersion) > 0
    return release.items.some(item => !isUpdateItemInstalled(release, item, componentVersions, installedKeys)
      && compareReleaseVersions(release.version, componentVersions?.[item.component] || currentVersion) > 0)
  })
}

export function publicReleaseNotesUrl(value) {
  try {
    const url = new URL(String(value || ''))
    return ['http:', 'https:'].includes(url.protocol) && url.pathname.startsWith('/public/release-notes/') ? url.href : ''
  } catch {
    return ''
  }
}

export function defaultUpdateSelection(releases = [], installedKeys = []) {
  const installed = new Set(installedKeys)
  return releases.flatMap(release => (release.items || [])
    .map(item => updateItemKey(release, item))
    .filter(key => !installed.has(key)))
}

export function toggleUpdateSelection(releases = [], current = [], release, item, checked) {
  const selected = new Set(current)
  const key = updateItemKey(release, item)
  const includeDependencies = targetRelease => targetItem => {
    const targetKey = updateItemKey(targetRelease, targetItem)
    if (selected.has(targetKey)) return
    selected.add(targetKey)
    for (const dependencyId of targetItem.requires || []) {
      const dependency = (targetRelease.items || []).find(candidate => candidate.id === dependencyId)
      if (dependency) includeDependencies(targetRelease)(dependency)
    }
  }

  if (checked) includeDependencies(release)(item)
  else if (!item.required) selected.delete(key)

  // Keep required items and dependencies valid. Optional items that depend on
  // a newly removed item are removed as a group; required dependants restore it.
  let changed = true
  while (changed) {
    changed = false
    for (const candidate of releases) {
      for (const candidateItem of candidate.items || []) {
        const candidateKey = updateItemKey(candidate, candidateItem)
        if (!selected.has(candidateKey)) continue
        for (const dependencyId of candidateItem.requires || []) {
          const dependency = (candidate.items || []).find(entry => entry.id === dependencyId)
          if (!dependency) continue
          const dependencyKey = updateItemKey(candidate, dependency)
          if (selected.has(dependencyKey)) continue
          if (candidateItem.required) selected.add(dependencyKey)
          else selected.delete(candidateKey)
          changed = true
        }
      }
    }
  }
  return releases.flatMap(candidate => (candidate.items || []).map(entry => updateItemKey(candidate, entry))).filter(candidateKey => selected.has(candidateKey))
}
