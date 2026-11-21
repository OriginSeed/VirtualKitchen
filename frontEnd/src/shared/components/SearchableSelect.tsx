import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import './SearchableSelect.css'

export type SearchableSelectOption = {
  value: string
  label: string
  icon?: string
  category?: string
  /** Secondary line under the label (e.g. what an action means). */
  description?: string
  /** Extra search terms that aren't displayed (e.g. catalog aliases: "dhania" finds Coriander Leaves). */
  keywords?: readonly string[]
}

const matchesQuery = (option: SearchableSelectOption, normalizedQuery: string) =>
  option.label.toLowerCase().includes(normalizedQuery)
  || (option.category?.toLowerCase().includes(normalizedQuery) ?? false)
  || (option.keywords?.some((keyword) => keyword.toLowerCase().includes(normalizedQuery)) ?? false)

type SearchableSelectProps = {
  value: string
  onChange: (value: string) => void
  options: SearchableSelectOption[]
  placeholder?: string
  emptyLabel?: string
  className?: string
}

export default function SearchableSelect({
  value,
  onChange,
  options,
  placeholder = 'Select…',
  emptyLabel = 'No matches found',
  className,
}: SearchableSelectProps) {
  const [isOpen, setIsOpen] = useState(false)
  const [query, setQuery] = useState('')
  const containerRef = useRef<HTMLDivElement>(null)
  const searchInputRef = useRef<HTMLInputElement>(null)

  const selectedOption = useMemo(() => options.find(option => option.value === value), [options, value])

  const groupedOptions = useMemo(() => {
    const normalizedQuery = query.trim().toLowerCase()
    const filtered = normalizedQuery
      ? options.filter(option => matchesQuery(option, normalizedQuery))
      : options

    const groups = new Map<string, SearchableSelectOption[]>()
    filtered.forEach(option => {
      const key = option.category ?? ''
      const existing = groups.get(key)
      if (existing) existing.push(option)
      else groups.set(key, [option])
    })
    return Array.from(groups.entries())
  }, [options, query])

  useEffect(() => {
    if (!isOpen) return
    const handleClickOutside = (event: PointerEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as globalThis.Node)) {
        setIsOpen(false)
        setQuery('')
      }
    }
    // pointerdown, not mousedown: iOS sends no mouse events for taps on non-clickable areas.
    document.addEventListener('pointerdown', handleClickOutside)
    return () => document.removeEventListener('pointerdown', handleClickOutside)
  }, [isOpen])

  useEffect(() => {
    // Touch screens: focusing the search box would open the keyboard over the list; the user taps it to search.
    if (isOpen && !window.matchMedia?.('(pointer: coarse)').matches) searchInputRef.current?.focus()
  }, [isOpen])

  const handleSelect = useCallback((optionValue: string) => {
    onChange(optionValue)
    setIsOpen(false)
    setQuery('')
  }, [onChange])

  return (
    <div className={`searchable-select ${className ?? ''}`} ref={containerRef}>
      <button
        type="button"
        className="searchable-select-trigger"
        onClick={() => setIsOpen(prev => !prev)}
      >
        <span className="searchable-select-trigger-label">
          {selectedOption ? `${selectedOption.icon ? `${selectedOption.icon} ` : ''}${selectedOption.label}` : placeholder}
        </span>
        <span className="searchable-select-chevron">{isOpen ? '▲' : '▼'}</span>
      </button>

      {isOpen && (
        <div className="searchable-select-dropdown">
          <input
            ref={searchInputRef}
            type="text"
            className="searchable-select-search"
            placeholder="Search…"
            value={query}
            onChange={e => setQuery(e.target.value)}
          />
          <div className="searchable-select-options">
            {groupedOptions.length === 0 && (
              <div className="searchable-select-empty">{emptyLabel}</div>
            )}
            {groupedOptions.map(([category, categoryOptions]) => (
              <div key={category || 'uncategorized'} className="searchable-select-group">
                {category && <div className="searchable-select-group-label">{category}</div>}
                {categoryOptions.map(option => (
                  <button
                    type="button"
                    key={option.value}
                    className={`searchable-select-option ${option.value === value ? 'searchable-select-option--selected' : ''}`}
                    onClick={() => handleSelect(option.value)}
                  >
                    {option.icon && <span className="searchable-select-option-icon">{option.icon}</span>}
                    {option.description ? (
                      <span className="searchable-select-option-text">
                        <span>{option.label}</span>
                        <span className="searchable-select-option-description">{option.description}</span>
                      </span>
                    ) : (
                      <span>{option.label}</span>
                    )}
                  </button>
                ))}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
