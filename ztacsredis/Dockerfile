# Use OpenJDK base image
FROM openjdk:21-jdk-slim

# Set time zone (optional)
ENV TZ=Asia/Kolkata

# Copy built JAR from target folder
ARG JAR_FILE=target/ztacsredis-0.0.1-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar

# Expose Spring Boot port (adjust if needed)
EXPOSE 8083

# Run the app
ENTRYPOINT ["java", "-jar", "/app.jar"]
