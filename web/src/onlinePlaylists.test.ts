import { describe, expect, it, vi } from 'vitest'
import { collectOnlinePlaylistTracks, formatPlayCount, mergeOnlinePlaylistTracks, normalizedPage } from './onlinePlaylists'
import type { OnlinePlaylistDetailResponse, OnlineTrack } from './types'

const track = (id: string): OnlineTrack => ({
  id,
  title: `歌曲 ${id}`,
  artist: '歌手',
  album: '专辑',
  artworkUrl: null,
  source: 'wy',
  durationMs: 180_000,
})

const detail = (page: number, ids: string[]): OnlinePlaylistDetailResponse => ({
  id: 'playlist-1',
  name: '测试歌单',
  author: 'SHiNe',
  artworkUrl: null,
  description: '测试',
  tracks: ids.map(track),
  total: 4,
  page,
  limit: 2,
  allPages: 3,
  source: 'wy',
})

describe('online playlist helpers', () => {
  it('formats Chinese play counts without false precision', () => {
    expect(formatPlayCount(0)).toBe('暂无播放量')
    expect(formatPlayCount(9_999)).toBe('9,999 次播放')
    expect(formatPlayCount(12_340)).toBe('1.2 万次播放')
    expect(formatPlayCount(230_000_000)).toBe('2.3 亿次播放')
  })

  it('clamps invalid pagination values', () => {
    expect(normalizedPage(0, 4)).toBe(1)
    expect(normalizedPage(8, 4)).toBe(4)
    expect(normalizedPage(Number.NaN, 0)).toBe(1)
  })

  it('merges playlist pages in order and removes duplicate tracks', () => {
    expect(mergeOnlinePlaylistTracks([[track('a'), track('b')], [track('b'), track('c')]]).map((item) => item.id)).toEqual(['a', 'b', 'c'])
  })

  it('loads every missing detail page before an entire-playlist action', async () => {
    const loadPage = vi.fn(async (page: number) => detail(page, page === 1 ? ['a', 'b'] : ['d']))
    const tracks = await collectOnlinePlaylistTracks(detail(2, ['b', 'c']), loadPage)

    expect(loadPage).toHaveBeenCalledTimes(2)
    expect(loadPage).toHaveBeenNthCalledWith(1, 1)
    expect(loadPage).toHaveBeenNthCalledWith(2, 3)
    expect(tracks.map((item) => item.id)).toEqual(['a', 'b', 'c', 'd'])
  })
})
