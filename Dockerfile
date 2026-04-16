FROM eclipse-temurin:17-jre
COPY target/connector-server-graphql-1.0.0-exec.jar /opt/connector-server-graphql.jar
EXPOSE 7338
CMD ["java", "-Duser.timezone=UTC", "-jar", "/opt/connector-server-graphql.jar"]
