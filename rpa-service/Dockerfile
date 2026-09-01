# syntax=docker/dockerfile:1
# Build context: repo kökü (docker-compose.yml).

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl rpa-service -am package -DskipTests \
 && cp rpa-service/target/rpa-service-*.jar /workspace/app.jar

# JDK 25 + headless Chromium. Etiket, kök pom'daki playwright.version ile aynı olmalı.
FROM mcr.microsoft.com/playwright/java:v1.63.0-noble
# Tarayıcı imajda (PLAYWRIGHT_BROWSERS_PATH); çalışırken indirilmez.
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1
RUN groupadd --system app && useradd --system --gid app app

# Named volume ilk bağlanışta bu dizinin sahipliğini devralır.
RUN mkdir -p /data/screenshots && chown app:app /data/screenshots

WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "app.jar"]
