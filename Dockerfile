# Build Stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

# Copy root pom and module poms
COPY pom.xml .
COPY payment-domain/pom.xml payment-domain/
COPY payment-application/pom.xml payment-application/
COPY payment-infrastructure/pom.xml payment-infrastructure/
COPY payment-api/pom.xml payment-api/

# Copy source code
COPY payment-domain/src payment-domain/src
COPY payment-application/src payment-application/src
COPY payment-infrastructure/src payment-infrastructure/src
COPY payment-api/src payment-api/src

# Build executable jar
RUN apk add --no-cache maven && \
    mvn clean package -DskipTests

# Runtime Stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /workspace/payment-api/target/payment-api-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]

