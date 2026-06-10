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
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/imini.jar /app/imini.jar
# the agent's working directory + persistence live under these (mounted in compose)
RUN mkdir -p /workspace /app/.imini
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/imini.jar"]
