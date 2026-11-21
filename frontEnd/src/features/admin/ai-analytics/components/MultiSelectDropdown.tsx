import { useEffect, useRef, useState } from 'react'

interface MultiSelectDropdownProps {
  label: string
  hint?: string
  values: string[]
  selected: string[]
  valueLabel: (value: string) => string
  onChange: (selected: string[]) => void
}

/** A dropdown of checkboxes; nothing selected means "All". */
export default function MultiSelectDropdown({ label, hint, values, selected, valueLabel, onChange }: MultiSelectDropdownProps) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  // Opens towards whichever side has room: a dropdown near the right edge (the right column on a phone) opens
  // leftwards instead of pushing the page sideways. 200px is the menu's minimum width (aiAnalytics.css).
  const [alignRight, setAlignRight] = useState(false)
  const openMenu = () => {
    const box = ref.current?.getBoundingClientRect()
    setAlignRight(Boolean(box && box.left + 200 > document.documentElement.clientWidth - 8))
    setOpen(true)
  }

  useEffect(() => {
    if (!open) return
    const onPointer = (e: PointerEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOpen(false)
    }
    document.addEventListener('pointerdown', onPointer)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onPointer)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const summary = selected.length === 0 ? 'All'
    : selected.length === 1 ? valueLabel(selected[0])
    : `${selected.length} selected`
  const toggle = (value: string) =>
    onChange(selected.includes(value) ? selected.filter((v) => v !== value) : [...selected, value])

  return (
    <div className="aia-dropdown" ref={ref}>
      <span className="aia-dropdown-label" title={hint}>{label}</span>
      <button type="button" className={`aia-dropdown-button${selected.length ? ' is-set' : ''}`}
        aria-haspopup="true" aria-expanded={open} onClick={() => (open ? setOpen(false) : openMenu())}>
        <span className="aia-dropdown-value">{summary}</span>
        <span aria-hidden="true">▾</span>
      </button>
      {open && (
        <div className={`aia-dropdown-menu${alignRight ? ' is-right' : ''}`} role="group" aria-label={label}>
          <label className="aia-inline-field">
            <input type="checkbox" checked={selected.length === 0} onChange={() => onChange([])} />
            <span>All</span>
          </label>
          {values.length === 0 && <span className="aia-muted aia-small">No values yet</span>}
          {values.map((v) => (
            <label key={v} className="aia-inline-field">
              <input type="checkbox" checked={selected.includes(v)} onChange={() => toggle(v)} />
              <span>{valueLabel(v)}</span>
            </label>
          ))}
        </div>
      )}
    </div>
  )
}
