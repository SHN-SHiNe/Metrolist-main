import { describe, expect, it, vi } from 'vitest'
import { randomId } from './randomId'

describe('randomId', () => {
  it('uses randomUUID when the secure-context API is available', () => {
    const randomUUID = vi.fn(() => '11111111-1111-4111-8111-111111111111')
    expect(randomId({ randomUUID, getRandomValues: vi.fn() })).toBe('11111111-1111-4111-8111-111111111111')
    expect(randomUUID).toHaveBeenCalledOnce()
  })

  it('creates a valid v4 UUID on an HTTP origin without randomUUID', () => {
    const getRandomValues = vi.fn((bytes: Uint8Array) => {
      bytes.set([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15])
      return bytes
    })

    expect(randomId({ getRandomValues })).toBe('00010203-0405-4607-8809-0a0b0c0d0e0f')
  })
})
