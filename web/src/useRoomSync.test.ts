import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { RoomDetail, RoomPlaybackState, Track } from './types'
import type { PlayerController } from './usePlayer'

const apiMocks = vi.hoisted(() => ({
  room: vi.fn(),
  tracks: vi.fn(),
  updateRoom: vi.fn(),
  autofillRoom: vi.fn(),
}))

const sendspinHarness = vi.hoisted(() => ({
  options: null as null | { onStateChange: (state: { serverState: { metadata: Record<string, string | null> } }) => void },
}))

vi.mock('./api', () => ({ api: apiMocks }))

vi.mock('@sendspin/sendspin-js', () => ({
  SendspinPlayer: class {
    syncInfo = { syncErrorMs: 0 }
    trackProgress = { positionMs: 0, durationMs: 180_000 }

    constructor(options: NonNullable<typeof sendspinHarness.options>) {
      sendspinHarness.options = options
    }

    unlock() { return Promise.resolve() }
    connect() { return Promise.resolve() }
    disconnect() {}
    setVolume() {}
    setSyncDelay() {}
  },
}))

vi.mock('react', () => ({
  useCallback: <T,>(callback: T) => callback,
  useEffect: (effect: () => void | (() => void)) => { effect() },
  useRef: <T,>(value: T) => ({ current: value }),
  useState: <T,>(initial: T | (() => T)) => [typeof initial === 'function' ? (initial as () => T)() : initial, vi.fn()],
}))

import { useRoomSync } from './useRoomSync'

const localTrack = track('local', 'Local')
const remoteTrack = track('remote', 'Remote')
const newerTrack = track('newer', 'Newer')

describe('useRoomSync snapshot coordination', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    apiMocks.room.mockReset()
    apiMocks.tracks.mockReset().mockResolvedValue([])
    apiMocks.updateRoom.mockReset()
    apiMocks.autofillRoom.mockReset()
    sendspinHarness.options = null
    const storage = new Map<string, string>()
    vi.stubGlobal('localStorage', {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => { storage.set(key, value) },
    })
    vi.stubGlobal('location', { origin: 'http://nas:8767' })
    vi.stubGlobal('navigator', { userAgent: 'Vitest' })
    vi.stubGlobal('window', {
      setTimeout,
      clearTimeout,
      setInterval: vi.fn(() => 1),
      clearInterval: vi.fn(),
    })
  })

  it('does not let an older concurrent reflection overwrite a newer snapshot', async () => {
    const player = playerStub()
    apiMocks.room.mockResolvedValueOnce(roomDetail(1, localTrack.id, [localTrack.id]))
    const sync = useRoomSync(player.controller, [localTrack, remoteTrack, newerTrack])
    await sync.join('living-room')
    player.reflect.mockClear()

    const older = deferred<RoomDetail>()
    const newer = deferred<RoomDetail>()
    apiMocks.room.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise)
    sendspinHarness.options?.onStateChange({ serverState: { metadata: {} } })
    sendspinHarness.options?.onStateChange({ serverState: { metadata: {} } })

    newer.resolve(roomDetail(3, newerTrack.id, [newerTrack.id]))
    await flushPromises()
    older.resolve(roomDetail(2, remoteTrack.id, [remoteTrack.id]))
    await flushPromises()

    expect(player.reflect).toHaveBeenCalledTimes(1)
    expect(player.reflect.mock.calls[0][0].currentTrackId).toBe(newerTrack.id)
  })

  it('uses request order to break ties between equal-version reflections', async () => {
    const player = playerStub()
    apiMocks.room.mockResolvedValueOnce(roomDetail(1, localTrack.id, [localTrack.id]))
    const sync = useRoomSync(player.controller, [localTrack, remoteTrack, newerTrack])
    await sync.join('living-room')
    player.reflect.mockClear()

    const older = deferred<RoomDetail>()
    const newer = deferred<RoomDetail>()
    apiMocks.room.mockReturnValueOnce(older.promise).mockReturnValueOnce(newer.promise)
    sendspinHarness.options?.onStateChange({ serverState: { metadata: {} } })
    sendspinHarness.options?.onStateChange({ serverState: { metadata: {} } })

    newer.resolve(roomDetail(2, newerTrack.id, [newerTrack.id]))
    await flushPromises()
    older.resolve(roomDetail(2, remoteTrack.id, [remoteTrack.id]))
    await flushPromises()

    expect(player.reflect).toHaveBeenCalledTimes(1)
    expect(player.reflect.mock.calls[0][0].currentTrackId).toBe(newerTrack.id)
  })

  it('retries unresolved room track IDs after a transient tracks request failure', async () => {
    const player = playerStub()
    apiMocks.room.mockResolvedValue(roomDetail(1, remoteTrack.id, [remoteTrack.id]))
    apiMocks.tracks.mockReset().mockRejectedValueOnce(new Error('temporary outage')).mockResolvedValueOnce([remoteTrack])
    const sync = useRoomSync(player.controller, [localTrack])

    await sync.join('living-room')
    sendspinHarness.options?.onStateChange({ serverState: { metadata: {} } })
    await flushPromises()

    expect(apiMocks.tracks).toHaveBeenCalledTimes(2)
    const reflectedTracks = player.reflect.mock.calls.at(-1)?.[1] as Track[]
    expect(reflectedTracks).toContainEqual(remoteTrack)
  })

  it('hydrates the current track even when it is not present in the room queue', async () => {
    const player = playerStub()
    apiMocks.room.mockResolvedValue(roomDetail(1, remoteTrack.id, []))
    apiMocks.tracks.mockResolvedValueOnce([remoteTrack])
    const sync = useRoomSync(player.controller, [localTrack])

    await sync.join('living-room')

    expect(apiMocks.tracks).toHaveBeenCalledWith([remoteTrack.id])
    const reflectedTracks = player.reflect.mock.calls.at(-1)?.[1] as Track[]
    expect(reflectedTracks).toContainEqual(remoteTrack)
  })

  it('retries an empty track lookup after its negative-cache backoff expires', async () => {
    let now = 10_000
    vi.spyOn(Date, 'now').mockImplementation(() => now)
    const player = playerStub()
    apiMocks.room.mockResolvedValue(roomDetail(1, remoteTrack.id, [remoteTrack.id]))
    apiMocks.tracks.mockResolvedValueOnce([]).mockResolvedValueOnce([remoteTrack])
    const sync = useRoomSync(player.controller, [localTrack])

    await sync.join('living-room')
    sendspinHarness.options?.onStateChange({ serverState: { metadata: {} } })
    await flushPromises()
    expect(apiMocks.tracks).toHaveBeenCalledTimes(1)

    now += 2_000
    sendspinHarness.options?.onStateChange({ serverState: { metadata: {} } })
    await flushPromises()

    expect(apiMocks.tracks).toHaveBeenCalledTimes(2)
    const reflectedTracks = player.reflect.mock.calls.at(-1)?.[1] as Track[]
    expect(reflectedTracks).toContainEqual(remoteTrack)
  })

  it('replays command responses with tracks previously hydrated into the room cache', async () => {
    const player = playerStub()
    apiMocks.room.mockResolvedValueOnce(roomDetail(1, remoteTrack.id, [remoteTrack.id]))
    apiMocks.tracks.mockReset().mockResolvedValueOnce([remoteTrack])
    apiMocks.updateRoom.mockResolvedValueOnce(roomDetail(2, remoteTrack.id, [remoteTrack.id], true))
    const sync = useRoomSync(player.controller, [localTrack])
    await sync.join('living-room')
    player.reflect.mockClear()

    expect(sync.command({ playing: true })).toBe(true)
    await flushPromises()

    const reflectedTracks = player.reflect.mock.calls[0][1] as Track[]
    expect(reflectedTracks).toContainEqual(remoteTrack)
  })
})

function playerStub() {
  const reflect = vi.fn()
  return {
    reflect,
    controller: {
      volume: 0.8,
      enterRoomMode: vi.fn(),
      leaveRoomMode: vi.fn(),
      reflectRoomState: reflect,
    } as unknown as PlayerController,
  }
}

function track(id: string, title: string): Track {
  return { id, title, artist: 'Artist', album: 'Album', durationMs: 180_000 }
}

function roomDetail(version: number, currentTrackId: string, queue: string[], playing = false): RoomDetail {
  const state: RoomPlaybackState = { queue, currentTrackId, positionMs: 0, playing, effectiveAt: 0 }
  return { summary: { id: 'living-room', name: 'Living room', memberCount: 2, version, updatedAt: version }, state }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

async function flushPromises() {
  await Promise.resolve()
  await Promise.resolve()
}
