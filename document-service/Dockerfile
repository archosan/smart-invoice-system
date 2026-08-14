# syntax=docker/dockerfile:1
# Build context: repo kökü (docker-compose.yml).

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl document-service -am package -DskipTests \
 && cp document-service/target/document-service-*.jar /workspace/app.jar

FROM eclipse-temurin:25-jre
RUN groupadd --system app && useradd --system --gid app app

# Named volume ilk bağlanışta bu dizinin sahipliğini devralır.
RUN mkdir -p /data/documents && chown app:app /data/documents

WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "app.jar"]
