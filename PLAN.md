# PLAN — extractDataCellPhone

## Objetivo
JAR ejecutable (Spring Boot, CLI) que recupera archivos de un origen (celular MTP,
carpeta, USB, disco) hacia un destino, aplicando TODO lo aprendido en la sesión real.
No expone API ni servidor web.

## No está en alcance
- No expone endpoints REST ni puerto de red.
- No escribe ni modifica el origen (solo lectura).
- No desencripta `.crypt14` (requiere la llave de WhatsApp).
- Sin interfaz gráfica.

## Lecciones reales codificadas (por qué cada parte existe)
1. **Detectar por bytes mágicos, NO por extensión.** Encontramos un PDF de 67 MB
   llamado `CATALOGO MAYOR TENIS- VALSU` sin extensión: un filtro por extensión lo ignora.
   → `DetectorContenido` con Apache Tika sin metadata de nombre.
2. **La extensión miente en los dos sentidos.** `.tmp`, `.thumbnail`, `.bizprofile` y
   archivos sin nombre contienen JPEG/MP4 reales. → el contenido manda, la extensión es secundaria.
3. **Carpetas vacías ensucian el resultado.** El celular tenía 163 carpetas vacías.
   → no se crean directorios vacíos.
4. **Los fragmentos truncados no se reproducen.** Los MP4 de WhatsApp medían múltiplos
   exactos de 65536 (262144, 196608, 131072). → van a una carpeta etiquetada aparte.
5. **Excluir carpetas por nombre** (ej. `juanCarlosNotesObsidian`).
6. **Reanudable:** no re-copia archivos que ya existen con el mismo tamaño.
7. **Verificación obligatoria:** conteos, suma de bytes y checksum md5 de una muestra.
   Esto cazó 2075 archivos de WhatsApp Business que mi primera versión no copiaba.
8. **Registro de errores** en archivo, sin abortar todo por un archivo.

## Archivos afectados (creación completa)
```
pom.xml
src/main/resources/application.yml
src/main/java/com/extractdatacellphone/
  ExtractDataCellPhoneApplication.java          punto de entrada, código de salida
  cli/CliExtractDataCellPhone.java              parseo de --origen --destino --excluir ...
  config/ExtractDataCellPhoneProperties.java    extensiones, MIME, excludes
  model/Clasificacion.java             enum de estados
  model/ArchivoOrigen.java             archivo escaneado
  model/ResultadoCopia.java            resultado por archivo
  model/Informe.java                   resumen + verificación
  servicio/DetectorContenido.java      Tika, bytes mágicos
  servicio/DetectorTruncado.java       heurística múltiplo de 65536
  servicio/Escaneador.java             barrido recursivo con excludes
  servicio/Copiador.java               copia espejo, reanudable, sin vacías
  servicio/Verificador.java            conteos, bytes, md5 muestra
  reporte/GeneradorInforme.java        consola + archivo
src/test/java/com/extractdatacellphone/...
```

## Pasos
1. `pom.xml` + estructura → `mvn -q compile`
2. Modelo + propiedades → compila
3. DetectorContenido (Tika) + DetectorTruncado → unit test con bytes reales
4. Escaneador con excludes → unit test en @TempDir
5. Copiador espejo reanudable → unit test
6. Verificador + informe → unit test
7. CLI + empaquetado → `mvn package` genera el JAR
8. Prueba end-to-end real → ejecutar el JAR

## Criterios de aceptación
- `mvn -q package` termina con BUILD SUCCESS y genera el JAR ejecutable.
- Los tests unitarios pasan (sin omitir ninguno).
- Ejecutado contra una carpeta de prueba: detecta un PDF sin extensión, clasifica
  un MP4 truncado a la carpeta separada, no crea carpetas vacías, y el informe cuadra.
- `java -jar ... --origen=<carpeta> --destino=<carpeta>` funciona sin puerto abierto.

## Riesgos
- MTP lento/inestable → timeouts por archivo y reanudación.
- Heurística de truncado puede dar falso positivo en un video legítimo que sea múltiplo
  de 65536 → configurable y documentado.
