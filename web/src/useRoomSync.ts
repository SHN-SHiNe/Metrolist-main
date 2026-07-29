import { SendspinPlayer } from '@sendspin/sendspin-js'
import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api'
import { clampSendspinDelay, sendspinBaseUrl } from './sendspin'
import type { RoomPlaybackState, Track } from './types'
import type { PlayerController } from './usePlayer'

export function useRoomSync(player: PlayerController, tracks: Track[]) {
  const sendspin = useRef<SendspinPlayer | null>(null)
  const joinedRoom = useRef<string | null>(null)
  const playerRef = useRef(player)
  const tracksRef = useRef(tracks)
  const metadataRef = useRef<{ title?: string | null; artist?: string | null; album?: string | null }>({})
  const [roomId, setRoomId] = useState<string | null>(null)
  const [members, setMembers] = useState(0)
  const [queueIds, setQueueIds] = useState<string[]>([])
  const [status, setStatus] = useState<'offline' | 'connecting' | 'joined' | 'error'>('offline')
  const [serverOffset, setServerOffset] = useState(0)
  const [deviceDelay, setDeviceDelayState] = useState(() => clampSendspinDelay(Number(localStorage.getItem('shine-device-delay') ?? 0)))

  useEffect(() => { playerRef.current = player }, [player])
  useEffect(() => { tracksRef.current = tracks }, [tracks])
  useEffect(() => { sendspin.current?.setVolume(player.volume * 100) }, [player.volume])

  const leave = useCallback(() => {
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
  }, [])

  const reflect = useCallback(async (id: string) => {
    const detail = await api.room(id)
    if (joinedRoom.current !== id) return
    setMembers(detail.summary.memberCount)
    setQueueIds(detail.state.queue)
    const progress = sendspin.current?.trackProgress
    const metadata = metadataRef.current
    const currentId = detail.state.currentTrackId
    const knownTracks = tracksRef.current
    const displayTracks = currentId && !knownTracks.some((track) => track.id === currentId)
      ? [...knownTracks, {
          id: currentId,
          title: metadata.title || '同步房间正在播放',
          artist: metadata.artist || 'Sendspin',
          album: metadata.album || '',
          durationMs: progress?.durationMs ?? 0,
        }]
      : knownTracks
    playerRef.current.reflectRoomState(detail.state, displayTracks, progress?.positionMs, progress?.durationMs)
    setServerOffset(sendspin.current?.syncInfo.syncErrorMs ?? 0)
  }, [])

  const join = useCallback(async (id: string, ready?: () => Promise<unknown>) => {
    leave()
    setStatus('connecting')
    setRoomId(id)
    joinedRoom.current = id
    playerRef.current.enterRoomMode()
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
        onReconnecting: () => { if (joinedRoom.current === id) setStatus('connecting') },
        onReconnected: () => { if (joinedRoom.current === id) setStatus('joined') },
        onExhausted: () => { if (joinedRoom.current === id) setStatus('error') },
      },
    })
    sendspin.current = sdk
    const unlockPromise = sdk.unlock()
    const readyPromise = ready?.()
    try {
      await unlockPromise
      await readyPromise
      await sdk.connect()
      if (joinedRoom.current !== id) {
        sdk.disconnect()
        return
      }
      sdk.setVolume(playerRef.current.volume * 100)
      setStatus('joined')
      await reflect(id)
    } catch {
      if (joinedRoom.current === id) setStatus('error')
    }
  }, [deviceDelay, leave, reflect])

  const command = useCallback((partial: Partial<RoomPlaybackState>) => {
    const id = joinedRoom.current
    if (!id) return false
    void api.updateRoom(id, partial).then((detail) => {
      if (joinedRoom.current !== id) return
      playerRef.current.reflectRoomState(detail.state, tracksRef.current)
      setMembers(detail.summary.memberCount)
      setQueueIds(detail.state.queue)
    }).catch(() => setStatus('error'))
    return true
  }, [])

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

  return { roomId, members, queueIds, status, serverOffset, deviceDelay, setDeviceDelay, join, leave, command }
}

function clientId(): string {
  const key = 'shine-client-id'
  const existing = localStorage.getItem(key)
  if (existing) return existing
  const next = crypto.randomUUID()
  localStorage.setItem(key, next)
  return next
}

function deviceName(): string {
  const mobile = /Android|iPhone|iPad/i.test(navigator.userAgent)
  return `SHiNe Web · ${mobile ? '手机' : '电脑'}`
}
