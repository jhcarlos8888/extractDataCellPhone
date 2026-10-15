package com.extractdatacellphone.servicio;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.Detector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.springframework.stereotype.Component;

/**
 * Detecta el tipo REAL de un archivo leyendo sus bytes magicos.
 *
 * Punto critico de diseno: NO se le pasa el nombre del archivo a Tika. Si se hiciera,
 * Tika usaria la extension como pista y classificaria mal los archivos sin extension.
 * En la recuperacion real aparecio un PDF de 67 MB llamado simplemente
 * "CATALOGO MAYOR TENIS- VALSU": sin esta deteccion por contenido se habria ignorado.
 *
 * Igualmente, archivos ".tmp", ".thumbnail" o ".bizprofile" resultaron ser JPEG y MP4
 * verdaderos. La extension nunca decide por si sola.
 */
@Component
public class DetectorContenido {

    private final Detector detector;

    public DetectorContenido() {
        this.detector = TikaConfig.getDefaultConfig().getDetector();
    }

    /**
     * @param archivo cuyo contenido se va a inspeccionar.
     * @return el tipo MIME detectado, por ejemplo "application/pdf" o "image/jpeg".
     * @throws IOException si el archivo no se puede leer. Se propaga a proposito:
     *                     un archivo ilegible no debe confundirse con basura, porque
     *                     eso escondria un fallo de lectura como si fuera una decision
     *                     acertada de descarte.
     */
    public String detectar(Path archivo) throws IOException {
        // Metadata vacio a proposito: sin "resource.name" Tika usa solo los bytes.
        Metadata metadata = new Metadata();
        try (BufferedInputStream entrada =
                     new BufferedInputStream(Files.newInputStream(archivo), 8192)) {
            MediaType tipo = detector.detect(entrada, metadata);
            return (tipo == null) ? "application/octet-stream" : tipo.toString();
        }
    }
}
