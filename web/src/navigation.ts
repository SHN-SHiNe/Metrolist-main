export type Section = 'home' | 'search' | 'library' | 'local' | 'rooms' | 'settings'

export const mainNavigation = [
  { id: 'home', label: '首页', icon: 'home' },
  { id: 'search', label: '搜索', icon: 'search' },
  { id: 'library', label: '音乐库', icon: 'library' },
  { id: 'local', label: '本地', icon: 'storage' },
] as const

export const roomNavigation = { id: 'rooms', label: '同步房间', icon: 'room' } as const
export const desktopNavigation = [...mainNavigation, roomNavigation] as const

export function sectionFromHash(hash: string): Section {
  const value = hash.replace(/^#/, '')
  if (value === 'favorites' || value === 'playlists') return 'library'
  return [...desktopNavigation.map((item) => item.id), 'settings'].includes(value as Section) ? value as Section : 'home'
}
