export type Section = 'home' | 'search' | 'library' | 'local' | 'rooms' | 'settings'

export const mainNavigation = [
  { id: 'home', label: '首页', icon: 'home' },
  { id: 'search', label: '搜索', icon: 'search' },
  { id: 'library', label: '音乐库', icon: 'library' },
  { id: 'local', label: '本地', icon: 'storage' },
  { id: 'rooms', label: '同步', icon: 'room' },
] as const

export function sectionFromHash(hash: string): Section {
  const value = hash.replace(/^#/, '')
  if (value === 'favorites' || value === 'playlists') return 'library'
  return [...mainNavigation.map((item) => item.id), 'settings'].includes(value as Section) ? value as Section : 'home'
}
