import type { CSSProperties, KeyboardEvent } from 'react'
import { camelotAcademic, camelotCode, camelotWithinTolerance, normalizeCamelotNumber, type CamelotMode, type CamelotSelection } from '../advancedSearch'

type Props = {
  enabled: boolean
  selection: CamelotSelection
  tolerance: number
  onEnabledChange: (enabled: boolean) => void
  onSelectionChange: (selection: CamelotSelection) => void
  onToleranceChange: (value: number) => void
}

const entries = (['B', 'A'] as const).flatMap((mode) => Array.from({ length: 12 }, (_, index) => ({ number: index + 1, mode })))

export function CamelotWheel({ enabled, selection, tolerance, onEnabledChange, onSelectionChange, onToleranceChange }: Props) {
  const choose = (next: CamelotSelection) => {
    onSelectionChange(next)
    onEnabledChange(true)
  }
  const handleKey = (event: KeyboardEvent<HTMLButtonElement>, current: CamelotSelection) => {
    let next = current
    if (event.key === 'ArrowLeft') next = { ...current, number: normalizeCamelotNumber(current.number - 1) }
    else if (event.key === 'ArrowRight') next = { ...current, number: normalizeCamelotNumber(current.number + 1) }
    else if (event.key.toLowerCase() === 'a') next = { ...current, mode: 'A' }
    else if (event.key.toLowerCase() === 'b') next = { ...current, mode: 'B' }
    else if (event.key === '+' || event.key === '=') {
      onToleranceChange(Math.min(5, tolerance + 1))
      event.preventDefault()
      return
    } else if (event.key === '-') {
      onToleranceChange(Math.max(0, tolerance - 1))
      event.preventDefault()
      return
    } else return
    event.preventDefault()
    choose(next)
  }

  return <section className="camelot-filter" aria-labelledby="camelot-filter-title">
    <header className="camelot-filter-header">
      <div><strong id="camelot-filter-title">KEY</strong><span>{enabled ? `${camelotCode(selection)} / ${camelotAcademic(selection)}` : '任意'}</span></div>
      <div className="camelot-tolerance" aria-label="Camelot 邻位容差">
        <button type="button" onClick={() => onToleranceChange(Math.max(0, tolerance - 1))} disabled={!enabled || tolerance === 0} aria-label="减少 Camelot 邻位容差">−</button>
        <output aria-live="polite">邻位 ±{tolerance}</output>
        <button type="button" onClick={() => onToleranceChange(Math.min(5, tolerance + 1))} disabled={!enabled || tolerance === 5} aria-label="增加 Camelot 邻位容差">＋</button>
      </div>
    </header>
    <div className="camelot-wheel" role="group" aria-label="Camelot 轮盘，外圈大调 B，内圈小调 A">
      {entries.map((entry) => {
        const selected = enabled && camelotCode(entry) === camelotCode(selection)
        const compatible = enabled && !selected && camelotWithinTolerance(entry, selection, tolerance)
        const angle = entry.number % 12 * Math.PI / 6
        const radius = entry.mode === 'B' ? 42.5 : 27
        const style = {
          left: `${50 + Math.sin(angle) * radius}%`,
          top: `${50 - Math.cos(angle) * radius}%`,
        } as CSSProperties
        return <button
          key={camelotCode(entry)}
          type="button"
          className={`camelot-sector ${entry.mode === 'A' ? 'inner' : 'outer'} ${selected ? 'selected' : ''} ${compatible ? 'compatible' : ''}`}
          style={style}
          aria-pressed={selected}
          aria-label={`${camelotCode(entry)}，${entry.mode === 'A' ? '小调' : '大调'} ${camelotAcademic(entry)}${compatible ? '，在邻位容差内' : ''}`}
          title={`${camelotCode(entry)} / ${camelotAcademic(entry)}`}
          onClick={() => choose(entry)}
          onKeyDown={(event) => handleKey(event, entry)}
        ><b>{camelotCode(entry)}</b><small>{camelotAcademic(entry)}</small></button>
      })}
      <button
        type="button"
        className={`camelot-center ${enabled ? '' : 'selected'}`}
        aria-pressed={!enabled}
        aria-label={enabled ? '切换为任意调性' : `启用 ${camelotCode(selection)}，${camelotAcademic(selection)}`}
        onClick={() => onEnabledChange(!enabled)}
      >{enabled ? <><b>{camelotCode(selection)}</b><small>{camelotAcademic(selection)}</small></> : <b>任意</b>}</button>
    </div>
    <p className="camelot-help">方向键换编号，A / B 换内外圈，＋ / − 调整邻位容差。</p>
  </section>
}

export const defaultCamelotSelection: CamelotSelection = { number: 8, mode: 'B' }
export type { CamelotMode }
