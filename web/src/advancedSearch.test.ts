import { describe, expect, it } from 'vitest'
import { camelotAcademic, camelotWithinTolerance, clampBpm, createDimensionTargets, formatSignedDelta, hasAdvancedCriteria, parseCamelotCode, signedCamelotDelta } from './advancedSearch'

describe('advanced search controls', () => {
  it('clamps the BPM target to the product range', () => {
    expect(clampBpm(39)).toBe(40)
    expect(clampBpm(220)).toBe(220)
    expect(clampBpm(221)).toBe(220)
  })

  it('models same-ring neighbors and same-number relative keys', () => {
    expect(camelotWithinTolerance({ number: 7, mode: 'B' }, { number: 8, mode: 'B' }, 1)).toBe(true)
    expect(camelotWithinTolerance({ number: 8, mode: 'A' }, { number: 8, mode: 'B' }, 0)).toBe(true)
    expect(camelotWithinTolerance({ number: 7, mode: 'A' }, { number: 8, mode: 'B' }, 1)).toBe(false)
    expect(signedCamelotDelta(12, 1)).toBe(-1)
    expect(camelotAcademic({ number: 8, mode: 'A' })).toBe('Am')
    expect(camelotAcademic({ number: 4, mode: 'B' })).toBe('Ab')
    expect(parseCamelotCode('12a')).toEqual({ number: 12, mode: 'A' })
  })

  it('keeps draft criteria explicit and formats explainable deltas', () => {
    const dimensions = createDimensionTargets()
    expect(hasAdvancedCriteria('', false, false, dimensions)).toBe(false)
    dimensions.energy = { enabled: true, value: .8 }
    expect(hasAdvancedCriteria('', false, false, dimensions)).toBe(true)
    expect(formatSignedDelta(-2.04, 1)).toBe('-2')
    expect(formatSignedDelta(1.96, 1)).toBe('+2')
    expect(formatSignedDelta(0)).toBe('±0')
  })
})
