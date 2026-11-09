FROM node:24-bookworm-slim AS web
WORKDIR /workspace/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/index.html web/tsconfig.json web/vite.config.ts ./
COPY web/src ./src
RUN npm run build

FROM maven:3.9.12-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
COPY benchmarks/scenario-families.json ./benchmarks/scenario-families.json
COPY --from=web /workspace/web/dist ./src/main/resources/static
# Unit/HTTP tests run here. Docker-dependent integration tests run in CI/verify.
RUN mvn --batch-mode --no-transfer-progress package

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl && addgroup -S modelhub && adduser -S -G modelhub modelhub
WORKDIR /app
COPY --from=build --chown=modelhub:modelhub /workspace/target/modelhub-0.3.0.jar app.jar
USER modelhub
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 CMD curl --fail --silent http://localhost:8080/api/health || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
