import { useState } from 'react'

/**
 * Small hand-rolled SVG/HTML charts for the analytics pages. Single-series only, so one accent color carries the
 * data and identity always comes from text labels (never color alone). Every mark has a hover tooltip, and each
 * chart sits next to a table with the same numbers.
 */

export interface BarDatum {
  label: string
  value: number | null
  /** Tooltip / secondary text, e.g. "12 requests · $0.0042 avg". */
  detail?: string
}

interface BarListProps {
  data: BarDatum[]
  format: (value: number | null) => string
  emptyText?: string
  ariaLabel: string
}

/** Horizontal bars with direct labels and values, for magnitude by category. Missing values are listed, not drawn. */
export function BarList({ data, format, emptyText = 'No data for this period.', ariaLabel }: BarListProps) {
  const max = Math.max(0, ...data.map((d) => d.value ?? 0))
  if (data.length === 0) return <p className="aia-empty">{emptyText}</p>
  return (
    <ul className="aia-bars" aria-label={ariaLabel}>
      {data.map((d) => (
        <li key={d.label} className="aia-bar-row" title={d.detail ? `${d.label}: ${format(d.value)} · ${d.detail}` : undefined}>
          <span className="aia-bar-label">{d.label}</span>
          <span className="aia-bar-track">
            {d.value !== null && max > 0 && (
              <span className="aia-bar-fill" style={{ width: `${Math.max(1.5, (d.value / max) * 100)}%` }} />
            )}
          </span>
          <span className="aia-bar-value">{format(d.value)}</span>
        </li>
      ))}
    </ul>
  )
}

export interface BarGroup {
  label: string
  data: BarDatum[]
}

interface GroupedBarListProps {
  groups: BarGroup[]
  format: (value: number | null) => string
  /** The scale's top, e.g. 5 for 1–5 ratings; defaults to the largest value. */
  max?: number
  emptyText?: string
  ariaLabel: string
}

/**
 * Horizontal bars grouped under a heading (e.g. one group per quality dimension, one bar per model). Still a single
 * series color: each bar is identified by its text label, and all groups share one scale so they compare directly.
 */
export function GroupedBarList({ groups, format, max, emptyText = 'No data for this period.', ariaLabel }: GroupedBarListProps) {
  const top = max ?? Math.max(0, ...groups.flatMap((g) => g.data.map((d) => d.value ?? 0)))
  if (groups.every((g) => g.data.length === 0)) return <p className="aia-empty">{emptyText}</p>
  return (
    <div className="aia-bar-groups" role="group" aria-label={ariaLabel}>
      {groups.map((group) => (
        <div key={group.label} className="aia-bar-group">
          <h4 className="aia-bar-group-label">{group.label}</h4>
          <ul className="aia-bars">
            {group.data.map((d) => (
              <li key={d.label} className="aia-bar-row" title={d.detail ? `${d.label}: ${format(d.value)} · ${d.detail}` : undefined}>
                <span className="aia-bar-label">{d.label}</span>
                <span className="aia-bar-track">
                  {d.value !== null && top > 0 && (
                    <span className="aia-bar-fill" style={{ width: `${Math.max(1.5, Math.min(100, (d.value / top) * 100))}%` }} />
                  )}
                </span>
                <span className="aia-bar-value">{format(d.value)}</span>
              </li>
            ))}
          </ul>
        </div>
      ))}
    </div>
  )
}

export interface PointDatum {
  label: string
  value: number | null
  detail?: string
}

interface LineChartProps {
  data: PointDatum[]
  format: (value: number | null) => string
  ariaLabel: string
}

const WIDTH = 640
const HEIGHT = 200
const PAD = { top: 12, right: 12, bottom: 28, left: 56 }

/** One line over time with a hover crosshair. Gaps (missing values) break the line instead of dropping to zero. */
export function LineChart({ data, format, ariaLabel }: LineChartProps) {
  const [hover, setHover] = useState<number | null>(null)
  const values = data.map((d) => d.value).filter((v): v is number => v !== null)
  if (data.length === 0 || values.length === 0) return <p className="aia-empty">No data for this period.</p>

  const max = Math.max(...values) || 1
  const innerW = WIDTH - PAD.left - PAD.right
  const innerH = HEIGHT - PAD.top - PAD.bottom
  const x = (i: number) => PAD.left + (data.length === 1 ? innerW / 2 : (i / (data.length - 1)) * innerW)
  const y = (v: number) => PAD.top + innerH - (v / max) * innerH

  const segments: string[] = []
  let current = ''
  data.forEach((d, i) => {
    if (d.value === null) {
      if (current) segments.push(current)
      current = ''
      return
    }
    current += `${current ? 'L' : 'M'}${x(i).toFixed(1)},${y(d.value).toFixed(1)}`
  })
  if (current) segments.push(current)

  const labelEvery = Math.max(1, Math.ceil(data.length / 6))
  const hovered = hover === null ? null : data[hover]

  return (
    <div className="aia-line-wrap">
      <svg
        className="aia-line"
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-label={ariaLabel}
        onMouseLeave={() => setHover(null)}
        onMouseMove={(event) => {
          const rect = event.currentTarget.getBoundingClientRect()
          const px = ((event.clientX - rect.left) / rect.width) * WIDTH
          const index = Math.round(((px - PAD.left) / innerW) * (data.length - 1))
          setHover(Math.min(data.length - 1, Math.max(0, index)))
        }}
      >
        {[0, 0.5, 1].map((t) => (
          <g key={t}>
            <line className="aia-grid" x1={PAD.left} x2={WIDTH - PAD.right} y1={y(max * t)} y2={y(max * t)} />
            <text className="aia-axis" x={PAD.left - 6} y={y(max * t) + 4} textAnchor="end">{format(max * t)}</text>
          </g>
        ))}
        {/* Evenly spaced labels; the last point is labelled only if it doesn't crowd the previous label. */}
        {data.map((d, i) => (i % labelEvery === 0 || (i === data.length - 1 && i % labelEvery >= labelEvery / 2)) && (
          <text key={d.label} className="aia-axis" x={x(i)} y={HEIGHT - 8} textAnchor="middle">{d.label}</text>
        ))}
        {segments.map((path) => <path key={path} className="aia-line-path" d={path} />)}
        {data.map((d, i) => d.value !== null && (
          <circle key={d.label} className="aia-line-dot" cx={x(i)} cy={y(d.value)} r={hover === i ? 5 : 3} />
        ))}
        {hover !== null && <line className="aia-crosshair" x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={PAD.top + innerH} />}
      </svg>
      {hovered && (
        <div className="aia-tooltip" style={{ left: `${(x(hover ?? 0) / WIDTH) * 100}%` }}>
          <strong>{hovered.label}</strong>
          <span>{format(hovered.value)}</span>
          {hovered.detail && <span className="aia-muted">{hovered.detail}</span>}
        </div>
      )}
    </div>
  )
}
