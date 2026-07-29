import { SendspinPlayer } from '@sendspin/sendspin-js'
import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api'
import { clampSendspinDelay, sendspinBaseUrl } from './sendspin'
import { randomId } from './randomId'
import type { RoomPlaybackState, Track } from './types'
import type { PlayerController } from './usePlayer'

const MISSING_TRACK_RETRY_BASE_MS = 1_000
const MISSING_TRACK_RETRY_MAX_MS = 30_000

export function useRoomSync(player: PlayerController, tracks: Track[]) {
  const sendspin = useRef<SendspinPlayer | null>(null)
  const joinedRoom = useRef<string | null>(null)
  const roomSession = useRef(0)
  const snapshotSequence = useRef(0)
  const appliedSnapshot = useRef<{ session: number; version: number; requestId: number } | null>(null)
  const playerRef = useRef(player)
  const tracksRef = useRef(tracks)
  const roomTrackCache = useRef(new Map<string, Track>())
  const missingTrackBackoff = useRef(new Map<string, { attempts: number; retryAt: number }>())
  const metadataRef = useRef<{ title?: string | null; artist?: string | null; album?: string | null }>({})
  const [roomId, setRoomId] = useState<string | null>(null)
  const [members, setMembers] = useState(0)
  const [queueIds, setQueueIds] = useState<string[]>([])
  const [status, setStatus] = useState<'offline' | 'connecting' | 'joined' | 'reconnecting' | 'error'>('offline')
  const [serverOffset, setServerOffset] = useState(0)
  const [deviceDelay, setDeviceDelayState] = useState(() => clampSendspinDelay(Number(localStorage.getItem('shine-device-delay') ?? 0)))

  useEffect(() => { playerRef.current = player }, [player])
  useEffect(() => {
    tracksRef.current = tracks
    tracks.forEach((track) => {
      roomTrackCache.current.set(track.id, track)
      missingTrackBackoff.current.delete(track.id)
    })
  }, [tracks])
  useEffect(() => { sendspin.current?.setVolume(player.volume * 100) }, [player.volume])

  const leave = useCallback(() => {
    roomSession.current += 1
    appliedSnapshot.current = null
    joinedRoom.current = null
    sendspin.current?.disconnect()
    sendspin.current = null
    metadataRef.current = {}
    playerRef.current.leaveRoomMode()
    setRoomId(null)
    setStatus('offline')
    setMembers(0)
    setQueueIds([])
    setServerOffset(0)
    roomTrackCache.current.clear()
    missingTrackBackoff.current.clear()
  }, [])

  const beginSnapshotRequest = useCallback(() => ({
    session: roomSession.current,
    requestId: ++snapshotSequence.current,
  }), [])

  const isActiveRequest = useCallback((id: string, request: { session: number }) => (
    joinedRoom.current === id && roomSession.current === request.session
  ), [])

  const acceptSnapshot = useCallback((id: string, version: number, request: { session: number; requestId: number }) => {
    if (!isActiveRequest(id, request)) return false
    const applied = appliedSnapshot.current
    if (applied?.session === request.session) {
      if (version < applied.version) return false
      if (version === applied.version && request.requestId <= applied.requestId) return false
    }
    appliedSnapshot.current = { ...request, version }
    return true
  }, [isActiveRequest])

  const reflect = useCallback(async (id: string) => {
    const request = beginSnapshotRequest()
    const detail = await api.room(id)
    if (!isActiveRequest(id, request)) return
    const progress = sendspin.current?.trackProgress
    const metadata = metadataRef.current
    const currentId = detail.state.currentTrackId
    const candidateIds = [...new Set([
      ...(currentId ? [currentId] : []),
      ...detail.state.queue,
    ])]
    const now = Date.now()
    const missingIds = candidateIds.filter((trackId) => {
      if (roomTrackCache.current.has(trackId)) return false
      const retry = missingTrackBackoff.current.get(trackId)
      return !retry || retry.retryAt <= now
    }).slice(0, 100)
    if (missingIds.length) {
      try {
        const fetched = await api.tracks(missingIds)
        if (!isActiveRequest(id, request)) return
        fetched.forEach((track) => {
          roomTrackCache.current.set(track.id, track)
          missingTrackBackoff.current.delete(track.id)
        })
        const fetchedIds = new Set(fetched.map((track) => track.id))
        missingIds.filter((trackId) => !fetchedIds.has(trackId)).forEach((trackId) => {
          const attempts = (missingTrackBackoff.current.get(trackId)?.attempts ?? 0) + 1
          const delay = Math.min(MISSING_TRACK_RETRY_BASE_MS * 2 ** (attempts - 1), MISSING_TRACK_RETRY_MAX_MS)
          missingTrackBackoff.current.set(trackId, { attempts, retryAt: Date.now() + delay })
        })
      } catch {
        // A transient batch lookup failure must leave these IDs eligible for the next reflection.
      }
    }
    if (!acceptSnapshot(id, detail.summary.version, request)) return
    setMembers(detail.summary.memberCount)
    setQueueIds(detail.state.queue)
    const displayTracks = roomTracksForSnapshot(
      detail.state,
      roomTrackCache.current,
      tracksRef.current,
      metadata,
      progress?.durationMs,
    )
    playerRef.current.reflectRoomState(detail.state, displayTracks, progress?.positionMs, progress?.durationMs)
    setServerOffset(sendspin.current?.syncInfo.syncErrorMs ?? 0)
  }, [acceptSnapshot, beginSnapshotRequest, isActiveRequest])

  const join = useCallback(async (id: string, ready?: () => Promise<unknown>) => {
    leave()
    setStatus('connecting')
    setRoomId(id)
    joinedRoom.current = id
    playerRef.current.enterRoomMode()
    try {
      const sdk = new SendspinPlayer({
        playerId: clientId(),
        clientName: deviceName(),
        baseUrl: sendspinBaseUrl(id, location.origin),
        correctionMode: 'sync',
        syncDelay: deviceDelay,
        onStateChange: (state) => {
          metadataRef.current = { ...metadataRef.current, ...state.serverState.metadata }
          void reflect(id)
        },
        reconnect: {
          onReconnecting: () => { if (joinedRoom.current === id) setStatus('reconnecting') },
          onReconnected: () => { if (joinedRoom.current === id) setStatus('joined') },
          onExhausted: () => { if (joinedRoom.current === id) setStatus('error') },
        },
      })
      sendspin.current = sdk
      await withTimeout(
        Promise.all([sdk.unlock(), ready?.()]).then(() => sdk.connect()),
        12_000,
        '连接 Sendspin 超时，请刷新页面后重试',
      )
      if (joinedRoom.current !== id) {
        sdk.disconnect()
        return
      }
      sdk.setVolume(playerRef.current.volume * 100)
      setStatus('joined')
      await reflect(id)
    } catch (error) {
      if (joinedRoom.current === id) leave()
      throw error
    }
  }, [deviceDelay, leave, reflect])

  const command = useCallback((partial: Partial<RoomPlaybackState>) => {
    const id = joinedRoom.current
    if (!id) return false
    const request = beginSnapshotRequest()
    void api.updateRoom(id, partial).then((detail) => {
      if (!acceptSnapshot(id, detail.summary.version, request)) return
      const progress = sendspin.current?.trackProgress
      const displayTracks = roomTracksForSnapshot(
        detail.state,
        roomTrackCache.current,
        tracksRef.current,
        metadataRef.current,
        progress?.durationMs,
      )
      playerRef.current.reflectRoomState(detail.state, displayTracks, progress?.positionMs, progress?.durationMs)
      setMembers(detail.summary.memberCount)
      setQueueIds(detail.state.queue)
    }).catch(() => { if (isActiveRequest(id, request)) setStatus('error') })
    return true
  }, [acceptSnapshot, beginSnapshotRequest, isActiveRequest])

  const autofill = useCallback(async (recentTrackIds: string[] = []) => {
    const id = joinedRoom.current
    if (!id) return null
    const detail = await api.autofillRoom(id, recentTrackIds)
    if (joinedRoom.current !== id) return null
    await reflect(id)
    return detail
  }, [reflect])

  const setDeviceDelay = useCallback((value: number) => {
    const next = clampSendspinDelay(value)
    setDeviceDelayState(next)
    localStorage.setItem('shine-device-delay', String(next))
    sendspin.current?.setSyncDelay(next)
  }, [])

  useEffect(() => {
    if (!roomId || status === 'offline') return
    const timer = window.setInterval(() => {
      void reflect(roomId).catch(() => { if (joinedRoom.current === roomId) setStatus('error') })
    }, 1000)
    return () => window.clearInterval(timer)
  }, [reflect, roomId, status])

  useEffect(() => leave, [leave])

  return { roomId, members, queueIds, status, serverOffset, deviceDelay, setDeviceDelay, join, leave, command, autofill }
}

function roomTracksForSnapshot(
  state: RoomPlaybackState,
  cache: Map<string, Track>,
  currentTracks: Track[],
  metadata: { title?: string | null; artist?: string | null; album?: string | null },
  liveDurationMs?: number,
): Track[] {
  const known = new Map(cache)
  currentTracks.forEach((track) => known.set(track.id, track))
  const currentId = state.currentTrackId
  if (currentId && !known.has(currentId)) {
    known.set(currentId, {
      id: currentId,
      title: metadata.title || '同步房间正在播放',
      artist: metadata.artist || 'Sendspin',
      album: metadata.album || '',
      durationMs: liveDurationMs ?? 0,
    })
  }
  return [...known.values()]
}

function withTimeout<T>(promise: Promise<T>, timeoutMs: number, message: string): Promise<T> {
  return new Promise((resolve, reject) => {
    const timeout = window.setTimeout(() => reject(new Error(message)), timeoutMs)
    promise.then(
      (value) => { window.clearTimeout(timeout); resolve(value) },
      (error) => { window.clearTimeout(timeout); reject(error) },
    )
  })
}

function clientId(): string {
  const key = 'shine-client-id'
  const existing = localStorage.getItem(key)
  if (existing) return existing
  const next = randomId()
  localStorage.setItem(key, next)
  return next
}

function deviceName(): string {
  const mobile = /Android|iPhone|iPad/i.test(navigator.userAgent)
  return `SHiNe Web · ${mobile ? '手机' : '电脑'}`
}
