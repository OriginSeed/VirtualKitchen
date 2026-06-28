# Backend Code Structure

This document explains the backend package structure of the Virtual Kitchen application and the responsibility of each folder.

## Overall architecture

The backend follows a typical Spring Boot layered architecture:

1. Controller layer receives HTTP requests.
2. Service layer contains business logic.
3. Repository layer handles database access.
4. Model layer defines the domain entities.
5. DTO layer defines request/response payloads.
6. Mapper layer converts between entities and DTOs.

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
└── VirtualKitchenApplication.java
```

## Package responsibilities

### 1. Root package

- `VirtualKitchenApplication.java`
- The main Spring Boot entry point.
- Starts the application and configures the Spring context.

### 2. `config`

- Contains application configuration classes.
- Example: `SecurityConfig` handles security rules, authentication, and authorization.
- This package is used for cross-cutting concerns such as security and app setup.

### 3. `controller`

- Contains REST controllers for the API.
- Each controller exposes endpoints for a specific domain such as `Ingredient`, `Kitchen`, `ProcessTemplate`, `User`, and `Inventory`.
- Responsibilities:
  - Accept incoming HTTP requests.
  - Validate request input.
  - Call the appropriate service.
  - Return responses to the client.

### 4. `service`

- Contains the core business logic of the application.
- Organized around interfaces and implementation classes.
- Example:
  - `IIngredientService` defines the contract.
  - `IngredientServiceImpl` (inside `impl`) implements the logic.
- Responsibilities:
  - Apply business rules.
  - Coordinate operations across multiple repositories.
  - Prepare data before persistence or response.

### 5. `repository`

- Contains Spring Data repositories for database access.
- Each repository is responsible for CRUD operations on a specific entity.
- Example: `IngredientRepository` handles persistence for ingredients.
- Responsibilities:
  - Read and write data from the database.
  - Provide query methods for entities.
  - Keep database access code separate from business logic.

### 6. `model`

- Contains the domain entities and enums.
- These classes represent the database model and business objects.
- Examples:
  - `Ingredient`, `Kitchen`, `ProcessExecution`, `User`
  - Enums such as `ProcessStatus`, `StepStatus`, `UnitType`
- Responsibilities:
  - Define the structure of the data.
  - Map to database tables using JPA annotations.
  - Represent the real-world concepts of the application.

### 7. `dto`

- Contains Data Transfer Objects used for API communication.
- DTOs separate the API contract from the database model.
- Examples:
  - `IngredientRequestDTO`
  - `IngredientResponseDTO`
  - `IngredientUpdateDTO`
- Responsibilities:
  - Define what data clients send to the API.
  - Define what data the API returns to clients.
  - Keep APIs flexible and avoid exposing internal entities directly.

### 8. `mapper`

- Contains mapping classes to convert between entities and DTOs.
- Example: `IngredientMapper` converts `Ingredient` to `IngredientResponseDTO` and vice versa.
- Responsibilities:
  - Reduce repetitive manual mapping code.
  - Keep controller and service layers cleaner.

### 9. `exception`

- Contains custom exception classes and global error handling.
- Examples:
  - `UserNotFoundException`
  - `GlobalExceptionHandler`
- Responsibilities:
  - Handle invalid or unexpected situations consistently.
  - Return meaningful error responses to clients.

### 10. `utils`

- Contains helper or utility classes shared across the application.
- Example: `ApiResponse` for consistent API response formatting.
- Responsibilities:
  - Provide reusable support functionality.
  - Avoid code duplication in multiple layers.

## Typical request flow

A typical API request usually follows this path:

```text
Client -> Controller -> Service -> Repository -> Database
```

And the response returns back through the same layers in reverse order.

## Example: Ingredient flow

- `IngredientController` receives the request.
- `IIngredientService` handles the business logic.
- `IngredientRepository` stores or retrieves the data.
- `Ingredient` model represents the persisted entity.
- `IngredientRequestDTO` and `IngredientResponseDTO` define the API payload.
- `IngredientMapper` converts between the entity and DTO.

## Summary

This backend is organized into clear layers:

- Controllers handle API endpoints.
- Services implement business rules.
- Repositories manage persistence.
- Models represent entities.
- DTOs define API input/output.
- Mappers connect models and DTOs.
- Exceptions and config provide safety and cross-cutting behavior.
