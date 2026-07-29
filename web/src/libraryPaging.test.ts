import { describe, expect, it } from 'vitest'
import { mergeTrackPage, visibleTrackRange } from './libraryPaging'
import type { Track } from './types'

const track = (id: string): Track => ({
  id,
  title: id,
  artist: 'artist',
  album: '',
  durationMs: 0,
  mimeType: 'audio/mpeg',
  size: 1,
  modifiedAt: 1,
})

describe('large library paging', () => {
  it('merges subsequent pages without duplicating tracks', () => {
    expect(mergeTrackPage([track('1'), track('2')], [track('2'), track('3')]).map((item) => item.id))
      .toEqual(['1', '2', '3'])
  })

  it('renders only the visible rows with overscan', () => {
    expect(visibleTrackRange(620, 310, 62, 10_000, 2)).toEqual({ start: 8, end: 17 })
  })
})
