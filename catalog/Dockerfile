# Multi-stage image for the catalog service (owns catalog-db).
# Concept: docs/concepts/docker-fundamentals.md §5 (multi-stage) + §6 (root build context).
# Built from the REPO ROOT (context = .) because the Maven reactor needs the parent pom + mvnw
# wrapper, which live at the root — not inside this module folder.
#   docker build -f catalog/Dockerfile -t catalog .

# ---- build stage: Maven + JDK 21 compile the runnable jar -------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
# -pl catalog          build only this module
# -am                  "also make" the modules it depends on (the reactor resolves the graph)
# -DskipTests          the reactor build already gates tests; the image stage only packages
RUN mvn -q -pl catalog -am clean package -DskipTests

# ---- runtime stage: slim JRE + only the jar (Maven/JDK/source left behind) --------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/catalog/target/catalog-*.jar app.jar
EXPOSE 8081
# JSON/exec form so `docker stop` delivers SIGTERM to the JVM for a clean shutdown.
ENTRYPOINT ["java", "-jar", "app.jar"]
