FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# 国内网络使用阿里云 Maven 镜像，避免 Maven Central 下载超时
COPY settings.xml /root/.m2/settings.xml
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/kb-backend-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
