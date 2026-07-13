# Backend Code Structure

This document explains the backend package structure of the Virtual Kitchen application and the responsibility of each main folder.

## Overall architecture

The backend is a Spring Boot application with a layered architecture:

1. Controller layer handles HTTP requests and returns responses.
2. Service layer implements business logic.
3. Repository layer handles database access.
4. Model layer defines domain entities.
5. DTO layer defines API payloads.
6. Mapper layer converts between entities and DTOs.
7. Config and utils support cross-cutting concerns.

## Main package structure

```text
backend/src/main/java/com/processVisualisation/virtualKitchen/
├── config/
├── controller/
├── dto/
├── exception/
├── mapper/
├── model/
├── repository/
├── service/
├── utils/
├── MongoVerifyRunner.java
└── VirtualKitchenApplication.java
```

## Supporting resources

```text
backend/src/main/resources/
└── application.properties
```

## Package responsibilities

### Root package

- `VirtualKitchenApplication.java`
  - Main Spring Boot entry point.
  - Starts the application and initializes the Spring context.
- `MongoVerifyRunner.java`
  - Helper runner used for MongoDB verification, connectivity checks, or data initialization during application startup.

### `config`

- Contains application configuration classes.
- Example: security setup, authentication/authorization rules, cross-cutting configuration.
- Responsibilities:
  - Configure beans and security filters.
  - Manage environment-specific settings.
  - Apply global application behavior.

### `controller`

- Contains REST controllers for the API.
- Each controller exposes endpoints for a specific domain.
- Responsibilities:
  - Accept and validate incoming HTTP requests.
  - Invoke service layer operations.
  - Return structured API responses.

### `service`

- Contains core business logic.
- Often organized as interfaces and implementation classes.
- Responsibilities:
  - Apply business rules and workflows.
  - Coordinate actions across multiple repositories.
  - Prepare and validate data for persistence and response.

### `repository`

- Contains Spring Data repositories for database access.
- Each repository handles persistence for a specific entity.
- Responsibilities:
  - Perform CRUD operations.
  - Define custom queries and data access methods.
  - Keep persistence logic separate from business logic.

### `model`

- Contains domain entities and enums.
- Entities represent persisted data and business objects.
- Responsibilities:
  - Define entity fields and relationships.
  - Map objects to database collections or tables.
  - Represent application state.

### `dto`

- Contains Data Transfer Objects for API communication.
- DTOs separate the external API contract from internal entities.
- Responsibilities:
  - Define request payloads and response shapes.
  - Prevent direct exposure of internal entity structures.
  - Simplify validation and API versioning.

### `mapper`

- Contains mapping utilities that convert entities to DTOs and vice versa.
- Responsibilities:
  - Centralize transformation logic.
  - Reduce boilerplate mapping code in services and controllers.
  - Keep data conversion consistent.

### `exception`

- Contains custom exception classes and global exception handlers.
- Responsibilities:
  - Capture error conditions consistently.
  - Return meaningful error payloads to API clients.
  - Handle exceptions such as not found, validation failures, and server errors.

### `utils`

- Contains shared helper and utility classes.
- Responsibilities:
  - Provide reusable support functionality.
  - Standardize response objects, parsing, and common utilities.

## Typical request flow

A request typically flows as follows:

```text
Client -> Controller -> Service -> Repository -> Database
```

The response travels back through the same layers.

## Example: Ingredient request flow

- `IngredientController` receives the request.
- The controller validates input and forwards it to a service.
- The service applies business rules and calls a repository.
- `IngredientRepository` reads or writes data.
- The service maps the entity to a DTO.
- The controller returns the DTO response.

## Summary

The backend is organized into clear responsibility layers:

- Controllers manage API endpoints.
- Services implement business rules.
- Repositories manage persistence.
- Models define domain data.
- DTOs define API payloads.
- Mappers convert between models and DTOs.
- Config and utils provide shared, cross-cutting behavior.
