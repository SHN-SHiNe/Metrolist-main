export type Section = 'home' | 'search' | 'library' | 'local' | 'downloads' | 'rooms' | 'history' | 'stats' | 'settings'

export const mainNavigation = [
  { id: 'home', label: '首页', icon: 'home' },
  { id: 'search', label: '搜索', icon: 'search' },
  { id: 'library', label: '音乐库', icon: 'library' },
  { id: 'local', label: '本地', icon: 'storage' },
] as const

export const roomNavigation = { id: 'rooms', label: '同步房间', icon: 'room' } as const
export const downloadNavigation = { id: 'downloads', label: '下载任务', icon: 'download' } as const
export const desktopNavigation = [...mainNavigation, downloadNavigation, roomNavigation] as const

export function sectionFromHash(hash: string): Section {
  const value = hash.replace(/^#/, '')
  if (value === 'favorites' || value === 'playlists') return 'library'
  return [...desktopNavigation.map((item) => item.id), 'history', 'stats', 'settings'].includes(value as Section) ? value as Section : 'home'
}
