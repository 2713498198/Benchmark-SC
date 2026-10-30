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

# The Compose healthcheck uses wget. Install only this small runtime utility
# and remove the package index so the application image stays self-contained.
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/target/cryptobench-1.0.0-SNAPSHOT.jar /app/cryptobench.jar
ENV SERVER_ADDRESS=0.0.0.0
ENV PORT=4173
EXPOSE 4173
ENTRYPOINT ["java", "-jar", "/app/cryptobench.jar"]
