export const updateItemKey = (release, item) => `${release.version}:${item.id}`

export function compareReleaseVersions(left, right) {
  const parse = value => String(value || '').split('.').map(part => Number.parseInt(part, 10))
  const a = parse(left), b = parse(right)
  if (a.length !== 3 || b.length !== 3 || [...a, ...b].some(value => !Number.isSafeInteger(value))) return 0
  for (let index = 0; index < 3; index++) if (a[index] !== b[index]) return Math.sign(a[index] - b[index])
  return 0
}

export const applicableUpdateReleases = (releases = [], currentVersion = '') =>
  releases.filter(release => compareReleaseVersions(release.version, currentVersion) > 0)

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
    selected.add(updateItemKey(targetRelease, targetItem))
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
