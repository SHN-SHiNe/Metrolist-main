export type MediaSessionControlCallbacks = {
  play: () => void
  pause: () => void
  next: () => void
  previous: () => void
  seek: (seconds: number) => void
  stop?: () => void
}

/**
 * Installs a complete set of transport handlers and tolerates partial
 * Media Session implementations (notably older iOS Safari builds).
 *
 * WebKit removes its default remote commands as soon as a page registers a
 * Media Session handler. Registering play/pause explicitly is therefore
 * important: otherwise the lock-screen button can remain visible but never
 * reach the page. Each action is installed independently because Safari may
 * reject newer actions such as `seekto` or `stop`.
 */
export function installMediaSessionHandlers(session: MediaSession, callbacks: MediaSessionControlCallbacks) {
  const handlers: Record<MediaSessionAction, MediaSessionActionHandler> = {
    play: () => callbacks.play(),
    pause: () => callbacks.pause(),
    nexttrack: () => callbacks.next(),
    previoustrack: () => callbacks.previous(),
    seekbackward: (details) => callbacks.seek(-(details.seekOffset ?? 10)),
    seekforward: (details) => callbacks.seek(details.seekOffset ?? 10),
    seekto: (details) => { if (typeof details.seekTime === 'number') callbacks.seek(details.seekTime) },
    stop: () => callbacks.stop?.(),
    skipad: () => callbacks.next(),
  }
  const installed: MediaSessionAction[] = []
  for (const [action, handler] of Object.entries(handlers) as [MediaSessionAction, MediaSessionActionHandler][]) {
    try {
      session.setActionHandler(action, handler)
      installed.push(action)
    } catch {
      // Older Safari versions throw for actions they do not expose.
    }
  }
  return () => {
    for (const action of installed) {
      try { session.setActionHandler(action, null) } catch { /* ignore unsupported cleanup */ }
    }
  }
}
