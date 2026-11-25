FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY server/src server/src
RUN mkdir -p /out && javac -d /out $(find server/src -name '*.java')

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app && mkdir /data && chown app /data
WORKDIR /app
COPY --from=build /out /app/classes
COPY web /app/web
ENV PORT=8080 BIND=0.0.0.0 DATA_DIR=/data WEB_DIR=/app/web
VOLUME /data
USER app
EXPOSE 8080
CMD ["java", "-cp", "/app/classes", "taskmanager.Main"]
