package com.extractdatacellphone.model;

import com.extractdatacellphone.model.Clasificacion;
import java.nio.file.Path;

/**
 * Un archivo encontrado en el origen, ya clasificado.
 *
 * @param rutaRelativa  ruta respecto al origen, con '/' como separador.
 *                      Se usa tal cual para reflejar la estructura en el destino.
 * @param tamano        tamano en bytes.
 * @param mime          tipo real detectado por bytes magicos (ej. "application/pdf").
 * @param clasificacion decision final.
 * @param motivo        razon legible de la decision, para el informe.
 */
public record ArchivoOrigen(
        Path ruta,
        String rutaRelativa,
        long tamano,
        String mime,
        Clasificacion clasificacion,
        String motivo) {

    /** Carpeta marcada para los archivos parciales, dentro del destino. */
    public static final String CARPETA_PARCIALES = "SOSPECHOSOS_PARCIALES";

    /** Devuelve la ruta de destino correspondiente a este archivo. */
    public Path destinoEn(Path destino) {
        Path base = (clasificacion == Clasificacion.CONTENIDO_PARCIAL)
                ? destino.resolve(CARPETA_PARCIALES)
                : destino;
        return base.resolve(rutaRelativa);
    }
}
