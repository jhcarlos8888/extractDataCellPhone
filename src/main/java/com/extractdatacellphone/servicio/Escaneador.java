package com.extractdatacellphone.servicio;

import com.extractdatacellphone.config.ExtractDataCellPhoneProperties;
import com.extractdatacellphone.model.ArchivoOrigen;
import com.extractdatacellphone.model.Clasificacion;
import com.extractdatacellphone.model.Escaneo;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Barrido recursivo del origen aplicando alcance y clasificacion.
 *
 * Se aplican dos filtros en este orden:
 *
 *  1) ALCANCE. Solo se desciende en las carpetas de usuario y, dentro de Android,
 *     en los paquetes de mensajeria. Sin este filtro el barrido se mete en caches
 *     de juegos, tiendas y procesos del sistema que solo contienen trozos de
 *     descargas y logs, y que no le sirven a nadie.
 *
 *  2) CONTENIDO. Para lo que si entra en alcance, el tipo se decide por los bytes
 *     magicos, nunca por la extension.
 *
 * Tres decisiones heredadas de una recuperacion real: no se crean carpetas vacias,
 * un fallo de lectura no aborta el resto del barrido, y las carpetas ocultas NO se
 * excluyen por su nombre porque guardan contenido valido (por ejemplo .Statuses).
 */
@Component
public class Escaneador {

    private static final Logger LOG = LoggerFactory.getLogger(Escaneador.class);
    private static final String ANDROID = "Android";

    private final DetectorContenido detectorContenido;
    private final DetectorTruncado detectorTruncado;
    private final ExtractDataCellPhoneProperties propiedades;

    public Escaneador(DetectorContenido detectorContenido,
                      DetectorTruncado detectorTruncado,
                      ExtractDataCellPhoneProperties propiedades) {
        this.detectorContenido = detectorContenido;
        this.detectorTruncado = detectorTruncado;
        this.propiedades = propiedades;
    }

    public Escaneo escanear(Path origen) {
        List<ArchivoOrigen> archivos = new ArrayList<>();
        List<String> errores = new ArrayList<>();
        long[] descartadosPorAlcance = {0L};

        try {
            Files.walkFileTree(origen, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (dir.equals(origen)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (propiedades.esCarpetaExcluida(nombreDe(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!carpetaPermitida(partes(origen, dir))) {
                        descartadosPorAlcance[0]++;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path archivo, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    archivos.add(clasificar(origen, archivo, attrs.size(), errores));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path archivo, IOException e) {
                    errores.add(archivo + " -> " + e.getClass().getSimpleName()
                            + ": " + e.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            errores.add(origen + " -> fallo irrecuperable del barrido: " + e.getMessage());
        } catch (RuntimeException e) {
            // Algunos proveedores (en particular los montajes MTP/FUSE) lanzan
            // UncheckedIOException u otras excepciones de runtime ante un directorio
            // ilegible. Se registra y se devuelve lo ya recorrido.
            errores.add(origen + " -> fallo inesperado del barrido: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        if (descartadosPorAlcance[0] > 0) {
            LOG.info("fuera de alcance: {} carpetas de aplicaciones que no son de mensajeria",
                    descartadosPorAlcance[0]);
        }
        return new Escaneo(List.copyOf(archivos), List.copyOf(errores));
    }

    /**
     * Decide si se debe descender en una carpeta segun su ruta respecto al origen.
     *
     * Estructura contemplada:
     *   DCIM/                                -> carpeta de usuario
     *   DCIM/Camera/                         -> dentro de una carpeta de usuario
     *   Android/                             -> contenedor
     *   Android/media/  | data/  | obb/      -> contenedor
     *   Android/media/com.whatsapp/          -> paquete: solo si es de mensajeria
     *   Android/media/com.whatsapp/WhatsApp/ -> dentro de un paquete permitido
     */
    private boolean carpetaPermitida(List<String> partes) {
        if (partes.isEmpty()) {
            return true;
        }
        String primera = partes.get(0);

        if (!ANDROID.equalsIgnoreCase(primera)) {
            return propiedades.esCarpetaUsuario(primera);
        }

        if (partes.size() <= 2) {
            return true;                       // Android  y  Android/{media,data,obb}
        }
        // Se comprueba el paquete aunque la ruta siga mas abajo. En el barrido no
        // hacia falta porque ya se filtro al descender, pero la poda reclasifica
        // archivos de uno en uno sin pasar por cada directorio, y con un "return
        // true" a partir de cuatro componentes todo el contenido de una app que no
        // es de mensajeria habria resultado protegible.
        return propiedades.esPaqueteMensajeria(partes.get(2));
    }

    /**
     * Clasifica un archivo concreto del origen, fuera del barrido.
     *
     * Lo usa la poda para confirmar contra el ORIGEN antes de borrar una copia.
     * Un montaje MTP puede omitir archivos sin reportar ningun error, y fiarse
     * unicamente del barrido borraria copias cuyo original sigue estando ahi.
     *
     * Si no se puede leer el archivo se devuelve INDETERMINADO y no IGNORADO:
     * una respuesta que no llego no debe autorizar a borrar nada.
     */
    public ArchivoOrigen reclasificar(Path origen, Path archivo) {
        String relativa = String.join("/", partes(origen, archivo));
        long tamano;
        try {
            tamano = Files.size(archivo);
        } catch (IOException e) {
            return new ArchivoOrigen(archivo, relativa, 0, null,
                    Clasificacion.INDETERMINADO, "no se pudo medir: " + e.getMessage());
        }
        List<String> errores = new ArrayList<>();
        ArchivoOrigen visto = clasificar(origen, archivo, tamano, errores);
        if (!errores.isEmpty()) {
            return new ArchivoOrigen(archivo, relativa, tamano, null,
                    Clasificacion.INDETERMINADO, "no se pudo leer: " + errores.get(0));
        }
        return visto;
    }

    private ArchivoOrigen clasificar(Path origen, Path archivo, long tamano,
                                     List<String> errores) {
        List<String> partes = partes(origen, archivo);
        String nombre = nombreDe(archivo);
        String relativa = partes.isEmpty() ? nombre : String.join("/", partes);

        // Archivos sueltos en la raiz del almacenamiento: no pertenecen a ninguna
        // carpeta de usuario, y en la practica son restos de sistemas (badbuff.bin).
        if (partes.size() <= 1) {
            return new ArchivoOrigen(archivo, relativa, tamano, null,
                    Clasificacion.IGNORADO, "archivo suelto en la raiz, fuera del alcance");
        }

        boolean dentroDeApps = ANDROID.equalsIgnoreCase(partes.get(0));

        // El alcance se aplica al ARCHIVO, no solo a las carpetas. Un archivo
        // suelto en Android/, Android/media/ o Android/data/ no pertenece a ningun
        // paquete de mensajeria; sin esta comprobacion entraria al destino y
        // quedaria inmune a la poda porque el escaneo lo marcara como contenido.
        List<String> partesPadre = partes.subList(0, partes.size() - 1);
        if (dentroDeApps && partesPadre.size() < 3) {
            return new ArchivoOrigen(archivo, relativa, tamano, null,
                    Clasificacion.IGNORADO,
                    "fuera de alcance: archivo suelto en un contenedor de Android");
        }
        if (!carpetaPermitida(partesPadre)) {
            return new ArchivoOrigen(archivo, relativa, tamano, null,
                    Clasificacion.IGNORADO, "fuera de alcance");
        }

        // 1) Nombres de basura sin extension, como "patch_1.33.9_304_27".
        //    Sin esta regla, una lista de extensiones nunca los alcanzaria a ver.
        if (propiedades.esNombreIgnorado(nombre)) {
            return new ArchivoOrigen(archivo, relativa, tamano, null,
                    Clasificacion.IGNORADO, "nombre de archivo basura: " + nombre);
        }

        // 2) Extension descartada en cualquier parte.
        if (propiedades.esExtensionIgnorada(nombre)) {
            return new ArchivoOrigen(archivo, relativa, tamano, null,
                    Clasificacion.IGNORADO, "extension descartada: ." + extensionDe(nombre));
        }

        // 3) Extension descartada solo dentro de aplicaciones. Un .txt en Documents
        //    puede ser una nota del usuario; en una app es un recurso interno.
        if (dentroDeApps && propiedades.esExtensionIgnoradaEnApps(nombre)) {
            return new ArchivoOrigen(archivo, relativa, tamano, null,
                    Clasificacion.IGNORADO,
                    "extension descartada dentro de aplicaciones: ." + extensionDe(nombre));
        }

        // 4) El tipo REAL decide. Aqui no manda la extension.
        String mime;
        try {
            mime = detectorContenido.detectar(archivo);
        } catch (IOException e) {
            String detalle = e.getClass().getSimpleName() + ": " + e.getMessage();
            errores.add(relativa + " -> no se pudo leer su contenido: " + detalle);
            return new ArchivoOrigen(archivo, relativa, tamano, null,
                    Clasificacion.IGNORADO, "no se pudo leer: " + detalle);
        }

        if (!propiedades.esMimeContenido(mime)) {
            // Un archivo cuyo contenido no se pudo determinar NO es lo mismo que
            // basura decidida por una regla. En MTP es habitual que un archivo
            // se degrade en silencio sin lanzar excepcion; tratarlo como basura
            // haria que la poda borrara del destino una copia que si era valida.
            if (esIndeterminado(tamano, mime)) {
                return new ArchivoOrigen(archivo, relativa, tamano, mime,
                        Clasificacion.INDETERMINADO,
                        "contenido indeterminado: " + mime + " (" + tamano + " bytes)");
            }
            return new ArchivoOrigen(archivo, relativa, tamano, mime,
                    Clasificacion.IGNORADO, "tipo no recuperable: " + mime);
        }

        // 5) Descargas por trozos incompletas van aparte, etiquetadas.
        if (detectorTruncado.esTruncado(tamano)) {
            return new ArchivoOrigen(archivo, relativa, tamano, mime,
                    Clasificacion.CONTENIDO_PARCIAL,
                    "multiplo de " + propiedades.getBloqueTruncado()
                            + " bytes: posible descarga incompleta");
        }

        return new ArchivoOrigen(archivo, relativa, tamano, mime,
                Clasificacion.CONTENIDO, "contenido recuperable");
    }

    /** @return las partes de la ruta respecto al origen; la ultima es el nombre del archivo. */
    private static List<String> partes(Path origen, Path ruta) {
        Path relativa = origen.relativize(ruta);
        List<String> salida = new ArrayList<>();
        for (Path elemento : relativa) {
            salida.add(elemento.toString());
        }
        return salida;
    }

    /**
     * true si el contenido del archivo no se pudo determinar.
     *
     * No es una decision de configuracion sino una respuesta que no llego: archivo
     * vacio, o bytes que Tika no identifica. En MTP un archivo puede degradarse en
     * silencio sin lanzar excepcion, y eso no debe confundirse con basura.
     */
    private static boolean esIndeterminado(long tamano, String mime) {
        if (tamano == 0L) {
            return true;
        }
        if (mime == null || mime.isBlank()) {
            return true;
        }
        String m = mime.toLowerCase(java.util.Locale.ROOT);
        return "application/octet-stream".equals(m) || "application/x-empty".equals(m);
    }

    private static String nombreDe(Path p) {
        Path nombre = p.getFileName();
        return (nombre == null) ? "" : nombre.toString();
    }

    private static String extensionDe(String nombre) {
        int punto = nombre.lastIndexOf('.');
        return (punto < 0) ? "" : nombre.substring(punto + 1);
    }
}
