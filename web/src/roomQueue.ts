export type RoomQueueRemoval = {
  queue: string[]
  currentTrackId: string | null
}

export function insertRoomTrackNext(queue: string[], currentTrackId: string | null, trackId: string): string[] {
  if (trackId === currentTrackId) return queue
  const withoutTarget = queue.filter((id) => id !== trackId)
  const currentIndex = currentTrackId ? withoutTarget.indexOf(currentTrackId) : -1
  const insertionIndex = currentIndex >= 0 ? currentIndex + 1 : 0
  return [...withoutTarget.slice(0, insertionIndex), trackId, ...withoutTarget.slice(insertionIndex)]
}

export function appendRoomTrack(queue: string[], trackId: string): string[] {
  return queue.includes(trackId) ? queue : [...queue, trackId]
}

export function moveRoomTrack(queue: string[], trackId: string, delta: -1 | 1): string[] {
  const from = queue.indexOf(trackId)
  const to = from + delta
  if (from < 0 || to < 0 || to >= queue.length) return queue
  const next = [...queue]
  const [moved] = next.splice(from, 1)
  next.splice(to, 0, moved)
  return next
}

export function removeRoomTrack(queue: string[], currentTrackId: string | null, trackId: string): RoomQueueRemoval {
  const removeIndex = queue.indexOf(trackId)
  if (removeIndex < 0) return { queue, currentTrackId }
  const nextQueue = queue.filter((id) => id !== trackId)
  if (trackId !== currentTrackId) return { queue: nextQueue, currentTrackId }
  return {
    queue: nextQueue,
    currentTrackId: nextQueue[Math.min(removeIndex, nextQueue.length - 1)] ?? null,
  }
}
