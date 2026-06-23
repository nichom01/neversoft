# Stage 1 — compile native image
FROM quay.io/quarkus/ubi-quarkus-mandrel-builder-image:jdk-21 AS build
USER quarkus
WORKDIR /code
COPY --chown=quarkus:quarkus lib-consumer-map/pom.xml lib-consumer-map/pom.xml
COPY --chown=quarkus:quarkus lib-consumer-map/src/ lib-consumer-map/src/
RUN mvn -f lib-consumer-map/pom.xml install -DskipTests -B -q
COPY --chown=quarkus:quarkus svc-risk/pom.xml svc-risk/pom.xml
RUN mvn -f svc-risk/pom.xml dependency:go-offline -B -q
COPY --chown=quarkus:quarkus svc-risk/src/ svc-risk/src/
RUN mvn -f svc-risk/pom.xml package -Pnative -DskipTests -q

# Stage 2 — minimal runtime image
FROM quay.io/quarkus/quarkus-micro-image:2.0
WORKDIR /work
COPY --from=build /code/svc-risk/target/*-runner /work/application
EXPOSE 8082
USER 1001
CMD ["./application", "-Dquarkus.http.host=0.0.0.0"]
