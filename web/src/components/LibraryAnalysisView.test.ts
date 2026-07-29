import { describe, expect, it } from 'vitest'
import type { AnalysisStatus, Track } from '../types'
import { groupTracksByAnalysis } from './LibraryAnalysisView'

const track = (id: string, status?: AnalysisStatus): Track => ({
  id, title: id, artist: 'SHiNe', album: '', durationMs: 1,
  analysis: status ? { status, progress: status === 'completed' ? 1 : 0 } : undefined,
})

describe('groupTracksByAnalysis', () => {
  it('keeps only completed tracks in the analyzed group and retryable states pending', () => {
    const groups = groupTracksByAnalysis([
      track('none'), track('pending', 'pending'), track('queued', 'queued'), track('failed', 'failed'), track('done', 'completed'),
    ])
    expect(groups.completed.map((item) => item.id)).toEqual(['done'])
    expect(groups.pending.map((item) => item.id)).toEqual(['none', 'pending', 'queued', 'failed'])
  })
})
