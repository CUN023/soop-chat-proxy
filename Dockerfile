# soopapi 라이브러리 요구 사항: Java 25 이상
FROM eclipse-temurin:25-jdk

WORKDIR /app

# Maven 설치 (빌드용)
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
COPY src ./src

RUN mvn -q -DskipTests package

ENV PORT=8080
EXPOSE 8080

CMD ["sh", "-c", "java -jar target/app.jar"]
