import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from './api'
import { insertTrackNext } from './trackActions'
import type { RoomPlaybackState, Track } from './types'

export function usePlayer() {
  const audio = useMemo(() => new Audio(), [])
  const restored = useMemo(readPlaybackState, [])
  const [queue, setQueue] = useState<Track[]>(restored.queue)
  const [index, setIndex] = useState(restored.index)
  const [playing, setPlaying] = useState(false)
  const [position, setPosition] = useState(restored.position)
  const [duration, setDuration] = useState(0)
  const [volume, setVolumeState] = useState(() => Number(localStorage.getItem('shine-volume') ?? .8))
  const roomMode = useRef(false)
  const independentState = useRef<{ queue: Track[]; index: number; position: number } | null>(null)
  const current = queue[index] ?? null

  const playIndex = useCallback(async (nextIndex: number, autoPlay = true) => {
    const next = queue[nextIndex]
    if (!next) return
    setIndex(nextIndex)
    if (audio.src !== `${location.origin}/api/media/${next.id}/stream`) {
      audio.src = `/api/media/${next.id}/stream`
      audio.load()
      void api.recordHistory(next.id).catch(() => undefined)
    }
    if (autoPlay) await audio.play().catch(() => setPlaying(false))
  }, [audio, queue])

  const playTrack = useCallback((track: Track, sourceQueue: Track[]) => {
    const nextQueue = sourceQueue.length ? sourceQueue : [track]
    setQueue(nextQueue)
    const nextIndex = Math.max(0, nextQueue.findIndex((item) => item.id === track.id))
    setIndex(nextIndex)
    audio.src = `/api/media/${track.id}/stream`
    audio.load()
    void api.recordHistory(track.id).catch(() => undefined)
    void audio.play().catch(() => setPlaying(false))
  }, [audio])

  const appendTracks = useCallback((tracks: Track[]) => {
    setQueue((current) => {
      const known = new Set(current.map((track) => track.id))
      const additions = tracks.filter((track) => {
        if (known.has(track.id)) return false
        known.add(track.id)
        return true
      })
      return additions.length ? [...current, ...additions] : current
    })
  }, [])

  const insertNext = useCallback((track: Track) => {
    const currentId = queue[index]?.id ?? null
    const nextQueue = insertTrackNext(queue, currentId, track)
    setQueue(nextQueue)
    if (currentId) setIndex(nextQueue.findIndex((item) => item.id === currentId))
  }, [index, queue])

  const continueWith = useCallback((tracks: Track[]) => {
    const known = new Set(queue.map((track) => track.id))
    const additions = tracks.filter((track) => {
      if (known.has(track.id)) return false
      known.add(track.id)
      return true
    })
    const next = queue[index + 1] ?? additions[0]
    if (!next) return false
    if (!queue[index + 1]) setQueue([...queue, ...additions])
    setIndex(index + 1)
    audio.src = `/api/media/${next.id}/stream`
    audio.load()
    void api.recordHistory(next.id).catch(() => undefined)
    void audio.play().catch(() => setPlaying(false))
    return true
  }, [audio, index, queue])

  const hydrateTracks = useCallback((tracks: Track[]) => {
    if (!tracks.length) return
    const byId = new Map(tracks.map((track) => [track.id, track]))
    setQueue((current) => current.map((track) => byId.get(track.id) ?? track))
  }, [])

  const moveQueueItem = useCallback((from: number, to: number) => {
    if (from === to || from < 0 || to < 0 || from >= queue.length || to >= queue.length) return
    const nextQueue = [...queue]
    const [moved] = nextQueue.splice(from, 1)
    nextQueue.splice(to, 0, moved)
    const currentId = current?.id
    setQueue(nextQueue)
    setIndex(currentId ? Math.max(0, nextQueue.findIndex((track) => track.id === currentId)) : -1)
  }, [current?.id, queue])

  const removeQueueItem = useCallback((removeIndex: number) => {
    if (removeIndex < 0 || removeIndex >= queue.length) return
    const removedCurrent = removeIndex === index
    const nextQueue = queue.filter((_, itemIndex) => itemIndex !== removeIndex)
    if (!nextQueue.length) {
      audio.pause()
      audio.removeAttribute('src')
      audio.load()
      setQueue([])
      setIndex(-1)
      setPosition(0)
      setDuration(0)
      return
    }
    const nextIndex = removedCurrent
      ? Math.min(removeIndex, nextQueue.length - 1)
      : Math.max(0, nextQueue.findIndex((track) => track.id === current?.id))
    setQueue(nextQueue)
    setIndex(nextIndex)
    if (removedCurrent) {
      const next = nextQueue[nextIndex]
      audio.src = `/api/media/${next.id}/stream`
      audio.load()
      void api.recordHistory(next.id).catch(() => undefined)
      if (playing) void audio.play().catch(() => setPlaying(false))
    }
  }, [audio, current?.id, index, playing, queue])

  const toggle = useCallback(() => {
    if (!current) return
    if (audio.paused) void audio.play()
    else audio.pause()
  }, [audio, current])

  const next = useCallback(() => {
    if (queue.length === 0) return
    if (index + 1 < queue.length) void playIndex(index + 1)
  }, [index, playIndex, queue.length])

  const previous = useCallback(() => {
    if (audio.currentTime > 5) audio.currentTime = 0
    else if (queue.length) void playIndex((index - 1 + queue.length) % queue.length)
  }, [audio, index, playIndex, queue.length])
  const seek = useCallback((seconds: number) => { audio.currentTime = seconds }, [audio])
  const setVolume = useCallback((value: number) => {
    audio.volume = value
    setVolumeState(value)
    localStorage.setItem('shine-volume', String(value))
  }, [audio])

  const enterRoomMode = useCallback(() => {
    if (!roomMode.current) independentState.current = { queue, index, position: audio.currentTime }
    roomMode.current = true
    audio.pause()
  }, [audio, index, queue])

  const leaveRoomMode = useCallback(() => {
    roomMode.current = false
    const snapshot = independentState.current
    independentState.current = null
    if (snapshot) {
      setQueue(snapshot.queue)
      setIndex(snapshot.index)
      setPosition(snapshot.position)
      setDuration(Number.isFinite(audio.duration) ? audio.duration : 0)
    }
    setPlaying(false)
  }, [audio])

  const reflectRoomState = useCallback((state: RoomPlaybackState, tracks: Track[], livePositionMs?: number, liveDurationMs?: number) => {
    const nextQueue = state.queue.map((id) => tracks.find((track) => track.id === id)).filter(Boolean) as Track[]
    const target = tracks.find((track) => track.id === state.currentTrackId)
    if (!target) return
    const targetIndex = Math.max(0, nextQueue.findIndex((track) => track.id === target.id))
    setQueue(nextQueue.length ? nextQueue : [target])
    setIndex(targetIndex)
    setPosition((livePositionMs ?? state.positionMs) / 1000)
    setDuration((liveDurationMs ?? target.durationMs) / 1000)
    setPlaying(state.playing)
  }, [])

  useEffect(() => {
    audio.preload = 'auto'
    audio.volume = volume
    const update = () => {
      if (roomMode.current) return
      setPosition(audio.currentTime)
      setDuration(Number.isFinite(audio.duration) ? audio.duration : 0)
    }
    const onPlay = () => setPlaying(true)
    const onPause = () => setPlaying(false)
    audio.addEventListener('timeupdate', update)
    audio.addEventListener('durationchange', update)
    audio.addEventListener('play', onPlay)
    audio.addEventListener('pause', onPause)
    return () => {
      audio.pause()
      audio.removeEventListener('timeupdate', update)
      audio.removeEventListener('durationchange', update)
      audio.removeEventListener('play', onPlay)
      audio.removeEventListener('pause', onPause)
    }
  }, [audio])

  useEffect(() => {
    if (!current || audio.src) return
    audio.src = `/api/media/${current.id}/stream`
    audio.load()
    const restorePosition = () => { audio.currentTime = restored.position }
    audio.addEventListener('loadedmetadata', restorePosition, { once: true })
    return () => audio.removeEventListener('loadedmetadata', restorePosition)
  }, [audio, current, restored.position])

  useEffect(() => {
    if (roomMode.current) return
    localStorage.setItem('shine-playback', JSON.stringify({ queue, index, position }))
  }, [index, position, queue])

  useEffect(() => {
    if (!current || !('mediaSession' in navigator)) return
    navigator.mediaSession.metadata = new MediaMetadata({ title: current.title, artist: current.artist, album: current.album })
    navigator.mediaSession.setActionHandler('play', () => { if (!roomMode.current) void audio.play() })
    navigator.mediaSession.setActionHandler('pause', () => { if (!roomMode.current) audio.pause() })
    navigator.mediaSession.setActionHandler('nexttrack', () => { if (!roomMode.current) next() })
    navigator.mediaSession.setActionHandler('previoustrack', () => { if (!roomMode.current) previous() })
  }, [audio, current, next, previous])

  return { audio, current, queue, index, playing, position, duration, volume, playTrack, appendTracks, insertNext, continueWith, hydrateTracks, moveQueueItem, removeQueueItem, toggle, next, previous, seek, setVolume, enterRoomMode, leaveRoomMode, reflectRoomState }
}

export type PlayerController = ReturnType<typeof usePlayer>

function readPlaybackState(): { queue: Track[]; index: number; position: number } {
  try {
    const value = JSON.parse(localStorage.getItem('shine-playback') ?? '{}') as Partial<{ queue: Track[]; index: number; position: number }>
    return {
      queue: Array.isArray(value.queue) ? value.queue : [],
      index: typeof value.index === 'number' ? value.index : -1,
      position: typeof value.position === 'number' ? value.position : 0,
    }
  } catch {
    return { queue: [], index: -1, position: 0 }
  }
}
