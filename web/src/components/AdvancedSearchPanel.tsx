import { useEffect, useMemo, useState } from 'react'
import { api } from '../api'
import type { AdvancedSearchRequest, AnalysisDimension, AnalysisSummary, Track } from '../types'
import { analysisDimensionKeys } from '../types'
import { RadarChart, radarDimensions } from './RadarChart'
import { Icon } from './Icon'
import { EmptyState, TrackList } from './TrackList'

type DimensionTarget = { enabled: boolean; value: number }

const labels = Object.fromEntries(radarDimensions) as Record<AnalysisDimension, string>
const initialDimensions = Object.fromEntries(analysisDimensionKeys.map((key) => [key, { enabled: false, value: .5 }])) as Record<AnalysisDimension, DimensionTarget>

export function AdvancedSearchPanel({ favorites, onPlay, onFavorite, onNotice }: { favorites: Track[]; onPlay: (track: Track, queue: Track[]) => void; onFavorite: (track: Track) => void; onNotice: (value: string) => void }) {
  const [text, setText] = useState('')
  const [bpmEnabled, setBpmEnabled] = useState(false)
  const [bpm, setBpm] = useState(120)
  const [bpmTolerance, setBpmTolerance] = useState(5)
  const [keyEnabled, setKeyEnabled] = useState(false)
  const [keyName, setKeyName] = useState('8A')
  const [keyTolerance, setKeyTolerance] = useState(0)
  const [emotionTolerance, setEmotionTolerance] = useState(.12)
  const [dimensions, setDimensions] = useState(initialDimensions)
  const [summary, setSummary] = useState<AnalysisSummary | null>(null)
  const [results, setResults] = useState<{ track: Track; similarityPercent: number }[]>([])
  const [searching, setSearching] = useState(false)
  const preview = useMemo(() => ({
    status: 'completed' as const,
    progress: 1,
    ...Object.fromEntries(analysisDimensionKeys.map((key) => [key, dimensions[key].enabled ? dimensions[key].value : 0])),
  }), [dimensions])

  useEffect(() => { void api.analysis().then(setSummary).catch(() => undefined) }, [])
  useEffect(() => {
    if (!summary?.available || summary.pending + summary.queued + summary.running === 0) return
    const timer = window.setInterval(() => { void api.analysis().then(setSummary).catch(() => undefined) }, 2500)
    return () => window.clearInterval(timer)
  }, [summary?.pending, summary?.queued, summary?.running])

  const setDimension = (key: AnalysisDimension, patch: Partial<DimensionTarget>) => setDimensions((current) => ({ ...current, [key]: { ...current[key], ...patch } }))
  const runSearch = async () => {
    const enabledDimensions = analysisDimensionKeys.filter((key) => dimensions[key].enabled)
    const criteria: AdvancedSearchRequest = {
      text: text.trim(), bpm: bpmEnabled ? bpm : null, bpmTolerance,
      keyName: keyEnabled ? keyName.trim() : null, keyTolerance,
      emotionTolerance: enabledDimensions.length ? emotionTolerance : .08,
      ...Object.fromEntries(analysisDimensionKeys.map((key) => [key, dimensions[key].enabled ? dimensions[key].value : null])),
      limit: 100,
    }
    setSearching(true)
    try { setResults((await api.advancedSearch(criteria)).items) } catch (error) {
      onNotice(`${readError(error)}。高级分析接口尚未就绪时，可以先提交曲目分析。`)
    } finally { setSearching(false) }
  }
  const analyzeMissing = async () => {
    try {
      const result = await api.analyze([], true)
      setSummary(await api.analysis())
      onNotice(result.draining ? 'NAS 正在持续分析全部缺失曲目' : `${result.queued} 首曲目已加入分析队列`)
    } catch (error) { onNotice(readError(error)) }
  }
  const tracks = results.map((item) => item.track)
  const badges = new Map(results.map((item) => [item.track.id, `${item.similarityPercent}%`]))
  return <div className="advanced-search">
    <section className="analysis-overview">
      <div><span className="section-kicker"><Icon name="sparkles" /> 多维音乐画像</span><h2>按听感找音乐</h2><p>{summary?.available === false ? `当前设备暂不可分析：${summary.unavailableReason || '分析引擎不可用'}` : '结合节拍、调性与七维特征，在不频繁切歌的前提下延续当前氛围。'}</p></div>
      <div className="analysis-progress"><strong>{summary?.completed ?? 0}</strong><span>首已分析 / {summary?.total ?? '—'}</span><button className="secondary-button" onClick={() => void analyzeMissing()} disabled={summary?.available === false}>分析缺失曲目</button></div>
    </section>
    <div className="advanced-search-workbench">
      <form className="advanced-criteria" onSubmit={(event) => { event.preventDefault(); void runSearch() }}>
        <label className="advanced-text"><span>歌名 / 歌手（可选）</span><input value={text} onChange={(event) => setText(event.target.value)} placeholder="在特征范围内继续筛选" /></label>
        <fieldset><legend>速度与调性</legend>
          <div className="criterion-row"><label className="criterion-toggle"><input type="checkbox" checked={bpmEnabled} onChange={(event) => setBpmEnabled(event.target.checked)} />BPM</label><input aria-label="目标 BPM" type="number" min="40" max="240" value={bpm} disabled={!bpmEnabled} onChange={(event) => setBpm(Number(event.target.value))} /><label>± <input aria-label="BPM 容差" type="number" min="1" max="40" value={bpmTolerance} disabled={!bpmEnabled} onChange={(event) => setBpmTolerance(Number(event.target.value))} /></label></div>
          <div className="criterion-row"><label className="criterion-toggle"><input type="checkbox" checked={keyEnabled} onChange={(event) => setKeyEnabled(event.target.checked)} />Key / Camelot</label><input aria-label="Key 或 Camelot" value={keyName} disabled={!keyEnabled} onChange={(event) => setKeyName(event.target.value)} placeholder="Am 或 8A" /><label>邻位 <input aria-label="Camelot 邻位容差" type="number" min="0" max="3" value={keyTolerance} disabled={!keyEnabled} onChange={(event) => setKeyTolerance(Number(event.target.value))} /></label></div>
        </fieldset>
        <fieldset><legend>七维听感目标</legend><label className="emotion-tolerance"><span>整体容差 ±{Math.round(emotionTolerance * 100)}</span><input aria-label="七维听感整体容差" type="range" min=".01" max=".5" step=".01" value={emotionTolerance} onChange={(event) => setEmotionTolerance(Number(event.target.value))} /></label>{analysisDimensionKeys.map((key) => <div className="dimension-control" key={key}><label className="criterion-toggle"><input type="checkbox" checked={dimensions[key].enabled} onChange={(event) => setDimension(key, { enabled: event.target.checked })} />{labels[key]}</label><input aria-label={`${labels[key]}目标`} type="range" min="0" max="1" step=".01" value={dimensions[key].value} disabled={!dimensions[key].enabled} onChange={(event) => setDimension(key, { value: Number(event.target.value) })} /><output>{Math.round(dimensions[key].value * 100)}</output></div>)}</fieldset>
        <button className="primary-button advanced-submit" disabled={searching}>{searching ? '正在计算…' : '查找相似听感'}</button>
      </form>
      <div className="advanced-preview"><RadarChart analysis={preview} /><p>勾选特征后，雷达图会实时呈现目标听感。</p></div>
    </div>
    {tracks.length ? <section className="advanced-results"><div className="section-heading"><div><h2>匹配结果</h2><p>{tracks.length} 首已分析曲目，按整体接近度排序</p></div></div><TrackList tracks={tracks} favorites={favorites} onPlay={onPlay} onFavorite={onFavorite} trailingAction={(track) => <span className="similarity-badge">{badges.get(track.id)}</span>} /></section> : <EmptyState compact title="调整参数，寻找连续的听感" body="没有分析结果时，先让 NAS 在后台完成曲目分析。" />}
  </div>
}

function readError(error: unknown) { return error instanceof Error ? error.message : '操作失败，请稍后重试' }
