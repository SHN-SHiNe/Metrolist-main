import { useCallback, useEffect, useRef, useState } from 'react'
import type { RoomEnvelope, RoomPlaybackState, Track } from './types'
import type { PlayerController } from './usePlayer'
import { estimateServerOffset, type ClockSample } from './sync'

export function useRoomSync(player: PlayerController, tracks: Track[]) {
  const socket = useRef<WebSocket | null>(null)
  const samples = useRef<ClockSample[]>([])
  const latestState = useRef<RoomPlaybackState | null>(null)
  const playerRef = useRef(player)
  const tracksRef = useRef(tracks)
  const serverOffsetRef = useRef(0)
  const deviceDelayRef = useRef(0)
  const [roomId, setRoomId] = useState<string | null>(null)
  const [members, setMembers] = useState(0)
  const [status, setStatus] = useState<'offline' | 'connecting' | 'joined' | 'error'>('offline')
  const [serverOffset, setServerOffset] = useState(0)
  const [deviceDelay, setDeviceDelayState] = useState(() => Number(localStorage.getItem('shine-device-delay') ?? 0))

  useEffect(() => { playerRef.current = player }, [player])
  useEffect(() => { tracksRef.current = tracks }, [tracks])
  useEffect(() => { serverOffsetRef.current = serverOffset }, [serverOffset])
  useEffect(() => { deviceDelayRef.current = deviceDelay }, [deviceDelay])

  const leave = useCallback(() => {
    socket.current?.close()
    socket.current = null
    latestState.current = null
    setRoomId(null)
    setStatus('offline')
    setMembers(0)
  }, [])

  const join = useCallback((id: string) => {
    leave()
    void playerRef.current.enableAudio().catch(() => undefined)
    setStatus('connecting')
    setRoomId(id)
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:'
    const ws = new WebSocket(`${protocol}//${location.host}/ws/rooms/${id}`)
    socket.current = ws
    ws.addEventListener('open', () => {
      setStatus('joined')
      const sentAt = Date.now()
      ws.send(JSON.stringify({ type: 'ping', clientId: clientId(), clientTime: sentAt }))
    })
    ws.addEventListener('message', (event) => {
      const message = JSON.parse(String(event.data)) as RoomEnvelope
      setMembers(message.memberCount)
      if (message.type === 'pong' && message.clientTime != null) {
        samples.current = [...samples.current.slice(-9), { sentAt: message.clientTime, receivedAt: Date.now(), serverTime: message.serverTime }]
        setServerOffset(estimateServerOffset(samples.current))
      }
      if (message.state && (message.type === 'snapshot' || message.type === 'state')) {
        latestState.current = message.state
        playerRef.current.applyRoomState(message.state, tracksRef.current, serverOffsetRef.current, deviceDelayRef.current)
      }
    })
    ws.addEventListener('error', () => setStatus('error'))
    ws.addEventListener('close', () => setStatus('offline'))
  }, [leave])

  const command = useCallback((partial: Partial<RoomPlaybackState>) => {
    if (socket.current?.readyState !== WebSocket.OPEN) return false
    socket.current.send(JSON.stringify({
      type: 'state', clientId: clientId(), ...partial,
      effectiveAt: Date.now() + serverOffset + 900,
    }))
    return true
  }, [serverOffset])

  const setDeviceDelay = useCallback((value: number) => {
    setDeviceDelayState(value)
    localStorage.setItem('shine-device-delay', String(value))
  }, [])

  useEffect(() => {
    if (status !== 'joined') return
    const ping = window.setInterval(() => {
      if (socket.current?.readyState === WebSocket.OPEN) {
        const sentAt = Date.now()
        socket.current.send(JSON.stringify({ type: 'ping', clientId: clientId(), clientTime: sentAt }))
      }
    }, 5000)
    const drift = window.setInterval(() => {
      const state = latestState.current
      if (!state?.playing) return
      const expected = state.positionMs + Math.max(0, Date.now() + serverOffset - state.effectiveAt)
      player.correctDrift(expected)
    }, 3000)
    return () => { window.clearInterval(ping); window.clearInterval(drift) }
  }, [player, serverOffset, status])

  useEffect(() => leave, [leave])

  return { roomId, members, status, serverOffset, deviceDelay, setDeviceDelay, join, leave, command }
}

function clientId(): string {
  const key = 'shine-client-id'
  const existing = localStorage.getItem(key)
  if (existing) return existing
  const next = crypto.randomUUID()
  localStorage.setItem(key, next)
  return next
}
