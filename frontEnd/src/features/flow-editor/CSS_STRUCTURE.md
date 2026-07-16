# Flow editor styling structure

## Purpose

This area now uses a small shared style layer for reusable tokens and component-oriented patterns, with Tailwind used for layout and spacing.

## How to read the styles

- Shared tokens live in [frontEnd/src/features/flow-editor/styles/flow-editor.css](frontEnd/src/features/flow-editor/styles/flow-editor.css).
- Tailwind handles layout, spacing, flex/grid positioning, and responsive structure.
- Component-specific styles remain local to the relevant component when needed.

## Conventions

- Prefer semantic class names such as `header`, `card`, `title`, `actionButton`, and `sectionHeading`.
- Keep layout in Tailwind utilities and keep visual treatment in shared CSS classes.
- Reuse shared classes instead of duplicating similar styles.
- Avoid deep CSS nesting and keep selectors shallow.

## Files to know

- [frontEnd/src/features/flow-editor/styles/flow-editor.css](frontEnd/src/features/flow-editor/styles/flow-editor.css): shared tokens and reusable component styles.
- [frontEnd/src/features/flow-editor/components/canvas/FlowCanvas.tsx](frontEnd/src/features/flow-editor/components/canvas/FlowCanvas.tsx): layout and editor shell.
- [frontEnd/src/features/flow-editor/components/sidebar/Sidebar.tsx](frontEnd/src/features/flow-editor/components/sidebar/Sidebar.tsx): sidebar interaction styles.
- [frontEnd/src/features/flow-editor/components/toolbar/PropertiesPanel.tsx](frontEnd/src/features/flow-editor/components/toolbar/PropertiesPanel.tsx): properties panel editor controls.
- [frontEnd/src/features/flow-editor/RecipeHomePage.tsx](frontEnd/src/features/flow-editor/RecipeHomePage.tsx): recipe landing page.
