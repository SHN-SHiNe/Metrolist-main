import { useEffect, useMemo, useState, type CSSProperties } from 'react'
import { api } from '../api'
import { camelotAcademic, camelotCode, clampBpm, createDimensionTargets, dimensionsToAnalysis, formatSignedDelta, hasAdvancedCriteria, parseCamelotCode, type DimensionTargets } from '../advancedSearch'
import type { AdvancedSearchItem, AdvancedSearchRequest, AnalysisDimension, AnalysisSummary, Track } from '../types'
import { analysisDimensionKeys } from '../types'
import { CamelotWheel, defaultCamelotSelection } from './CamelotWheel'
import { Icon } from './Icon'
import { InteractiveRadar } from './InteractiveRadar'
import { RadarChart, radarDimensions } from './RadarChart'
import { AlbumArt, EmptyState } from './TrackList'

const labels = Object.fromEntries(radarDimensions) as Record<AnalysisDimension, string>

export function AdvancedSearchPanel({ favorites, onPlay, onFavorite, onNotice }: { favorites: Track[]; onPlay: (track: Track, queue: Track[]) => void; onFavorite: (track: Track) => void; onNotice: (value: string) => void }) {
  const [text, setText] = useState('')
  const [bpmEnabled, setBpmEnabled] = useState(false)
  const [bpm, setBpm] = useState(120)
  const [bpmTolerance, setBpmTolerance] = useState(5)
  const [keyEnabled, setKeyEnabled] = useState(false)
  const [camelot, setCamelot] = useState(defaultCamelotSelection)
  const [keyTolerance, setKeyTolerance] = useState(0)
  const [emotionTolerance, setEmotionTolerance] = useState(.12)
  const [emotionInput, setEmotionInput] = useState<'sliders' | 'radar'>('sliders')
  const [dimensions, setDimensions] = useState<DimensionTargets>(() => createDimensionTargets())
  const [appliedDimensions, setAppliedDimensions] = useState<DimensionTargets | null>(null)
  const [appliedSignature, setAppliedSignature] = useState('')
  const [summary, setSummary] = useState<AnalysisSummary | null>(null)
  const [results, setResults] = useState<AdvancedSearchItem[]>([])
  const [totalCandidates, setTotalCandidates] = useState(0)
  const [searched, setSearched] = useState(false)
  const [searching, setSearching] = useState(false)
  const [criteriaHint, setCriteriaHint] = useState('')
  const preview = useMemo(() => dimensionsToAnalysis(dimensions), [dimensions])
  const favoriteIds = useMemo(() => new Set(favorites.map((track) => track.id)), [favorites])

  useEffect(() => { void api.analysis().then(setSummary).catch(() => undefined) }, [])
  useEffect(() => {
    if (!summary?.available || summary.pending + summary.queued + summary.running === 0) return
    const timer = window.setInterval(() => { void api.analysis().then(setSummary).catch(() => undefined) }, 2500)
    return () => window.clearInterval(timer)
  }, [summary?.available, summary?.pending, summary?.queued, summary?.running])

  const setDimension = (key: AnalysisDimension, patch: { enabled?: boolean; value?: number }) => setDimensions((current) => ({
    ...current,
    [key]: {
      ...current[key],
      ...patch,
      ...(patch.value === undefined ? {} : { value: Math.max(0, Math.min(1, patch.value)) }),
    },
  }))
  const draftSignature = JSON.stringify({ text: text.trim(), bpmEnabled, bpm, bpmTolerance, keyEnabled, camelot, keyTolerance, emotionTolerance, dimensions })
  const pending = Boolean(appliedSignature && appliedSignature !== draftSignature)
  const enabledDimensions = analysisDimensionKeys.filter((key) => dimensions[key].enabled)
  const criteriaCount = Number(Boolean(text.trim())) + Number(bpmEnabled) + Number(keyEnabled) + enabledDimensions.length

  const runSearch = async () => {
    if (!hasAdvancedCriteria(text, bpmEnabled, keyEnabled, dimensions)) {
      setCriteriaHint('请先启用 BPM、调性或至少一个听感维度，也可以输入歌名或歌手。')
      setSearched(false)
      return
    }
    const criteria: AdvancedSearchRequest = {
      text: text.trim(),
      bpm: bpmEnabled ? clampBpm(bpm) : null,
      bpmTolerance,
      keyName: keyEnabled ? camelotCode(camelot) : null,
      keyTolerance,
      emotionTolerance: enabledDimensions.length ? emotionTolerance : .08,
      ...Object.fromEntries(analysisDimensionKeys.map((key) => [key, dimensions[key].enabled ? dimensions[key].value : null])),
      limit: 100,
    }
    setSearching(true)
    setCriteriaHint('')
    try {
      const response = await api.advancedSearch(criteria)
      setResults(response.items)
      setTotalCandidates(response.totalCandidates)
      setAppliedDimensions(cloneDimensions(dimensions))
      setAppliedSignature(draftSignature)
      setSearched(true)
    } catch (error) {
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
  const bpmRangeStart = Math.max(40, bpm - bpmTolerance)
  const bpmRangeEnd = Math.min(220, bpm + bpmTolerance)
  const bpmTrackStyle = {
    '--bpm-range-start': `${(bpmRangeStart - 40) / 1.8}%`,
    '--bpm-range-end': `${(bpmRangeEnd - 40) / 1.8}%`,
  } as CSSProperties

  return <div className="advanced-search">
    <section className="analysis-overview">
      <div><span className="section-kicker"><Icon name="sparkles" /> 多维音乐画像</span><h2>按听感找音乐</h2><p>{summary?.available === false ? `当前设备暂不可分析：${summary.unavailableReason || '分析引擎不可用'}` : '结合节拍、调性与七维特征，在不频繁切歌的前提下延续当前氛围。'}</p></div>
      <div className="analysis-progress"><strong>{summary?.completed ?? 0}</strong><span>首已分析 / {summary?.total ?? '—'}</span><button className="secondary-button" onClick={() => void analyzeMissing()} disabled={summary?.available === false}>分析缺失曲目</button></div>
    </section>
    <section className="advanced-filter-card" aria-labelledby="advanced-filter-title">
      <header className="advanced-filter-heading"><div><span className="section-kicker"><Icon name="radar" /> 高级筛选</span><h2 id="advanced-filter-title">{criteriaCount} 个条件 · {searched ? `${totalCandidates} 首匹配` : '待执行'}{pending ? ' · 未应用' : appliedSignature ? ' · 已应用' : ''}</h2></div></header>
      <form className="advanced-criteria" onSubmit={(event) => { event.preventDefault(); void runSearch() }}>
        <label className="advanced-text"><span>歌名 / 歌手（可选）</span><input value={text} onChange={(event) => setText(event.target.value)} placeholder="在特征范围内继续筛选" /></label>
        <fieldset className="fuzzy-filter"><legend>速度</legend>
          <div className="fuzzy-filter-heading"><strong>BPM</strong><button type="button" className={`any-toggle ${!bpmEnabled ? 'active' : ''}`} aria-pressed={!bpmEnabled} onClick={() => setBpmEnabled(false)}>任意</button><label>目标 <input aria-label="目标 BPM，范围 40 到 220" type="number" min="40" max="220" value={bpm} onChange={(event) => { setBpm(clampBpm(Number(event.target.value))); setBpmEnabled(true) }} /></label><div className="tolerance-stepper"><button type="button" aria-label="减少 BPM 容差" disabled={!bpmEnabled || bpmTolerance === 0} onClick={() => setBpmTolerance(Math.max(0, bpmTolerance - 1))}>−</button><output>±{bpmTolerance}</output><button type="button" aria-label="增加 BPM 容差" disabled={!bpmEnabled || bpmTolerance === 60} onClick={() => setBpmTolerance(Math.min(60, bpmTolerance + 1))}>＋</button></div></div>
          <input className="fuzzy-range bpm-range" style={bpmTrackStyle} aria-label={`BPM 目标 ${bpm}，容差正负 ${bpmTolerance}`} aria-valuetext={bpmEnabled ? `${bpm} BPM，有效范围 ${bpmRangeStart} 到 ${bpmRangeEnd}` : '任意 BPM'} type="range" min="40" max="220" step="1" value={bpm} onChange={(event) => { setBpm(Number(event.target.value)); setBpmEnabled(true) }} />
          <small>{bpmEnabled ? `有效范围 ${bpmRangeStart}–${bpmRangeEnd} BPM` : '当前不限制速度；拖动滑块会自动启用。'}</small>
        </fieldset>
        <CamelotWheel enabled={keyEnabled} selection={camelot} tolerance={keyTolerance} onEnabledChange={setKeyEnabled} onSelectionChange={setCamelot} onToleranceChange={setKeyTolerance} />
        <fieldset className="emotion-filter"><legend>七维听感目标</legend>
          <div className="emotion-filter-tools"><div className="emotion-input-tabs" role="tablist" aria-label="七维输入方式"><button type="button" role="tab" aria-selected={emotionInput === 'sliders'} className={emotionInput === 'sliders' ? 'active' : ''} onClick={() => setEmotionInput('sliders')}>滑条</button><button type="button" role="tab" aria-selected={emotionInput === 'radar'} className={emotionInput === 'radar' ? 'active' : ''} onClick={() => setEmotionInput('radar')}>雷达</button></div><label className="emotion-tolerance"><span>整体容差 ±{Math.round(emotionTolerance * 100)}</span><input aria-label="七维听感整体容差" type="range" min=".01" max=".5" step=".01" value={emotionTolerance} onChange={(event) => setEmotionTolerance(Number(event.target.value))} /></label></div>
          {emotionInput === 'sliders' ? <div className="dimension-sliders">{analysisDimensionKeys.map((key) => <div className="dimension-control" key={key}><label className="criterion-toggle"><input type="checkbox" checked={dimensions[key].enabled} onChange={(event) => setDimension(key, { enabled: event.target.checked })} />{labels[key]}</label><input aria-label={`${labels[key]}目标`} aria-valuetext={`${Math.round(dimensions[key].value * 100)}，${dimensions[key].enabled ? '已启用' : '任意'}`} type="range" min="0" max="1" step=".01" value={dimensions[key].value} onChange={(event) => setDimension(key, { enabled: true, value: Number(event.target.value) })} /><input className="dimension-number" aria-label={`${labels[key]}目标数值`} type="number" min="0" max="100" value={Math.round(dimensions[key].value * 100)} onChange={(event) => setDimension(key, { enabled: true, value: Number(event.target.value) / 100 })} /></div>)}</div> : <><InteractiveRadar dimensions={dimensions} tolerance={emotionTolerance} onChange={setDimension} /><div className="dimension-toggle-strip" aria-label="七维启用状态">{analysisDimensionKeys.map((key) => <button type="button" key={key} className={dimensions[key].enabled ? 'active' : ''} aria-pressed={dimensions[key].enabled} onClick={() => setDimension(key, { enabled: !dimensions[key].enabled })}>{labels[key]} {dimensions[key].enabled ? Math.round(dimensions[key].value * 100) : '任意'}</button>)}</div></>}
        </fieldset>
        {criteriaHint && <p className="criteria-hint" role="status">{criteriaHint}</p>}
        <button className="primary-button advanced-submit" disabled={searching}>{searching ? '正在计算…' : !appliedSignature ? '执行筛选' : pending ? '应用筛选' : '重新筛选'}</button>
      </form>
    </section>
    {results.length ? <AdvancedResults items={results} tracks={tracks} favorites={favoriteIds} target={appliedDimensions ? dimensionsToAnalysis(appliedDimensions) : preview} onPlay={onPlay} onFavorite={onFavorite} total={totalCandidates} /> : <AdvancedEmpty searched={searched} summary={summary} />}
  </div>
}

function AdvancedResults({ items, tracks, favorites, target, onPlay, onFavorite, total }: { items: AdvancedSearchItem[]; tracks: Track[]; favorites: Set<string>; target: ReturnType<typeof dimensionsToAnalysis>; onPlay: (track: Track, queue: Track[]) => void; onFavorite: (track: Track) => void; total: number }) {
  return <section className="advanced-results"><div className="section-heading"><div><h2>匹配结果</h2><p>显示 {items.length} / {total} 首已分析曲目，按整体接近度排序</p></div></div><div className="advanced-result-list" role="list">{items.map((item) => {
    const analysis = item.track.analysis
    const bpm = analysis?.bpm
    const key = analysis?.keyName
    const camelot = analysis?.camelot
    const bpmChip = typeof bpm === 'number' ? `${Math.round(bpm)} BPM${typeof item.bpmDelta === 'number' ? ` · Δ${formatSignedDelta(item.bpmDelta, 1)}` : ''}` : 'BPM MISS'
    const parsedCamelot = parseCamelotCode(camelot)
    const academicKey = parsedCamelot ? camelotAcademic(parsedCamelot) : key
    const keyChip = academicKey || camelot ? `${academicKey || '调性 MISS'}${camelot ? ` · ${camelot}` : ''}${typeof item.camelotDelta === 'number' ? ` · 邻位 ${formatSignedDelta(item.camelotDelta)}${item.camelotModeChanged ? ' / 异环' : ''}` : ''}` : '调性 MISS'
    return <article className="advanced-result-row" role="listitem" key={item.track.id}>
      <button className="advanced-result-cover" onClick={() => onPlay(item.track, tracks)} aria-label={`播放 ${item.track.title}`}><AlbumArt title={item.track.title} artworkUrl={item.track.artworkUrl} /></button>
      <button className="advanced-result-meta" onClick={() => onPlay(item.track, tracks)} aria-label={`播放 ${item.track.title}`}><strong>{item.track.title}</strong><small>{item.track.artist}{item.track.album ? ` · ${item.track.album}` : ''}</small><span className="advanced-result-chips"><em>{item.similarityPercent}% 相似</em><span>{bpmChip}</span><span>{keyChip}</span></span></button>
      <button className={`icon-button ${favorites.has(item.track.id) ? 'favorite' : ''}`} onClick={() => onFavorite(item.track)} aria-label={favorites.has(item.track.id) ? `取消收藏 ${item.track.title}` : `收藏 ${item.track.title}`}><Icon name="heart" /></button>
      <RadarChart analysis={analysis} comparison={target} compact className="advanced-comparison-radar" />
    </article>
  })}</div></section>
}

function AdvancedEmpty({ searched, summary }: { searched: boolean; summary: AnalysisSummary | null }) {
  if (summary && !summary.completed) return <EmptyState compact title="分析数据不足" body="先让 NAS 在后台完成曲目分析，再用 BPM、Camelot 与七维画像筛选。" />
  if (searched) return <EmptyState compact title="没有匹配结果" body="可以放宽 BPM、邻位或七维容差，也可以将部分维度切回“任意”。" />
  return <EmptyState compact title="设置高级筛选后执行搜索" body="草稿不会立即改动结果；明确执行后才按相似度更新列表。" />
}

function cloneDimensions(dimensions: DimensionTargets): DimensionTargets {
  return Object.fromEntries(analysisDimensionKeys.map((key) => [key, { ...dimensions[key] }])) as DimensionTargets
}

function readError(error: unknown) { return error instanceof Error ? error.message : '操作失败，请稍后重试' }
