import { createContext, useContext, type ReactNode } from 'react'
import type { IconName } from './components/Icon'
import type { Track } from './types'

export type TrackMenuAction = {
  id: string
  label: string
  icon?: IconName
  disabled?: boolean
  description?: string
  children?: TrackMenuAction[]
  onSelect?: () => void | Promise<void>
}

export type TrackMenuActionFactory = (track: Track, sourceQueue: Track[]) => TrackMenuAction[]

const TrackActionsContext = createContext<TrackMenuActionFactory | null>(null)

export function TrackActionsProvider({ value, children }: { value: TrackMenuActionFactory; children: ReactNode }) {
  return <TrackActionsContext.Provider value={value}>{children}</TrackActionsContext.Provider>
}

export function useTrackActions() {
  return useContext(TrackActionsContext)
}

export function insertTrackNext(queue: Track[], currentId: string | null, track: Track): Track[] {
  if (track.id === currentId) return queue
  const withoutTarget = queue.filter((item) => item.id !== track.id)
  const currentIndex = currentId ? withoutTarget.findIndex((item) => item.id === currentId) : -1
  const insertionIndex = currentIndex >= 0 ? currentIndex + 1 : 0
  return [...withoutTarget.slice(0, insertionIndex), track, ...withoutTarget.slice(insertionIndex)]
}

export function appendTrackOnce(queue: Track[], track: Track): Track[] {
  return queue.some((item) => item.id === track.id) ? queue : [...queue, track]
}

export function isTemporaryOnlineTrack(track: Track): boolean {
  return track.id.toLowerCase().startsWith('online-')
}
