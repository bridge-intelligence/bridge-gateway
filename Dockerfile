# Stage 1: Build
FROM gradle:8.4-jdk17 AS builder

WORKDIR /build

COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY src ./src

RUN gradle build -x test --no-daemon && \
    mv build/libs/*[!-plain].jar build/libs/app.jar

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine

ARG BUILD_VERSION=unknown
ARG BUILD_DATE=unknown
ARG GIT_COMMIT=unknown

LABEL org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.revision="${GIT_COMMIT}" \
      org.opencontainers.image.title="bridge-gateway" \
      org.opencontainers.image.vendor="Binari Digital"

RUN apk add --no-cache curl

WORKDIR /app

COPY --from=builder /build/build/libs/app.jar /app/app.jar

ENV SPRING_PROFILES_ACTIVE=dev

EXPOSE 4000

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:4000/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
