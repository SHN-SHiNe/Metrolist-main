import type { AnalysisDimension, TrackAnalysis } from './types'
import { analysisDimensionKeys } from './types'

export type CamelotMode = 'A' | 'B'
export type CamelotSelection = { number: number; mode: CamelotMode }
export type DimensionTarget = { enabled: boolean; value: number }
export type DimensionTargets = Record<AnalysisDimension, DimensionTarget>

const academicByCamelot: Record<string, string> = {
  '1A': 'Abm', '1B': 'B', '2A': 'Ebm', '2B': 'F#', '3A': 'Bbm', '3B': 'Db',
  '4A': 'Fm', '4B': 'Ab', '5A': 'Cm', '5B': 'Eb', '6A': 'Gm', '6B': 'Bb',
  '7A': 'Dm', '7B': 'F', '8A': 'Am', '8B': 'C', '9A': 'Em', '9B': 'G',
  '10A': 'Bm', '10B': 'D', '11A': 'F#m', '11B': 'A', '12A': 'Dbm', '12B': 'E',
}

export const createDimensionTargets = (): DimensionTargets => Object.fromEntries(
  analysisDimensionKeys.map((key) => [key, { enabled: false, value: .5 }]),
) as DimensionTargets

export function clampBpm(value: number) {
  if (!Number.isFinite(value)) return 120
  return Math.min(220, Math.max(40, Math.round(value)))
}

export function normalizeCamelotNumber(value: number) {
  return ((Math.round(value) - 1) % 12 + 12) % 12 + 1
}

export function camelotCode(selection: CamelotSelection) {
  return `${normalizeCamelotNumber(selection.number)}${selection.mode}`
}

export function camelotAcademic(selection: CamelotSelection) {
  return academicByCamelot[camelotCode(selection)]
}

export function parseCamelotCode(value?: string | null): CamelotSelection | null {
  const match = /^(\d{1,2})([AB])$/i.exec(value?.trim() ?? '')
  const number = Number(match?.[1])
  if (!match || number < 1 || number > 12) return null
  return { number, mode: match[2].toUpperCase() as CamelotMode }
}

export function signedCamelotDelta(candidate: number, target: number) {
  const clockwise = (normalizeCamelotNumber(candidate) - normalizeCamelotNumber(target) + 12) % 12
  return clockwise > 6 ? clockwise - 12 : clockwise
}

export function camelotWithinTolerance(candidate: CamelotSelection, target: CamelotSelection, tolerance: number) {
  const delta = Math.abs(signedCamelotDelta(candidate.number, target.number))
  return candidate.mode === target.mode ? delta <= Math.max(0, tolerance) : delta === 0
}

export function dimensionsToAnalysis(dimensions: DimensionTargets): TrackAnalysis {
  return {
    status: 'completed',
    progress: 1,
    ...Object.fromEntries(analysisDimensionKeys.map((key) => [key, dimensions[key].enabled ? dimensions[key].value : 0])),
  }
}

export function hasAdvancedCriteria(text: string, bpmEnabled: boolean, keyEnabled: boolean, dimensions: DimensionTargets) {
  return Boolean(text.trim() || bpmEnabled || keyEnabled || analysisDimensionKeys.some((key) => dimensions[key].enabled))
}

export function formatSignedDelta(value: number, digits = 0) {
  const rounded = Number(value.toFixed(digits))
  if (Object.is(rounded, -0) || rounded === 0) return '±0'
  return rounded > 0 ? `+${rounded}` : String(rounded)
}
