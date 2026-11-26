# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /app

# Copy backend and frontend catalog required by Maven
COPY backend ./backend
COPY frontEnd/src/shared/catalog/stepCatalogs.data.json ./frontEnd/src/shared/catalog/stepCatalogs.data.json

WORKDIR /app/backend

RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=build /app/backend/target/virtualKitchen-0.0.1-SNAPSHOT.jar app.jar

ENV PORT=8080

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java -Dserver.port=${PORT:-8080} -jar app.jar"]
