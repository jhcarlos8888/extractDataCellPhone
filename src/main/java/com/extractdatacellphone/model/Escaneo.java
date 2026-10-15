package com.extractdatacellphone.model;

import java.util.List;

/**
 * Resultado del barrido del origen.
 *
 * @param archivos todos los archivos encontrados, ya clasificados (incluye los ignorados,
 *                 para que el informe pueda explicar que se descarto y por que).
 * @param errores  fallos de lectura durante el barrido. No abortan el proceso:
 *                 en MTP un archivo puntual puede colgarse o fallar sin que el resto
 *                 deba perderse.
 */
public record Escaneo(List<ArchivoOrigen> archivos, List<String> errores) {

    public long contar(Clasificacion clasificacion) {
        return archivos.stream().filter(a -> a.clasificacion() == clasificacion).count();
    }

    /** Archivos que no recuperan, sea por decision de la configuracion o por no poder determinarlos. */
    public long descartados() {
        return contar(Clasificacion.IGNORADO) + contar(Clasificacion.INDETERMINADO);
    }

    /** Archivos que SI se recuperan: contenido y contenido parcial. */
    public long recuperables() {
        return archivos.stream()
                .filter(a -> a.clasificacion() == Clasificacion.CONTENIDO
                        || a.clasificacion() == Clasificacion.CONTENIDO_PARCIAL)
                .count();
    }

    public List<ArchivoOrigen> deClasificacion(Clasificacion clasificacion) {
        return archivos.stream()
                .filter(a -> a.clasificacion() == clasificacion)
                .toList();
    }
}
