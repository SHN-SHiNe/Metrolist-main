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
  analysis?: TrackAnalysis
}

export type OnlineTrack = Omit<Track, 'source'> & { source: string }

export type OnlinePlaylistSummary = {
  id: string
  name: string
  author: string
  artworkUrl?: string | null
  playCount: number
  source: string
}

export type OnlinePlaylistSearchResponse = {
  items: OnlinePlaylistSummary[]
  total: number
  page: number
  limit: number
  allPages: number
  source: string
}

export type OnlinePlaylistDetailResponse = {
  id: string
  name: string
  author: string
  artworkUrl?: string | null
  description: string
  tracks: OnlineTrack[]
  total: number
  page: number
  limit: number
  allPages: number
  source: string
}

export type AnalysisStatus = 'pending' | 'queued' | 'running' | 'completed' | 'failed' | 'unavailable'

export const analysisDimensionKeys = [
  'valence',
  'energy',
  'danceability',
  'acousticness',
  'instrumentalness',
  'liveness',
  'speechiness',
] as const

export type AnalysisDimension = typeof analysisDimensionKeys[number]

export type TrackAnalysis = {
  status: AnalysisStatus
  progress: number
  message?: string | null
  bpm?: number | null
  keyName?: string | null
  camelot?: string | null
  valence?: number | null
  energy?: number | null
  danceability?: number | null
  acousticness?: number | null
  instrumentalness?: number | null
  liveness?: number | null
  speechiness?: number | null
  analyzedAt?: number | null
}

export type SimilarTrack = {
  track: Track
  similarityPercent: number
  bpmDelta?: number
  camelotDelta?: number
}

export type SimilarTracksResponse = { seed: Track; items: SimilarTrack[] }

export type AnalysisSummary = {
  available: boolean
  implementation: string
  unavailableReason?: string | null
  total: number
  pending: number
  queued: number
  running: number
  completed: number
  failed: number
}

export type AnalysisEnqueueResponse = { queued: number; draining: boolean }

export type AdvancedSearchRequest = {
  text?: string
  bpm?: number | null
  bpmTolerance?: number
  keyName?: string | null
  keyTolerance?: number
  emotionTolerance?: number
  valence?: number | null
  energy?: number | null
  danceability?: number | null
  acousticness?: number | null
  instrumentalness?: number | null
  liveness?: number | null
  speechiness?: number | null
  limit?: number
}

export type AdvancedSearchItem = {
  track: Track
  similarityPercent: number
  bpmDelta?: number | null
  camelotDelta?: number | null
  camelotModeChanged?: boolean | null
}
export type AdvancedSearchResponse = { items: AdvancedSearchItem[]; totalCandidates: number }

export type TrackPage = { items: Track[]; total: number; offset: number; limit: number; revision: number }
export type PlaylistSummary = { id: string; name: string; version: number; trackCount: number; updatedAt: number }
export type PlaylistDetail = { id: string; name: string; version: number; tracks: Track[]; updatedAt: number }
export type HistoryEntry = { id: number; track: Track; playedAt: number }
export type SourceConfig = { id: string; name: string; apiUrl: string; apiKeyMasked: string; enabled: boolean; updatedAt: number }
export type DownloadJob = { id: string; title: string; artist: string; status: string; error?: string; createdAt: number; updatedAt: number }
export type SearchResponse = { items: OnlineTrack[]; total: number; page: number; limit: number }
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
