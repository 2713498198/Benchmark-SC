FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/cryptobench-1.0.0-SNAPSHOT.jar /app/cryptobench.jar
ENV SERVER_ADDRESS=0.0.0.0
ENV PORT=4173
EXPOSE 4173
ENTRYPOINT ["java", "-jar", "/app/cryptobench.jar"]
