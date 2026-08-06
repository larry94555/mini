# syntax=docker/dockerfile:1

# ---- build stage: compile + package the Spring Boot jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# copy the POM first so the dependency layer is cached across source-only changes
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline || true
COPY src ./src
RUN mvn -q -DskipTests package
# with <finalName>imini</finalName> in pom.xml this is target/imini.jar

# ---- run stage: small JRE image that just runs the jar ----
FROM eclipse-temurin:24-jre
WORKDIR /app
# curl is used by the container HEALTHCHECK to poll the readiness probe
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/target/imini.jar /app/imini.jar
# the agent's working directory + persistence live under these (mounted in compose)
RUN mkdir -p /workspace /app/.imini
EXPOSE 8080
# readiness probe: /healthz returns 200 while serving (status ok|degraded); curl -f fails only on non-2xx
HEALTHCHECK --interval=30s --timeout=3s --start-period=45s --retries=3 \
    CMD curl -fsS http://localhost:8080/healthz || exit 1
ENTRYPOINT ["java", "-jar", "/app/imini.jar"]
