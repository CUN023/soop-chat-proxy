# soopapi 라이브러리 요구 사항: Java 25 이상
FROM eclipse-temurin:25-jdk

WORKDIR /app

# 주의: apt로 maven을 설치하면 의존성으로 다른 버전의 JDK가 같이 깔려서
# Java 25 컴파일과 충돌할 수 있습니다. 그래서 Maven 바이너리를 직접 받습니다.
ARG MAVEN_VERSION=3.9.9
RUN apt-get update && apt-get install -y curl && \
    curl -fsSL https://dlcdn.apache.org/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz -o /tmp/maven.tar.gz && \
    tar -xzf /tmp/maven.tar.gz -C /opt && \
    ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/bin/mvn && \
    rm /tmp/maven.tar.gz && \
    rm -rf /var/lib/apt/lists/*

COPY pom.xml .
COPY src ./src

# -q(조용히) 옵션을 빼서, 실패하면 실제 에러가 로그에 그대로 보이게 합니다.
RUN mvn -DskipTests package

ENV PORT=8080
EXPOSE 8080

CMD ["sh", "-c", "java -jar target/app.jar"]
