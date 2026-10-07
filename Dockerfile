# Builds and runs the VAR Races multiplayer server (server/ module).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon :server:jar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/server/build/libs/var-races-server.jar server.jar
# Render (and most hosts) tell the server which port to use through $PORT
ENV PORT=8080
EXPOSE 8080
CMD ["java", "-jar", "server.jar"]
