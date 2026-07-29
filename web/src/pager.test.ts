import { describe, expect, it } from 'vitest'
import { pageFromScroll, pageOffset } from './pager'

describe('paged horizontal rails', () => {
  it('selects the nearest page while a native swipe settles', () => {
    expect(pageFromScroll(0, 360, 3)).toBe(0)
    expect(pageFromScroll(205, 360, 3)).toBe(1)
    expect(pageFromScroll(710, 360, 3)).toBe(2)
  })

  it('clamps requested pages to the available range', () => {
    expect(pageOffset(-1, 360, 3)).toBe(0)
    expect(pageOffset(1, 360, 3)).toBe(360)
    expect(pageOffset(7, 360, 3)).toBe(720)
  })
})
