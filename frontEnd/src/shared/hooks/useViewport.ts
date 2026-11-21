import { useSyncExternalStore } from 'react'

/**
 * Screen-size tiers shared by layouts that need to switch behaviour in code, not just CSS. They match the CSS
 * breakpoints documented in styles/responsive.css:
 *   phone   width <= 640, or a phone held sideways (landscape and height <= 500)
 *   tablet  641..1024
 *   desktop > 1024
 */
export const PHONE_MAX_WIDTH = 640
export const TABLET_MAX_WIDTH = 1024
export const PHONE_LANDSCAPE_MAX_HEIGHT = 500

export interface Viewport {
  width: number
  height: number
  /** Phone held upright (narrow, taller than wide). */
  isPhonePortrait: boolean
  /** Phone held sideways: short and wider than tall. */
  isPhoneLandscape: boolean
  /** Either phone orientation. */
  isPhone: boolean
  /** 641..1024 wide and not a sideways phone. */
  isTablet: boolean
  isLandscape: boolean
  /** Touch is the main input (no precise pointer). */
  coarse: boolean
}

function read(): Viewport {
  if (typeof window === 'undefined') {
    return {
      width: 1366, height: 768, isPhonePortrait: false, isPhoneLandscape: false, isPhone: false,
      isTablet: false, isLandscape: true, coarse: false,
    }
  }
  const width = window.innerWidth
  const height = window.innerHeight
  const isLandscape = width > height
  const isPhoneLandscape = isLandscape && height <= PHONE_LANDSCAPE_MAX_HEIGHT
  const isPhonePortrait = !isLandscape && width <= PHONE_MAX_WIDTH
  const isPhone = isPhonePortrait || isPhoneLandscape || width <= PHONE_MAX_WIDTH
  const coarse = window.matchMedia?.('(pointer: coarse)').matches ?? false
  return {
    width,
    height,
    isPhonePortrait,
    isPhoneLandscape,
    isPhone,
    isTablet: !isPhone && width <= TABLET_MAX_WIDTH,
    isLandscape,
    coarse,
  }
}

// One snapshot shared by every subscriber; replaced only when a field actually changes so React doesn't re-render
// on every resize event.
let snapshot: Viewport = read()

function sameViewport(a: Viewport, b: Viewport) {
  return (Object.keys(a) as (keyof Viewport)[]).every((key) => a[key] === b[key])
}

function subscribe(onChange: () => void) {
  const update = () => {
    const next = read()
    if (!sameViewport(snapshot, next)) {
      snapshot = next
      onChange()
    }
  }
  window.addEventListener('resize', update)
  window.addEventListener('orientationchange', update)
  return () => {
    window.removeEventListener('resize', update)
    window.removeEventListener('orientationchange', update)
  }
}

/** The current screen tier, updated on resize and rotation. */
export function useViewport(): Viewport {
  return useSyncExternalStore(subscribe, () => snapshot, () => snapshot)
}
