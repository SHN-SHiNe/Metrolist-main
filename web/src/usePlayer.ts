import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api } from './api'
import type { RoomPlaybackState, Track } from './types'
import { driftCorrection, scheduledDelay } from './sync'

export function usePlayer() {
  const audio = useMemo(() => new Audio(), [])
  const restored = useMemo(readPlaybackState, [])
  const [queue, setQueue] = useState<Track[]>(restored.queue)
  const [index, setIndex] = useState(restored.index)
  const [playing, setPlaying] = useState(false)
  const [position, setPosition] = useState(restored.position)
  const [duration, setDuration] = useState(0)
  const [volume, setVolumeState] = useState(() => Number(localStorage.getItem('shine-volume') ?? .8))
  const remoteTimer = useRef<number | null>(null)
  const pendingCanPlay = useRef<(() => void) | null>(null)
  const audioContext = useRef<AudioContext | null>(null)
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

  const toggle = useCallback(() => {
    if (!current) return
    if (audio.paused) void audio.play()
    else audio.pause()
  }, [audio, current])

  const next = useCallback(() => {
    if (queue.length === 0) return
    void playIndex((index + 1) % queue.length)
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

  const enableAudio = useCallback(async () => {
    if (!audioContext.current) {
      const context = new AudioContext()
      context.createMediaElementSource(audio).connect(context.destination)
      audioContext.current = context
    }
    await audioContext.current.resume()
  }, [audio])

  const outputLatencyMs = useCallback(() => {
    const context = audioContext.current
    if (!context) return 0
    const timestampLatency = typeof context.getOutputTimestamp === 'function'
      ? Math.max(0, (context.currentTime - (context.getOutputTimestamp().contextTime ?? context.currentTime)) * 1000)
      : 0
    return Math.max(timestampLatency, ((context.baseLatency ?? 0) + ('outputLatency' in context ? Number(context.outputLatency) : 0)) * 1000)
  }, [])

  const applyRoomState = useCallback((state: RoomPlaybackState, tracks: Track[], serverOffset: number, deviceDelay: number) => {
    if (remoteTimer.current !== null) window.clearTimeout(remoteTimer.current)
    if (pendingCanPlay.current) audio.removeEventListener('canplay', pendingCanPlay.current)
    pendingCanPlay.current = null
    const nextQueue = state.queue.map((id) => tracks.find((track) => track.id === id)).filter(Boolean) as Track[]
    const target = tracks.find((track) => track.id === state.currentTrackId)
    if (!target) return
    const targetIndex = Math.max(0, nextQueue.findIndex((track) => track.id === target.id))
    setQueue(nextQueue.length ? nextQueue : [target])
    setIndex(targetIndex)
    if (audio.src !== `${location.origin}/api/media/${target.id}/stream`) {
      audio.src = `/api/media/${target.id}/stream`
      audio.load()
    }
    const delay = Math.max(0, scheduledDelay(state.effectiveAt, serverOffset, deviceDelay, Date.now()) - outputLatencyMs())
    const startAtSynchronizedPosition = () => {
      pendingCanPlay.current = null
      const elapsed = state.playing ? Math.max(0, Date.now() + serverOffset - state.effectiveAt) : 0
      audio.currentTime = (state.positionMs + elapsed) / 1000
      if (state.playing) void audio.play().catch(() => setPlaying(false))
      else audio.pause()
    }
    remoteTimer.current = window.setTimeout(() => {
      if (!state.playing || audio.readyState >= HTMLMediaElement.HAVE_FUTURE_DATA) startAtSynchronizedPosition()
      else {
        pendingCanPlay.current = startAtSynchronizedPosition
        audio.addEventListener('canplay', startAtSynchronizedPosition, { once: true })
      }
    }, delay)
  }, [audio, outputLatencyMs])

  useEffect(() => {
    audio.preload = 'auto'
    audio.volume = volume
    const update = () => {
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
    localStorage.setItem('shine-playback', JSON.stringify({ queue, index, position }))
  }, [index, position, queue])

  useEffect(() => {
    if (!current || !('mediaSession' in navigator)) return
    navigator.mediaSession.metadata = new MediaMetadata({ title: current.title, artist: current.artist, album: current.album })
    navigator.mediaSession.setActionHandler('play', () => void audio.play())
    navigator.mediaSession.setActionHandler('pause', () => audio.pause())
    navigator.mediaSession.setActionHandler('nexttrack', next)
    navigator.mediaSession.setActionHandler('previoustrack', previous)
  }, [audio, current, next, previous])

  useEffect(() => {
    const timer = window.setInterval(() => {
      if (!playing || audio.playbackRate === 1) return
      audio.playbackRate = 1
    }, 3000)
    return () => window.clearInterval(timer)
  }, [audio, playing])

  const correctDrift = useCallback((expectedPositionMs: number) => {
    const drift = expectedPositionMs - audio.currentTime * 1000
    const correction = driftCorrection(drift)
    if (correction.seek) audio.currentTime = expectedPositionMs / 1000
    audio.playbackRate = correction.rate
  }, [audio])

  return { audio, current, queue, index, playing, position, duration, volume, playTrack, toggle, next, previous, seek, setVolume, enableAudio, applyRoomState, correctDrift }
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
