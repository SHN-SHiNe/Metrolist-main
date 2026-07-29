import { describe, expect, it } from 'vitest'
import { mergeSimilarQueue, recentTrackIds } from './similarAutoplay'
import type { SimilarTrack, Track } from './types'

const track = (id: string): Track => ({ id, title: id, artist: '', album: '', durationMs: 1 })
const similar = (id: string): SimilarTrack => ({ track: track(id), similarityPercent: 90 })

describe('similar autoplay', () => {
  it('keeps the last twelve unique played tracks', () => {
    const values = Array.from({ length: 14 }, (_, index) => String(index))
    expect(recentTrackIds(values, '13')).toEqual(values.slice(2))
  })

  it('excludes queue, recent history, and duplicate recommendations', () => {
    expect(mergeSimilarQueue([track('a')], [similar('a'), similar('b'), similar('b'), similar('c')], ['c']).map((item) => item.id)).toEqual(['b'])
  })
})
