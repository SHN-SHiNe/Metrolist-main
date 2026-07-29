import type { TrackAnalysis } from '../types'

export const radarDimensions = [
  ['valence', '愉悦'], ['energy', '能量'], ['danceability', '律动'], ['acousticness', '原声'],
  ['instrumentalness', '器乐'], ['liveness', '现场'], ['speechiness', '人声'],
] as const

const point = (index: number, radius: number, center = 100) => {
  const angle = -Math.PI / 2 + index * Math.PI * 2 / radarDimensions.length
  return `${center + Math.cos(angle) * radius},${center + Math.sin(angle) * radius}`
}

export function analysisValues(analysis?: TrackAnalysis) {
  return radarDimensions.map(([key]) => {
    const value = analysis?.[key]
    if (typeof value !== 'number' || !Number.isFinite(value)) return 0
    return Math.max(0, Math.min(1, value > 1 ? value / 100 : value))
  })
}

export function RadarChart({ analysis, comparison, compact = false, className = '' }: { analysis?: TrackAnalysis; comparison?: TrackAnalysis; compact?: boolean; className?: string }) {
  const values = analysisValues(analysis)
  const comparisonValues = analysisValues(comparison)
  const complete = analysis?.status === 'completed' && values.some(Boolean)
  const comparisonComplete = comparison?.status === 'completed' && comparisonValues.some(Boolean)
  const rings = [24, 48, 72, 92]
  const polygon = values.map((value, index) => point(index, value * 92)).join(' ')
  const comparisonPolygon = comparisonValues.map((value, index) => point(index, value * 92)).join(' ')
  const summary = radarDimensions.map(([, label], index) => `${label} ${Math.round(values[index] * 100)}`).join('，')
  const label = complete ? `${comparisonComplete ? '曲目与目标对比，曲目特征' : '音乐特征'}：${summary}` : '音乐特征尚未完成分析'
  const chart = <svg viewBox="0 0 200 200" role="img">
      <title>{complete ? summary : '等待音乐特征分析'}</title>
      {rings.map((radius) => <polygon key={radius} className="radar-grid" points={radarDimensions.map((_, index) => point(index, radius)).join(' ')} />)}
      {radarDimensions.map((_, index) => <line key={index} className="radar-axis" x1="100" y1="100" x2={point(index, 92).split(',')[0]} y2={point(index, 92).split(',')[1]} />)}
      {comparisonComplete && <polygon className="radar-comparison" points={comparisonPolygon} />}
      {complete && <polygon className="radar-value" points={polygon} />}
      {!compact && radarDimensions.map(([, label], index) => {
        const [x, y] = point(index, 108).split(',').map(Number)
        return <text key={label} x={x} y={y} textAnchor={x < 92 ? 'end' : x > 108 ? 'start' : 'middle'} dominantBaseline="middle">{label}</text>
      })}
    </svg>
  if (compact) return <span className={`radar-chart compact ${className}`} aria-label={label}>{chart}</span>
  return <figure className={`radar-chart ${className}`} aria-label={label}>{chart}{!complete && <figcaption>{analysis?.status === 'running' || analysis?.status === 'queued' ? `分析中 ${Math.round((analysis.progress || 0) * 100)}%` : '分析后显示七维音乐画像'}</figcaption>}</figure>
}
