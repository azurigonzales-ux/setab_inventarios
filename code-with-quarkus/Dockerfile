# Etapa 1: Construcción con Maven y Java 21
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY . .
# Compila el proyecto en modo producción
RUN mvn clean package -Dquarkus.profile=prod -DskipTests

# Etapa 2: Entorno de ejecución ligero para la nube
FROM eclipse-temurin:21-jre
WORKDIR /app
# Copia la app compilada de la etapa anterior
COPY --from=build /app/target/quarkus-app/ /app/

# Exponemos el puerto de la API
EXPOSE 8080

# Comando para encender el servidor
CMD ["java", "-jar", "quarkus-run.jar"]