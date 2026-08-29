# Build the fat jar. Tests are skipped here: they need FFmpeg and CI already ran them.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# FFmpeg is a runtime dependency, not a build one: encoding, recording and thumbnails shell out to it.
FROM eclipse-temurin:25-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/*

# AppPaths resolves data/ and frontend/ against the working directory, so the app must run from /app.
WORKDIR /app
COPY --from=build /src/target/theatrum-*.jar theatrum.jar
COPY frontend ./frontend

# config.yml holds live_stream_key secrets — mount it, never bake it in.
EXPOSE 8080 1935
VOLUME /app/data
ENTRYPOINT ["java", "-jar", "/app/theatrum.jar"]
CMD ["--config", "/config/config.yml"]
