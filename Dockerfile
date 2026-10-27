# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY config/ config/
COPY src/ src/
COPY docker/HealthCheck.java docker/HealthCheck.java
RUN chmod +x mvnw && ./mvnw --batch-mode -DskipTests package \
    && javac -d /workspace/healthcheck docker/HealthCheck.java

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
COPY --from=build --chown=app:app /workspace/target/backend-0.0.1-SNAPSHOT.jar app.jar
COPY --from=build --chown=app:app /workspace/healthcheck/HealthCheck.class HealthCheck.class
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
