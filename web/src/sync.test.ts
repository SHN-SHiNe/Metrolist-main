import { describe, expect, it } from 'vitest'
import { driftCorrection, estimateServerOffset, scheduledDelay } from './sync'

describe('room clock synchronization', () => {
  it('uses the lowest-latency samples to estimate server time', () => {
    const offset = estimateServerOffset([
      { sentAt: 0, receivedAt: 1000, serverTime: 700 },
      { sentAt: 1000, receivedAt: 1020, serverTime: 1210 },
      { sentAt: 2000, receivedAt: 2020, serverTime: 2210 },
    ])
    expect(offset).toBe(200)
  })

  it('schedules against server and per-device offsets', () => {
    expect(scheduledDelay(2000, 200, 80, 1000)).toBe(880)
  })

  it('seeks large drift and gently corrects small drift', () => {
    expect(driftCorrection(300)).toEqual({ rate: 1, seek: true })
    expect(driftCorrection(80)).toEqual({ rate: 1.01, seek: false })
    expect(driftCorrection(-80)).toEqual({ rate: 0.99, seek: false })
  })
})
