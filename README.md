# extractDataCellPhone

Herramienta de línea de comandos que recupera imágenes, videos, audio y documentos
desde un celular, USB, disco o cualquier carpeta montada, y los deja ordenados en un
destino. **No expone API ni abre puerto**: es un JAR que se ejecuta con parámetros.

Nació de una recuperación real sobre un Motorola Moto G86 Power montado por MTP,
donde se recuperaron 2.707 archivos (4,4 GB) de fotos, videos, notas de voz y
documentos de WhatsApp y WhatsApp Business.

---

## Compilar y probar

```bash
mvn package          # compila, ejecuta los tests y genera el JAR
mvn test             # solo los tests
```

El JAR queda en `target/extractDataCellPhone-1.0.0.jar` (~12 MB).

## Uso

```bash
java -jar target/extractDataCellPhone-1.0.0.jar \
  --origen="/run/user/1000/gvfs/mtp:host=MOTORZA.../Internal shared storage" \
  --destino="$HOME/Desktop/respaldo_celular" \
  --excluir=juanCarlosNotesObsidian
```

### Parámetros

| Parámetro | Obligatorio | Descripción |
|---|---|---|
| `--origen=<ruta>` | sí | Carpeta a recuperar. |
| `--destino=<ruta>` | sí | Carpeta de salida. Se crea si no existe. |
| `--excluir=a,b` | no | Nombres de carpetas a saltar en cualquier nivel. |
| `--carpetas=a,b` | no | Carpetas de usuario a incluir. Por defecto: `DCIM`, `Pictures`, `Movies`, `Documents`, `Download`, `Music`, `Recordings`, `Ringtones`... |
| `--mensajeria=paquete,..` | no | Paquetes de Android a incluir. Por defecto: WhatsApp, WhatsApp Business, Telegram, Messenger, Signal, Discord... |
| `--podar=true` | no | Borra del destino lo que la configuración no contempla. Protege `SOSPECHOSOS_PARCIALES` y el informe. |
| `--sobreescribir=true` | no | Re-copia aunque el destino ya tenga el mismo tamaño. |
| `--detectar-truncados=false` | no | No separa los archivos que parecen incompletos. |
| `--muestra-verificacion=N` | no | Archivos verificados por md5. `0` desactiva la muestra. |

### Códigos de salida

| Código | Significado |
|---|---|
| `0` | Recuperación completa y verificada. |
| `2` | Terminó, pero hay hallazgos que revisar (fallos de copia o discrepancias). |
| `1` | Error fatal: argumentos inválidos o origen inexistente. |

---

## Qué hace, paso a paso

1. **Barrido recursivo** del origen, limitado al alcance configurado y saltando las
   carpetas excluidas por nombre.
2. **Clasificación por contenido real** de cada archivo.
3. **Copia** reflejando la estructura del origen.
4. **Verificación** comparando el destino contra el origen.
5. **Informe** en consola y en `<destino>/informe_extraccion.txt`.
6. **Poda** (opcional, `--podar=true`) del destino, siempre después de copiar y verificar.

---

## Las decisiones de diseño y por qué existen

Cada regla responde a algo que ocurrió realmente. No son suposiciones.

### 1. El tipo de archivo se decide por sus bytes mágicos, nunca por la extensión

Durante la recuperación apareció un **PDF de 67 MB** llamado
`CATALOGO MAYOR TENIS- VALSU`, sin extensión. Un filtro por extensión lo habría
ignorado por completo.

Al revés, archivos con nombres de basura resultaron ser contenido de verdad:

| Archivo | Extensión sugiere | Resultado real |
|---|---|---|
| `GtIXAoYk5...=.tmp` | caché | `image/jpeg` |
| `da4b76d6....bizprofile` | metadata | `image/jpeg` |
| `status_fb37795b...` | basura | `image/jpeg` |

Por eso `DetectorContenido` usa Apache Tika **sin pasarle el nombre del archivo**:
si lo recibiera, Tika usaría la extensión como pista y classificaría mal justamente
los casos que importan.

### 2. La extensión solo manda para descartar

Algunos tipos nunca son contenido recuperable y se descartan directamente:
`.nomedia`, `.database_uuid`, `.bin`, `.log`, `.chck`, `.crypt14`, `.db`, etc.
Esa lista es configurable en `application.yml`.

### 3. Las descargas incompletas van aparte y etiquetadas

Los MP4 de WhatsApp que no se reproducían medían **múltiplos exactos de 65536 bytes**:
262144, 196608, 131072, 1114112. Eran fragmentos de descargas por trozos de 64 KiB.

Esos archivos van a `<destino>/SOSPECHOSOS_PARCIALES/...`, separados del contenido
bueno, para que se puedan reparar o descartar sin mezclarse. Hay un umbral de tamaño
(8 MB por defecto) para no apartar por error un video grande legítimo.

### 4. No se crean carpetas vacías

El teléfono tenía **163 carpetas vacías** que solo ensuciaron el resultado. El copiador
solo crea los directorios necesarios para un archivo real.

### 5. Las carpetas ocultas NO se excluyen

Parece obvio descartar lo que empieza por punto, pero en la práctica real `.Statuses`
de WhatsApp guardaba 46 imágenes verdaderas. Lo oculto se clasifica igual que lo demás:
por su contenido.

### 6. La copia es reanudable

En MTP una copia de 3 GB puede interrumpirse. Si el destino ya tiene un archivo del
mismo tamaño, se omite. Una segunda ejecución sobre el mismo destino termina en
segundos y solo completa lo que falte. Si un archivo quedó truncado en el destino,
su tamaño no coincidirá y se volverá a copiar.

### 7. La verificación se hace contra el ORIGEN

Esta es la decisión más importante.

El primer intento real devolvió **exit 0 en todos los pasos** mientras omitía
**2.075 archivos de WhatsApp Business**, porque solo se había apuntado a
`com.whatsapp` y no a `com.whatsapp.w4b`. Nada en la salida del proceso delataba la
omisión.

Por eso `Verificador` compara contra el origen, nunca contra la lista de lo que el
propio proceso cree haber copiado. Tres comprobaciones:

1. **Conteo** de archivos esperados vs presentes.
2. **Suma de bytes**, que detecta copias truncadas con nombre correcto.
3. **Checksum md5 de una muestra**, que detecta archivos del mismo tamaño pero corruptos.

Un fallo de lectura puntual no aborta el proceso: se registra y se continúa, porque
en MTP un archivo puede colgarse sin que el resto deba perderse.

### 8. Solo entra lo que le sirve a una persona

La primera versión recuperaba cualquier archivo real de cualquier parte del
almacenamiento. El resultado era técnicamente correcto e inútil: 943 archivos de
paquetes de juegos, tiendas y procesos del sistema, casi todos caché de descargas y
registros. Nadie necesita eso en un respaldo.

Por eso el barrido aplica dos filtros de **alcance** antes de mirar el contenido:

| Nivel | Regla |
|---|---|
| `DCIM/`, `Documents/`, `Download/`... | entra, si el nombre está en `carpetas-usuario` |
| `Android/`, `Android/media/` | entra, son contenedores |
| `Android/media/<paquete>/` | entra **solo** si el paquete está en `paquetes-mensajeria` |
| archivos sueltos en la raíz | se descartan (en la práctica son restos como `badbuff.bin`) |

Las extensiones también tienen dos listas, y la diferencia es el criterio:

- `extensiones-ignoradas`: se descartan **en todas partes** (`.tmp`, `.hash`, `.json`,
  `.db`, `.crypt14`...). Ningún usuario necesita esas basuras.
- `extensiones-ignoradas-en-apps`: se descartan **solo dentro de Android**
  (`.txt`, `.zip`, `.js`, `.html`...). Un `.txt` en `Documents` puede ser una nota
  tuya; dentro de una aplicación es un recurso interno.

### 9. Los tipos MIME que no empiezan por `image/`, `video/` ni `audio/`

La detección por bytes mágicos no basta si la lista de tipos aceptados es incompleta.
Al validar contra archivos **reales** del teléfono aparecieron dos huecos que no
habían cubierto los tests sintéticos:

| Tipo real que devuelve Tika | Qué es | Consecuencia del hueco |
|---|---|---|
| `application/ogg` | notas de voz `.opus` de WhatsApp | se perdían **todas** las notas de voz |
| `application/x-tika-ooxml` | `.docx` y `.pptx` reales | se perdían los documentos de Office |

Ninguno empieza por los prefijos habituales, así que una lista de prefijos los
descartaba en silencio. Por eso existe `mime-multimedia`, y por eso los tests usan la
cabecera real de un `.opus` de WhatsApp en vez de un archivo inventado.

### 10. La poda es lo único destructivo, y su regla es una sola

`--podar=true` borra del destino lo que la configuración no contempla. No decide por
extensión, tamaño ni apariencia: **borra un archivo únicamente si no está en el
conjunto de rutas que el escaneo del origen determinó que deben existir**. Si el
escaneo lo marcara como recuperable, se conserva aunque esté vacío o corrupto.

Va después de copiar y verificar, nunca antes: primero se garantiza que está todo lo
que debe, y solo después se retira lo que sobra. Dos protecciones son innegociables:

- `SOSPECHOSOS_PARCIALES/`, donde la herramienta manda lo incompleto y donde se
  recolocan fragmentos que ya no existen en el origen (borrarlos sería irreversible).
- `informe_extraccion.txt`, que es archivo generado por nosotros.

Una poda interrumpida no pierde datos: deja sobras, que se retiran al volver a
ejecutar. Por eso se reporta como hallazgo (`exit 2`) y no como error fatal.

---

## Limitaciones

- **No desencripta `.crypt14`** de WhatsApp. Contienen el historial de chats, pero
  necesitan la llave de desencriptado, que vive en una zona del sistema no accesible
  por MTP.
- **La detección de truncados es una heurística**: se basa en que el tamaño sea múltiplo
  exacto del bloque de descarga. Un video legítimo que por casualidad tenga ese tamaño
  se apartaría. Se puede desactivar con `--detectar-truncados=false`.
- **No repara** los archivos parciales: solo los separa y etiqueta.
- Con MTP la velocidad depende del dispositivo; no hay forma de acelerarlo desde aquí.

## Estructura del código

```
src/main/java/com/extractdatacellphone/
├── ExtractDataCellPhoneApplication.java      punto de entrada
├── cli/CliExtractDataCellPhone.java          parseo de parámetros y orquestación
├── config/ExtractDataCellPhoneProperties.java extensiones, MIME, exclusiones
├── model/                           ArchivoOrigen, Escaneo, Verificacion, Informe...
├── servicio/
│   ├── DetectorContenido.java       Tika por bytes mágicos
│   ├── DetectorTruncado.java        heurística de descargas incompletas
│   ├── Escaneador.java              barrido recursivo con alcance
│   ├── Copiador.java                copia espejo reanudable
│   ├── Podador.java                 retiro de lo que la configuración no contempla
│   └── Verificador.java             comprobación contra el origen
└── reporte/GeneradorInforme.java    consola y archivo
```

Los tests en `src/test/java` reproducen los casos reales: el PDF sin extensión, el
JPEG bautizado como `.tmp`, el MP4 de 262144 bytes, y la omisión silenciosa que la
verificación debe delatar.
