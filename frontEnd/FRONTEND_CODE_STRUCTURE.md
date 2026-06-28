# Frontend Code Structure

This document explains the frontend folder structure of the Virtual Kitchen application and the responsibility of each main directory.

## Overall architecture

The frontend is built with React and Vite. The code is organized by feature and responsibility so the UI is easier to maintain and extend.

## Main folder structure

```text
frontEnd/src/
├── app/
├── assets/
├── features/
├── shared/
├── styles/
├── App.css
├── index.css
└── main.tsx
```

## Folder responsibilities

### 1. `app`

- Contains the main application shell.
- Example: `App.tsx`
- Responsibilities:
  - Renders the root application layout.
  - Connects the main feature modules.
  - Acts as the top-level entry point for the UI.

### 2. `features`

- Contains feature-based modules of the application.
- Each feature is grouped around a specific business capability.
- In this project, the main feature is `flow-editor`.
- Responsibilities:
  - Keep related UI components, logic, and state close together.
  - Isolate feature-specific implementation from shared application code.

### 3. `features/flow-editor`

- Contains the visual flow editor feature.
- This is the main interactive area of the app.
- Responsibilities:
  - Render the workflow editor UI.
  - Manage nodes, edges, and canvas interactions.
  - Provide the interface for editing process flow steps.

#### Subfolders inside `flow-editor`

- `components/`
  - Contains UI building blocks for the editor.
  - Organized into `canvas`, `sidebar`, and `toolbar`.

- `nodes/`
  - Defines the different node types used in the editor.
  - Example: start node, recipe step node, condition node, section node.

- `edges/`
  - Contains edge-related logic and styling.

- `store/`
  - Holds the state management for the flow editor.
  - Usually contains the Zustand or similar store logic used to manage editor state.

### 4. `components` inside `flow-editor/components`

- `canvas/`
  - Contains the main drawing area and canvas-related helpers.
  - Handles rendering and interaction with nodes and connections.

- `sidebar/`
  - Contains the side panel UI used to configure or inspect selected elements.

- `toolbar/`
  - Contains top-level editor tools such as controls and property panels.

### 5. `shared`

- Contains reusable UI code that can be used across multiple features.
- Example: shared button component.
- Responsibilities:
  - Avoid duplication.
  - Provide common UI building blocks.

### 6. `assets`

- Stores static files such as images, icons, and other resources.
- Responsibilities:
  - Keep non-code assets organized.
  - Make them easy to import into components.

### 7. `styles`

- Contains global or shared styling files.
- Examples: global CSS rules and theme-level styles.
- Responsibilities:
  - Define application-wide visual styling.
  - Keep style concerns separate from component logic.

### 8. Root files

- `main.tsx`
  - The entry point of the frontend application.
  - Renders the React app into the DOM.

- `App.css` and `index.css`
  - Global stylesheet files.

## Typical frontend flow

A typical UI flow usually looks like this:

```text
User interaction -> React component -> Feature state/store -> UI update
```

For the flow editor, the interaction is usually:

```text
User edits node/edge -> component updates state -> store reflects change -> canvas re-renders
```

## Summary

The frontend is organized around:

- `app` for the root application shell
- `features` for major business features such as the flow editor
- `shared` for reusable UI components
- `assets` and `styles` for static resources and styling

This structure keeps the codebase modular and makes it easier to scale the application as new features are added.
