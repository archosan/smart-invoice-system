# syntax=docker/dockerfile:1
# Build context: repo kökü (docker-compose.yml).

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl mock-portal -am package -DskipTests \
 && cp mock-portal/target/mock-portal-*-exec.jar /workspace/app.jar

FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app app

WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "app.jar"]
