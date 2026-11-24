FROM eclipse-temurin:21.0.13_9-jdk-jammy AS build
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
ENV MAVEN_USER_HOME=/root/.m2
COPY .mvn/maven.config .mvn/maven.config
COPY .mvn/wrapper/maven-wrapper.properties .mvn/wrapper/maven-wrapper.properties
COPY mvnw pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 --mount=type=cache,target=/workspace/.mvn/repository \
    sh ./mvnw --batch-mode -DskipTests package

FROM eclipse-temurin:21.0.13_9-jre-jammy
WORKDIR /app
LABEL org.opencontainers.image.title="CryptoBench" \
      org.opencontainers.image.version="1.1.0"

# The Compose healthcheck uses wget. Install only this small runtime utility
# and remove the package index so the application image stays self-contained.
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/cryptobench-1.1.0.jar /app/cryptobench.jar
COPY docker/entrypoint.sh /usr/local/bin/cryptobench-entrypoint
RUN groupadd -r app && useradd -r -g app app \
    && mkdir -p /app/var && chown -R app:app /app \
    && chmod 0755 /usr/local/bin/cryptobench-entrypoint
ENV SERVER_ADDRESS=0.0.0.0
ENV PORT=4173
EXPOSE 4173
HEALTHCHECK --interval=10s --timeout=3s --start-period=20s --retries=12 \
    CMD wget --spider --quiet http://127.0.0.1:4173/actuator/health || exit 1
ENTRYPOINT ["/usr/local/bin/cryptobench-entrypoint", "java", "-jar", "/app/cryptobench.jar"]
