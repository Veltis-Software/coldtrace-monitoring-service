# syntax=docker/dockerfile:1.7
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY --from=shared . /shared/
RUN mvn -B -f /shared/pom.xml -DskipTests install
COPY pom.xml ./
COPY src/ ./src/
RUN mvn -B -DskipTests package
RUN cp target/coldtrace-monitoring-service-0.1.0-SNAPSHOT.jar /app.jar
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 coldtrace
COPY --from=build --chown=10001:10001 /app.jar ./app.jar
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70.0"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
