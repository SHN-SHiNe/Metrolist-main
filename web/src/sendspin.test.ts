import { describe, expect, it } from 'vitest'
import { clampSendspinDelay, sendspinBaseUrl } from './sendspin'

describe('Sendspin room adapter', () => {
  it('uses the Ktor room proxy as SDK base URL', () => {
    expect(sendspinBaseUrl('living room', 'http://nas:8767')).toBe('http://nas:8767/api/rooms/living%20room')
  })

  it('keeps static delay inside the Sendspin protocol range', () => {
    expect(clampSendspinDelay(-10)).toBe(0)
    expect(clampSendspinDelay(320)).toBe(320)
    expect(clampSendspinDelay(9000)).toBe(5000)
  })
})
