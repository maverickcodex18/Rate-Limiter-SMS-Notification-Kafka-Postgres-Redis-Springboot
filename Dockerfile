# Multi Stage Builds

#------------------------ Stage 1: Building JAR File --------------------

# We use a Maven image that has JDK 21 pre-installed
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /build

# 1. Copy only the pom.xml first to cache dependencies
COPY pom.xml .

# 2. Download dependencies (this layer is cached unless pom.xml changes)
RUN mvn dependency:go-offline

# 3. Copy the source code
COPY src ./src

# 4. Build the application
# Added -X and -e as requested for detailed error logging
# 4. Build the application
RUN mvn clean package -DskipTests


#------------------------ Stage 2: Run --------------------

FROM eclipse-temurin:21-jre-alpine

WORKDIR /RateLimitedSMSGateway

COPY --from=build /build/target/*.jar app.jar

EXPOSE 8080
EXPOSE 2181
EXPOSE 9092
EXPOSE 5432
EXPOSE 29092

ENTRYPOINT ["java", "-jar", "app.jar"]
