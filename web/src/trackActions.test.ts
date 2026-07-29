import { describe, expect, it } from 'vitest'
import { appendTrackOnce, insertTrackNext, isTemporaryOnlineTrack } from './trackActions'
import type { Track } from './types'

const a: Track = { id: 'a', title: 'A', artist: 'Artist', album: 'Album', durationMs: 1 }
const b: Track = { id: 'b', title: 'B', artist: 'Artist', album: 'Album', durationMs: 1 }
const c: Track = { id: 'c', title: 'C', artist: 'Artist', album: 'Album', durationMs: 1 }

describe('track action queue operations', () => {
  it('moves an existing track directly behind the current song', () => {
    expect(insertTrackNext([a, b, c], 'a', c).map((track) => track.id)).toEqual(['a', 'c', 'b'])
  })

  it('does not move the current song when it is already the requested next track', () => {
    const queue = [a, b, c]
    expect(insertTrackNext(queue, b.id, b)).toBe(queue)
  })

  it('inserts at the front when there is no current song and never appends duplicates', () => {
    expect(insertTrackNext([a, b], null, c).map((track) => track.id)).toEqual(['c', 'a', 'b'])
    expect(appendTrackOnce([a, b], b)).toEqual([a, b])
  })

  it('only treats server online ids as temporary tracks', () => {
    expect(isTemporaryOnlineTrack({ ...a, id: 'online-wy-1', source: 'wy' })).toBe(true)
    expect(isTemporaryOnlineTrack({ ...a, source: 'local' })).toBe(false)
  })
})
