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
  libraryId?: string
}

export type TrackPage = { items: Track[]; total: number; offset: number; limit: number; revision: number }
export type PlaylistSummary = { id: string; name: string; version: number; trackCount: number; updatedAt: number }
export type PlaylistDetail = { id: string; name: string; version: number; tracks: Track[]; updatedAt: number }
export type HistoryEntry = { id: number; track: Track; playedAt: number }
export type SourceConfig = { id: string; name: string; apiUrl: string; apiKeyMasked: string; enabled: boolean; updatedAt: number }
export type DownloadJob = { id: string; title: string; artist: string; status: string; error?: string; createdAt: number; updatedAt: number }
export type SearchResponse = { items: Track[]; total: number; page: number; limit: number }
export type RoomSummary = { id: string; name: string; memberCount: number; version: number; updatedAt: number }
export type RoomPlaybackState = { queue: string[]; currentTrackId: string | null; positionMs: number; playing: boolean; effectiveAt: number }
export type RoomDetail = { summary: RoomSummary; state: RoomPlaybackState }
export type MusicLibrary = {
  id: string
  name: string
  path: string
  deviceType: 'local' | 'usb' | 'network' | 'cloud'
  readOnly: boolean
  enabled: boolean
  downloadTarget: boolean
  status: 'unknown' | 'online' | 'offline' | 'disabled'
  trackCount: number
  lastScanAt?: number | null
  lastError?: string | null
  createdAt: number
  updatedAt: number
}
export type MusicLibraryInput = Pick<MusicLibrary, 'name' | 'path' | 'deviceType' | 'readOnly' | 'enabled' | 'downloadTarget'>
