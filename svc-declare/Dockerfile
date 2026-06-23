# Stage 1 — compile native image
FROM quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21 AS build
USER quarkus
WORKDIR /code
COPY --chown=quarkus:quarkus pom.xml .
RUN mvn -B dependency:go-offline -q
COPY --chown=quarkus:quarkus src/ ./src/
RUN mvn -B package -Pnative -DskipTests -q

# Stage 2 — minimal runtime image
FROM quay.io/quarkus/quarkus-micro-image:2.0
WORKDIR /work
COPY --from=build /code/target/*-runner /work/application
EXPOSE 8080
USER 1001
CMD ["./application", "-Dquarkus.http.host=0.0.0.0"]
