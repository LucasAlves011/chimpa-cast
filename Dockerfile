# Usamos a imagem base Java 25 Alpine
FROM amazoncorretto:25-alpine-jdk

WORKDIR /app

# Copia o JAR gerado pelo Maven (dentro da pasta target) para o container
COPY target/*.jar app.jar

# Expõe a porta interna da aplicação
EXPOSE 8080

# Comando para iniciar
ENTRYPOINT ["java", "-jar", "app.jar"]
