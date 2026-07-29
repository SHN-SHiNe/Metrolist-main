import { useRef, type KeyboardEvent, type PointerEvent } from 'react'
import { analysisDimensionKeys, type AnalysisDimension } from '../types'
import type { DimensionTargets } from '../advancedSearch'
import { radarDimensions } from './RadarChart'

type Props = {
  dimensions: DimensionTargets
  tolerance: number
  onChange: (key: AnalysisDimension, patch: { enabled?: boolean; value?: number }) => void
}

const center = 100
const radius = 72
const labelByDimension = Object.fromEntries(radarDimensions) as Record<AnalysisDimension, string>

function axisPoint(index: number, value: number, distance = radius) {
  const angle = -Math.PI / 2 + index * Math.PI * 2 / analysisDimensionKeys.length
  return [center + Math.cos(angle) * value * distance, center + Math.sin(angle) * value * distance] as const
}

const polygon = (dimensions: DimensionTargets) => analysisDimensionKeys.map((key, index) => axisPoint(index, dimensions[key].value).join(',')).join(' ')

export function InteractiveRadar({ dimensions, tolerance, onChange }: Props) {
  const active = useRef<{ pointerId: number; index: number; node: SVGCircleElement } | null>(null)
  const updateFromPointer = (event: PointerEvent<SVGSVGElement>) => {
    const drag = active.current
    if (!drag || drag.pointerId !== event.pointerId) return
    const bounds = event.currentTarget.getBoundingClientRect()
    const x = (event.clientX - bounds.left) * 200 / bounds.width - center
    const y = (event.clientY - bounds.top) * 200 / bounds.height - center
    const angle = -Math.PI / 2 + drag.index * Math.PI * 2 / analysisDimensionKeys.length
    const value = Math.max(0, Math.min(1, (x * Math.cos(angle) + y * Math.sin(angle)) / radius))
    onChange(analysisDimensionKeys[drag.index], { enabled: true, value: Math.round(value * 100) / 100 })
  }
  const stopDrag = (event: PointerEvent<SVGSVGElement>) => {
    const drag = active.current
    if (!drag || drag.pointerId !== event.pointerId) return
    if (drag.node.hasPointerCapture(event.pointerId)) drag.node.releasePointerCapture(event.pointerId)
    active.current = null
  }
  const handleKey = (event: KeyboardEvent<SVGCircleElement>, key: AnalysisDimension) => {
    const current = dimensions[key]
    let value = current.value
    if (event.key === 'ArrowUp' || event.key === 'ArrowRight') value += event.shiftKey ? .1 : .01
    else if (event.key === 'ArrowDown' || event.key === 'ArrowLeft') value -= event.shiftKey ? .1 : .01
    else if (event.key === 'Home') value = 0
    else if (event.key === 'End') value = 1
    else if (event.key === ' ' || event.key === 'Enter') {
      event.preventDefault()
      onChange(key, { enabled: !current.enabled })
      return
    } else return
    event.preventDefault()
    onChange(key, { enabled: true, value: Math.max(0, Math.min(1, Math.round(value * 100) / 100)) })
  }

  return <figure className="interactive-radar">
    <svg viewBox="0 0 200 200" aria-label="可交互的七维听感目标雷达" onPointerMove={updateFromPointer} onPointerUp={stopDrag} onPointerCancel={stopDrag}>
      {[.25, .5, .75, 1].map((ring) => <polygon key={ring} className="radar-grid" points={analysisDimensionKeys.map((_, index) => axisPoint(index, ring).join(',')).join(' ')} />)}
      {analysisDimensionKeys.map((_, index) => { const [x, y] = axisPoint(index, 1); return <line key={index} className="radar-axis" x1={center} y1={center} x2={x} y2={y} /> })}
      <polygon className="radar-draft" points={polygon(dimensions)} />
      {analysisDimensionKeys.map((key, index) => {
        const target = dimensions[key]
        const [x, y] = axisPoint(index, target.value)
        const [labelX, labelY] = axisPoint(index, 1, 94)
        const label = labelByDimension[key]
        return <g key={key}>
          {target.enabled && <circle className="radar-tolerance" cx={x} cy={y} r={5 + tolerance * 18} />}
          <circle
            className={`radar-handle ${target.enabled ? 'enabled' : ''}`}
            cx={x}
            cy={y}
            r="6"
            tabIndex={0}
            role="slider"
            aria-label={`${label}目标${target.enabled ? '' : '，当前任意'}，整体容差正负${Math.round(tolerance * 100)}`}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(target.value * 100)}
            aria-valuetext={`${Math.round(target.value * 100)}，${target.enabled ? '已启用' : '任意'}`}
            onPointerDown={(event) => {
              event.preventDefault()
              event.currentTarget.setPointerCapture(event.pointerId)
              active.current = { pointerId: event.pointerId, index, node: event.currentTarget }
              onChange(key, { enabled: true })
            }}
            onKeyDown={(event) => handleKey(event, key)}
          />
          <text x={labelX} y={labelY} textAnchor={labelX < 92 ? 'end' : labelX > 108 ? 'start' : 'middle'} dominantBaseline="middle"><tspan>{label}</tspan><tspan x={labelX} dy="10">{target.enabled ? target.value.toFixed(2) : '任意'}</tspan></text>
        </g>
      })}
    </svg>
    <figcaption>拖动控制点会启用该维度；聚焦控制点后可用方向键微调、空格切换“任意”。</figcaption>
  </figure>
}
