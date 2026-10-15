# 服务器已缓存 JDK 17 镜像，且 Docker Hub 拉取 JDK 21 镜像不稳定，因此统一使用 JDK 17（Spring Boot 3.2 支持）
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
# 限制构建时 Maven 内存，避免在小内存服务器上构建挤占其他服务
ENV MAVEN_OPTS="-Xmx384m -XX:+UseSerialGC"
# 国内网络使用阿里云 Maven 镜像，避免 Maven Central 下载超时
COPY settings.xml /root/.m2/settings.xml
COPY pom.xml .
# BuildKit 缓存挂载：Maven 本地仓库在多次构建之间复用，pom 变化时也只下载新增依赖
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -q dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2/repository mvn -B -q -DskipTests package
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /src/target/kb-backend-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
