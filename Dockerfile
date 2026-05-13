# Stage 1: Build the JAR using Maven and Java 11
FROM maven:3.9.6-eclipse-temurin-11 AS builder

# Set working directory
WORKDIR /app

# Copy everything into the container
COPY . .

# Copy dependencies and build a fat JAR
RUN mvn clean package -DskipTests dependency:copy-dependencies

# Stage 2: Use a smaller image for running the app
FROM eclipse-temurin:11-jre-alpine

# Set working directory in the runtime container
WORKDIR /app

# Copy app JAR and all dependency JARs from builder
COPY --from=builder /app/target/extension-custom-logic-1.0.0.jar app.jar
COPY --from=builder /app/target/dependency/*.jar lib/

# Copy property/config files from local context
COPY token.properties token.properties
COPY config.properties config.properties

# Run the app with full classpath (app + dependencies)
CMD ["java", "-cp", "app.jar:lib/*", "org.example.ExtensionCustomLogic"]
