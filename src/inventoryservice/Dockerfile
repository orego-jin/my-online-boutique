# build JAR with Java 21 & Maven
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml .
RUN mvn -B dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

# img
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/inventory-service-simple-*.jar /app/app.jar

USER 1000:1000
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-jar", "/app/app.jar"]
