FROM eclipse-temurin:21-jre
WORKDIR /app

# The Compose healthcheck uses wget. Install only this small runtime utility
# and remove the package index so the application image stays self-contained.
RUN apt-get update \
    && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*

COPY target/cryptobench-1.0.0-SNAPSHOT.jar /app/cryptobench.jar
ENV SERVER_ADDRESS=0.0.0.0
ENV PORT=4173
EXPOSE 4173
ENTRYPOINT ["java", "-jar", "/app/cryptobench.jar"]
