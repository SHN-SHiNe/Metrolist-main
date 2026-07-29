export type Track = {
  id: string
  title: string
  artist: string
  album: string
  durationMs: number
  mimeType?: string
  size?: number
  modifiedAt?: number
  favorite?: boolean
  artworkUrl?: string | null
  source?: string
}

export type TrackPage = { items: Track[]; total: number; offset: number; limit: number }
export type PlaylistSummary = { id: string; name: string; version: number; trackCount: number; updatedAt: number }
export type PlaylistDetail = { id: string; name: string; version: number; tracks: Track[]; updatedAt: number }
export type HistoryEntry = { id: number; track: Track; playedAt: number }
export type SourceConfig = { id: string; name: string; apiUrl: string; apiKeyMasked: string; enabled: boolean; updatedAt: number }
export type DownloadJob = { id: string; title: string; artist: string; status: string; error?: string; createdAt: number; updatedAt: number }
export type SearchResponse = { items: Track[]; total: number; page: number; limit: number }
export type RoomSummary = { id: string; name: string; memberCount: number; version: number; updatedAt: number }
export type RoomPlaybackState = { queue: string[]; currentTrackId: string | null; positionMs: number; playing: boolean; effectiveAt: number }
export type RoomEnvelope = {
  type: 'snapshot' | 'state' | 'members' | 'pong'
  roomId: string
  version: number
  state?: RoomPlaybackState
  serverTime: number
  memberCount: number
  clientTime?: number
}
