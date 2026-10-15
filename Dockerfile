FROM eclipse-temurin:17-jdk-jammy@sha256:43f4431cc895d37ceb115e2e1f160545ec45caee9c1cb4246fc6bb879d58aeda AS build
WORKDIR /build
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties gradle.lockfile ./
COPY gradle ./gradle
COPY src/main ./src/main
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

FROM eclipse-temurin:17-jre-jammy@sha256:8993f1aed8b25fcea7a7047a7949c1866fa558fc6830d938c22c4f13b26be9d7
# Security fixes not yet included in the pinned Temurin base image.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       libssl3=3.0.2-0ubuntu1.30 \
       libfreetype6=2.11.1+dfsg-1ubuntu0.4 \
       libpng16-16=1.6.37-3ubuntu0.7 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build --chown=app:app /build/build/libs/helpdesk.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/app.jar"]
