import { describe, expect, it } from 'vitest'
import { swipeAction } from './swipeGesture'

describe('swipeAction', () => {
  it('maps a deliberate horizontal swipe to the adjacent track', () => {
    expect(swipeAction(240, 120)).toBe('next')
    expect(swipeAction(120, 240)).toBe('previous')
  })

  it('keeps taps and small drags as clicks', () => {
    expect(swipeAction(100, 140)).toBeNull()
  })
})
