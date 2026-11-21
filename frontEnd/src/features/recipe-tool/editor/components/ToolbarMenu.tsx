import { createContext, useCallback, useContext, useEffect, useId, useLayoutEffect, useRef, useState } from 'react'
import type { CSSProperties, KeyboardEvent as ReactKeyboardEvent, ReactNode } from 'react'
import '../styles/RecipeEditorToolbar.css'

/** Where the menu opens relative to its button: below/above/beside it, aligned to its start or end edge. */
export type ToolbarMenuPlacement = 'bottom-start' | 'bottom-end' | 'top-start' | 'right-start'

type ToolbarMenuProps = {
  /** The button's content. */
  label: ReactNode
  /** Accessible name of the button (its tooltip too, unless `title` is given). */
  ariaLabel: string
  title?: string
  buttonStyle?: CSSProperties
  buttonClassName?: string
  placement?: ToolbarMenuPlacement
  width?: number
  disabled?: boolean
  /** The menu's items (MenuItem / MenuHeading / MenuDivider). */
  children: ReactNode
}

const GAP = 6

/** Dismisses the open menu and returns focus to its button — what a MenuItem does after its action. */
const CloseMenuContext = createContext<() => void>(() => {})

/**
 * A button that opens a small popover menu — the Recipe Editor's "+ Add" and "✨ AI" menus. The
 * popover is positioned `fixed` from the button's on-screen box, so it is never clipped by the
 * narrow, overflow-hidden panels it can open from (the process sidebar or its collapsed rail).
 * Closes on outside click, Escape (focus returns to the button), or choosing an item; ArrowUp/Down
 * move between items.
 */
export default function ToolbarMenu({
  label,
  ariaLabel,
  title,
  buttonStyle,
  buttonClassName,
  placement = 'bottom-start',
  width = 240,
  disabled = false,
  children,
}: ToolbarMenuProps) {
  const [open, setOpen] = useState(false)
  const [position, setPosition] = useState<CSSProperties>({})
  const buttonRef = useRef<HTMLButtonElement | null>(null)
  const menuRef = useRef<HTMLDivElement | null>(null)
  const menuId = useId()

  const close = useCallback(() => {
    setOpen(false)
    buttonRef.current?.focus()
  }, [])

  const place = useCallback(() => {
    const rect = buttonRef.current?.getBoundingClientRect()
    if (!rect) return
    const maxLeft = window.innerWidth - width - GAP
    if (placement === 'right-start') {
      setPosition({ left: Math.min(rect.right + GAP, maxLeft), top: rect.top })
    } else if (placement === 'top-start') {
      setPosition({ left: Math.min(rect.left, maxLeft), bottom: window.innerHeight - rect.top + GAP })
    } else if (placement === 'bottom-end') {
      setPosition({ right: Math.max(GAP, window.innerWidth - rect.right), top: rect.bottom + GAP })
    } else {
      setPosition({ left: Math.min(rect.left, maxLeft), top: rect.bottom + GAP })
    }
  }, [placement, width])

  useLayoutEffect(() => {
    if (open) place()
  }, [open, place])

  useEffect(() => {
    if (!open) return undefined
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Node
      if (menuRef.current?.contains(target) || buttonRef.current?.contains(target)) return
      setOpen(false)
    }
    // pointerdown: iOS sends no mouse events for taps on non-clickable areas such as the canvas.
    window.addEventListener('pointerdown', onPointerDown)
    window.addEventListener('resize', place)
    // The first item gets focus, so the menu is usable from the keyboard right away.
    menuRef.current?.querySelector<HTMLElement>('[role="menuitem"]:not([disabled])')?.focus()
    return () => {
      window.removeEventListener('pointerdown', onPointerDown)
      window.removeEventListener('resize', place)
    }
  }, [open, place])

  const handleMenuKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault()
      close()
      return
    }
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
    event.preventDefault()
    const items = Array.from(menuRef.current?.querySelectorAll<HTMLElement>('[role="menuitem"]:not([disabled])') ?? [])
    if (items.length === 0) return
    const index = items.indexOf(document.activeElement as HTMLElement)
    const next = event.key === 'ArrowDown' ? (index + 1) % items.length : (index - 1 + items.length) % items.length
    items[next].focus()
  }

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        className={buttonClassName}
        style={buttonStyle}
        aria-label={ariaLabel}
        title={title ?? ariaLabel}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        disabled={disabled}
        onClick={() => setOpen((value) => !value)}
      >
        {label}
      </button>
      {open && (
        <div
          ref={menuRef}
          id={menuId}
          role="menu"
          aria-label={ariaLabel}
          className="recipe-toolbar-menu"
          style={{ ...position, width }}
          onKeyDown={handleMenuKeyDown}
        >
          <CloseMenuContext.Provider value={close}>{children}</CloseMenuContext.Provider>
        </div>
      )}
    </>
  )
}

type MenuItemProps = {
  icon?: ReactNode
  label: ReactNode
  description?: ReactNode
  /** Shown at the item's end, e.g. a progress count. */
  trailing?: ReactNode
  onSelect: () => void
  disabled?: boolean
  title?: string
}

export function MenuItem({ icon, label, description, trailing, onSelect, disabled = false, title }: MenuItemProps) {
  const close = useContext(CloseMenuContext)
  return (
    <button
      type="button"
      role="menuitem"
      className="recipe-toolbar-menu-item"
      disabled={disabled}
      title={title}
      onClick={() => {
        close()
        onSelect()
      }}
    >
      {icon != null && <span className="recipe-toolbar-menu-icon" aria-hidden>{icon}</span>}
      <span className="recipe-toolbar-menu-text">
        <span className="recipe-toolbar-menu-label">{label}</span>
        {description && <span className="recipe-toolbar-menu-description">{description}</span>}
      </span>
      {trailing != null && <span className="recipe-toolbar-menu-trailing">{trailing}</span>}
    </button>
  )
}

export function MenuHeading({ children }: { children: ReactNode }) {
  return <div className="recipe-toolbar-menu-heading" role="presentation">{children}</div>
}

export function MenuDivider() {
  return <div className="recipe-toolbar-menu-divider" role="separator" />
}
