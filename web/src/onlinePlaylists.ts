import type { OnlinePlaylistDetailResponse, OnlineTrack } from './types'

export const ONLINE_PLAYLIST_DETAIL_LIMIT = 100
export const MAX_COMPLETE_PLAYLIST_PAGES = 100

export function formatPlayCount(value: number): string {
  if (!Number.isFinite(value) || value <= 0) return '暂无播放量'
  if (value >= 100_000_000) return `${trimDecimal(value / 100_000_000)} 亿次播放`
  if (value >= 10_000) return `${trimDecimal(value / 10_000)} 万次播放`
  return `${Math.floor(value).toLocaleString('zh-CN')} 次播放`
}

export function normalizedPage(page: number, allPages: number): number {
  const lastPage = Math.max(1, Math.floor(allPages) || 1)
  return Math.min(lastPage, Math.max(1, Math.floor(page) || 1))
}

export function mergeOnlinePlaylistTracks(pages: Iterable<readonly OnlineTrack[]>): OnlineTrack[] {
  const seen = new Set<string>()
  const merged: OnlineTrack[] = []
  for (const tracks of pages) {
    for (const track of tracks) {
      if (seen.has(track.id)) continue
      seen.add(track.id)
      merged.push(track)
    }
  }
  return merged
}

export async function collectOnlinePlaylistTracks(
  initial: OnlinePlaylistDetailResponse,
  loadPage: (page: number) => Promise<OnlinePlaylistDetailResponse>,
): Promise<OnlineTrack[]> {
  const allPages = Math.max(1, initial.allPages)
  if (allPages > MAX_COMPLETE_PLAYLIST_PAGES) {
    throw new Error(`歌单超过 ${MAX_COMPLETE_PLAYLIST_PAGES * ONLINE_PLAYLIST_DETAIL_LIMIT} 首，暂不支持整张操作`)
  }

  const pages = new Map<number, readonly OnlineTrack[]>([[normalizedPage(initial.page, allPages), initial.tracks]])
  for (let page = 1; page <= allPages; page += 1) {
    if (!pages.has(page)) pages.set(page, (await loadPage(page)).tracks)
  }
  return mergeOnlinePlaylistTracks(Array.from(pages.entries()).sort(([left], [right]) => left - right).map(([, tracks]) => tracks))
}

function trimDecimal(value: number): string {
  return value.toFixed(value >= 100 ? 0 : 1).replace(/\.0$/, '')
}
