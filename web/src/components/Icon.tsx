export type IconName = 'home' | 'search' | 'library' | 'storage' | 'heart' | 'playlist' | 'room' | 'settings' | 'history' | 'stats' | 'play' | 'pause' | 'previous' | 'next' | 'volume' | 'refresh' | 'download' | 'speaker' | 'trash' | 'radar' | 'sparkles' | 'queue' | 'close' | 'back' | 'share' | 'artist' | 'album'

const paths: Record<IconName, string> = {
  home: 'M3 11.5 12 4l9 7.5V20a1 1 0 0 1-1 1h-5v-6H10v6H5a1 1 0 0 1-1-1z',
  search: 'm21 21-4.4-4.4m2.4-5.1a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z',
  library: 'M4 5h4v14H4zm6-2h4v16h-4zm6 5h4v11h-4z',
  storage: 'M4 5h16v14H4zM8 9h8m-8 4h8m-8 4h5',
  heart: 'M20.8 5.7a5.5 5.5 0 0 0-7.8 0L12 6.8l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 22l8.8-8.5a5.5 5.5 0 0 0 0-7.8Z',
  playlist: 'M4 6h11M4 11h11M4 16h7m7-3v8m-4-4h8',
  room: 'M4 14a8 8 0 0 1 16 0m-12 0a4 4 0 0 1 8 0m-4 0v.01',
  settings: 'M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7Zm7.4-3.5 2-1.2-2-3.5-2.2.7a8 8 0 0 0-1.2-.7l-.5-2.3h-4l-.5 2.3a8 8 0 0 0-1.2.7l-2.2-.7-2 3.5 2 1.2v1.4l-2 1.2 2 3.5 2.2-.7 1.2.7.5 2.3h4l.5-2.3 1.2-.7 2.2.7 2-3.5-2-1.2z',
  history: 'M4 4v5h5M5.2 8.3A8 8 0 1 1 4 13m8-5v5l3 2',
  stats: 'M5 20V10m7 10V4m7 16v-7',
  play: 'm9 7 9 5-9 5z', pause: 'M8 6h3v12H8zm5 0h3v12h-3z',
  previous: 'M7 6h2v12H7zm3 6 8-6v12z', next: 'M15 6h2v12h-2zm-1 6-8 6V6z',
  volume: 'M4 10v4h4l5 4V6l-5 4zm12-1a4 4 0 0 1 0 6m2-8a7 7 0 0 1 0 10',
  refresh: 'M20 7v5h-5m4-1a7 7 0 1 0 0 4', download: 'M12 3v12m-5-5 5 5 5-5M5 20h14',
  speaker: 'M5 9h4l5-4v14l-5-4H5zm12 1a3 3 0 0 1 0 4m2-7a7 7 0 0 1 0 10',
  trash: 'M4 7h16M9 7V4h6v3m3 0-1 14H7L6 7m4 4v6m4-6v6',
  radar: 'M12 2 22 9l-4 12H6L2 9zm0 4v6m0 0 5 4m-5-4-5 4',
  sparkles: 'm12 2 1.5 4.5L18 8l-4.5 1.5L12 14l-1.5-4.5L6 8l4.5-1.5zM19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z',
  queue: 'M5 6h14M5 11h14M5 16h8m4-2v6m-3-3h6', close: 'M6 6l12 12M18 6 6 18', back: 'm15 18-6-6 6-6',
  share: 'M8 12 16 7m-8 5 8 5M18 4a3 3 0 1 1 0 6 3 3 0 0 1 0-6ZM6 9a3 3 0 1 1 0 6 3 3 0 0 1 0-6Zm12 5a3 3 0 1 1 0 6 3 3 0 0 1 0-6Z',
  artist: 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8Zm-7 9a7 7 0 0 1 14 0',
  album: 'M4 4h16v16H4zm4 4h8v8H8zm4 2a2 2 0 1 0 0 4 2 2 0 0 0 0-4Z',
}

export function Icon({ name }: { name: IconName }) {
  return <svg viewBox="0 0 24 24" aria-hidden="true"><path d={paths[name]} /></svg>
}
