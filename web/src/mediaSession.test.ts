import { describe, expect, it, vi } from 'vitest'
import { installMediaSessionHandlers } from './mediaSession'

describe('installMediaSessionHandlers', () => {
  it('registers play and pause before optional transport actions', () => {
    const handlers = new Map<string, ((details: MediaSessionActionDetails) => void) | null>()
    const session = { setActionHandler: vi.fn((action: MediaSessionAction, handler: ((details: MediaSessionActionDetails) => void) | null) => handlers.set(action, handler)) } as unknown as MediaSession
    const callbacks = { play: vi.fn(), pause: vi.fn(), next: vi.fn(), previous: vi.fn(), seek: vi.fn() }
    const cleanup = installMediaSessionHandlers(session, callbacks)

    handlers.get('play')?.({} as MediaSessionActionDetails)
    handlers.get('pause')?.({} as MediaSessionActionDetails)
    handlers.get('seekforward')?.({ seekOffset: 12 } as MediaSessionActionDetails)
    expect(callbacks.play).toHaveBeenCalledOnce()
    expect(callbacks.pause).toHaveBeenCalledOnce()
    expect(callbacks.seek).toHaveBeenCalledWith(12)

    cleanup()
    expect(session.setActionHandler).toHaveBeenCalledWith('pause', null)
  })

  it('continues installing the core handlers when Safari rejects an optional action', () => {
    const installed: string[] = []
    const session = {
      setActionHandler: vi.fn((action: MediaSessionAction, handler: ((details: MediaSessionActionDetails) => void) | null) => {
        if (handler && action === 'seekto') throw new Error('unsupported')
        installed.push(`${action}:${handler ? 'handler' : 'clear'}`)
      }),
    } as unknown as MediaSession
    installMediaSessionHandlers(session, { play: vi.fn(), pause: vi.fn(), next: vi.fn(), previous: vi.fn(), seek: vi.fn() })
    expect(installed).toContain('play:handler')
    expect(installed).toContain('pause:handler')
    expect(installed).not.toContain('seekto:handler')
  })
})
