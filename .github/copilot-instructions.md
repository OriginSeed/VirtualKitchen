# Cross-Cutting Workflow Rule

- If the user explicitly asks for architecture review before implementation, do not modify code first.
- First produce: (1) high-level architecture review, (2) prioritized improvements, (3) design feedback for planned node/component types, and (4) phased implementation plan with minimal breaking changes.
- Assess readiness for roadmap features when relevant (MCP integration, visualization engine, AI-assisted generation, execution engine, collaboration).

# GitHub Copilot Instructions - Frontend

## Tech Stack
- React 19
- TypeScript
- Vite
- Tailwind CSS

## General Principles
- Generate production-ready, maintainable code.
- Prioritize readability and maintainability over reducing lines of code.
- Keep implementations simple and easy to understand.
- Preserve existing behavior unless explicitly requested.
- Follow existing project architecture and conventions.
- Prefer composition over duplication.
- Avoid unnecessary abstractions.

## React Guidelines
- Use functional components only.
- Prefer reusable and composable components.
- Keep components focused on a single responsibility.
- Extract reusable logic into custom hooks when appropriate.
- Prefer props over duplicated state.
- Avoid unnecessary re-renders.
- Use TypeScript strictly; avoid `any` unless explicitly required.
- Use named exports unless project convention differs.

## Styling Guidelines
- Organize styles around components, not pages.
- Each component should own only its related styles.
- Remove duplicated CSS by extracting reusable styles.
- Use semantic class names based on purpose (e.g. `header`, `card`, `actionButton`) instead of appearance.
- Separate layout styles from component-specific styles whenever possible.
- Replace hardcoded colors, spacing, typography, radius, shadows, and similar values with shared design tokens or CSS variables.
- Keep CSS selectors shallow.
- Remove unused or dead CSS.
- Preserve responsiveness.
- Keep styling consistent across the application.

### CSS Property Order
1. Layout (display, position, flex/grid)
2. Size (width, height)
3. Spacing (margin, padding, gap)
4. Appearance (background, border, border-radius, shadow)
5. Typography (font-size, font-weight, color)
6. Animation / Transition

## Naming Conventions
- Use descriptive and meaningful names.
- Components: PascalCase.
- Functions & variables: camelCase.
- Constants: UPPER_SNAKE_CASE.
- Hooks: useXxx.
- Avoid abbreviations unless commonly understood.

## Code Quality
- Keep functions small and focused.
- Eliminate duplicated logic.
- Prefer reusable utilities over repeated code.
- Remove dead code.
- Avoid deeply nested conditions.
- Prefer early returns.
- Write self-explanatory code.

## Error Handling
- Never swallow exceptions.
- Always display user-friendly toast messages for recoverable errors.
- Log unexpected errors where appropriate.
- Handle loading and error states consistently.

## Comments
- Add comments only when they explain non-obvious logic or architectural decisions.
- Avoid obvious or redundant comments.

## Output Expectations
- Follow existing folder structure.
- Preserve existing functionality.
- Generate clean, reusable, scalable code suitable for long-term maintenance.




# GitHub Copilot Instructions - Backend

## Tech Stack
- Java
- Spring Boot
- Maven
- MongoDB
- Swagger / OpenAPI

## General Principles
- Generate production-ready, maintainable code.
- Prioritize readability and maintainability over reducing lines of code.
- Follow existing project architecture and conventions.
- Follow SOLID principles.
- Prefer composition over duplication.
- Keep implementations simple and easy to understand.
- Preserve backward compatibility unless explicitly requested.

## Architecture
Always follow the project architecture.

Controller
→ Service
→ Repository
→ Database

- Never bypass layers.
- Controllers should only handle HTTP requests, validation, and responses.
- Business logic belongs in the Service layer.
- Repository layer should only perform database operations.
- Keep responsibilities clearly separated.

## Coding Style
- Follow existing project coding conventions.
- Keep methods small and focused.
- Prefer early returns over nested conditions.
- Eliminate duplicated logic.
- Reuse existing utilities and helper classes.
- Avoid unnecessary abstractions.
- Prefer constructor injection.
- Avoid field injection.
- Use Lombok only where already adopted by the project.

## Naming Conventions
Use consistent camelCase naming.

Examples

- stName
- stEmail
- stWorkspaceId
- iCount
- iIndex
- iSize
- lUserId
- bEnabled
- bSuccess
- mapUsers
- listFiles
- setPermissions

Classes
- PascalCase

Constants
- UPPER_SNAKE_CASE

Packages
- lowercase

## Controller Guidelines
- Validate request parameters.
- Delegate business logic to the Service layer.
- Return appropriate HTTP status codes.
- Never access repositories directly.
- Keep controllers lightweight.

## Service Guidelines
- Implement all business logic.
- Validate business rules.
- Coordinate repository operations.
- Throw meaningful exceptions.
- Do not include HTTP-specific logic.

## Repository Guidelines
- Only perform MongoDB operations.
- Avoid business logic.
- Keep queries efficient.
- Prefer pagination where applicable.

## Models & DTOs
- Keep domain models and DTOs separate.
- Never expose internal entities directly through APIs.
- Use request and response DTOs.
- Follow consistent validation using Bean Validation annotations.

## Exception Handling
- Follow the project's global exception handling pattern.
- Use @ControllerAdvice where applicable.
- Throw specific exceptions instead of generic Exception.
- Never swallow exceptions.
- Return consistent API error responses.
- Log unexpected exceptions appropriately.

## Logging
- Use structured logging.
- Log meaningful events only.
- Never log passwords, secrets, or sensitive information.

## API Design
- Follow RESTful conventions.
- Use appropriate HTTP methods.
- Use meaningful endpoint names.
- Document APIs using Swagger/OpenAPI.
- Maintain backward compatibility whenever possible.

## MCP Compatibility
- Design APIs and services to be MCP-friendly.
- Define clear request and response schemas.
- Use strongly typed DTOs.
- Avoid dynamic or loosely typed payloads.
- Return structured, predictable JSON.
- Provide meaningful schema descriptions for tools.
- Design operations to be deterministic and idempotent whenever possible.
- Ensure APIs are easily exposable as MCP tools without additional transformation.

## Performance
- Avoid unnecessary database calls.
- Use indexes appropriately.
- Prefer pagination for large datasets.
- Avoid repeated computations.
- Keep queries efficient.

## Security
- Validate all external input.
- Never hardcode secrets.
- Follow authentication and authorization best practices.
- Sanitize user input where necessary.

## Testing
- Generate unit and integration tests for new functionality.
- Cover success, validation, edge cases, and failure scenarios.
- Follow existing testing conventions.

## Documentation
- Generate Swagger/OpenAPI annotations.
- Add JavaDoc only for public APIs and non-obvious logic.
- Avoid unnecessary comments.

## Comments
- Comment only complex business logic or architectural decisions.
- Prefer self-explanatory code over excessive comments.

## Output Expectations
- Follow existing package structure.
- Produce clean, scalable, reusable, and production-ready code.
- Preserve existing functionality unless explicitly requested.