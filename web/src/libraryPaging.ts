import type { Track } from './types'

export const LIBRARY_PAGE_SIZE = 200
export const TRACK_ROW_HEIGHT = 62

export function mergeTrackPage(current: Track[], incoming: Track[]): Track[] {
  const byId = new Map(current.map((track) => [track.id, track]))
  incoming.forEach((track) => byId.set(track.id, track))
  return [...byId.values()]
}

export function visibleTrackRange(
  scrollTop: number,
  viewportHeight: number,
  rowHeight: number,
  count: number,
  overscan = 6,
): { start: number; end: number } {
  const start = Math.min(count, Math.max(0, Math.floor(scrollTop / rowHeight) - overscan))
  const end = Math.min(count, Math.ceil((scrollTop + viewportHeight) / rowHeight) + overscan)
  return { start, end }
}
