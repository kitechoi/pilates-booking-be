# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies

COPY src src
# bootJar only (not `build`/`jar`) so the Spring Boot plain jar is never produced,
# keeping the build/libs/*.jar glob below unambiguous.
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring spring
COPY --from=build /workspace/build/libs/*.jar app.jar
RUN chown spring:spring app.jar
USER spring

EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
