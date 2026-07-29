import type { SimilarTrack, Track } from './types'

export function recentTrackIds(history: readonly string[], currentId?: string | null, limit = 12) {
  return [...history, ...(currentId ? [currentId] : [])]
    .filter((id, index, values) => id && values.lastIndexOf(id) === index)
    .slice(-limit)
}

export function mergeSimilarQueue(queue: readonly Track[], recommendations: readonly SimilarTrack[], recent: readonly string[]) {
  const excluded = new Set([...queue.map((track) => track.id), ...recent])
  return recommendations
    .map((item) => item.track)
    .filter((track) => {
      if (excluded.has(track.id)) return false
      excluded.add(track.id)
      return true
    })
}
