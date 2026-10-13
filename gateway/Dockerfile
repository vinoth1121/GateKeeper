FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY gateway/pom.xml gateway/
COPY mock-upstream/pom.xml mock-upstream/
RUN mvn -q -pl gateway -am dependency:go-offline || true
COPY gateway/src gateway/src
# glob copy: matches zero-or-more entries, so the build still works when console/ is absent
COPY console* console/
RUN mvn -q -pl gateway -am package -DskipTests

FROM eclipse-temurin:21-jre-jammy
# curl is required by the HEALTHCHECK (not present in the base JRE image)
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/gateway/target/*.jar /app/app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=12 --start-period=60s CMD curl -f http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java","-jar","/app/app.jar"]