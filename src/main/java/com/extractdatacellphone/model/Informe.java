package com.extractdatacellphone.model;

import java.nio.file.Path;
import java.util.List;

/**
 * Resumen completo de una ejecucion, util tanto para consola como para archivo.
 *
 * @param erroresEscaneo fallos de lectura durante el barrido (no abortan el proceso).
 * @param erroresCopia   mensajes de fallo de cada archivo que no se pudo copiar.
 */
public record Informe(
        Path origen,
        Path destino,
        long totalEscaneados,
        long contenido,
        long parciales,
        long ignorados,
        long indeterminados,
        long copiados,
        long yaExistian,
        long fallos,
        long bytesCopiados,
        List<String> erroresEscaneo,
        List<String> erroresCopia,
        Verificacion verificacion,
        Poda poda,
        long duracionMs) {

    /**
     * Resultado de la poda opcional.
     *
     * El motivo es la parte importante: si no es null, es que no se podo y hay que
     * decirlo. Sin esto el informe podia quedarse con "VERIFICADO CORRECTAMENTE"
     * en una ejecucion donde no se borro absolutamente nada.
     *
     * @param motivoNoPoda null si se podo correctamente; si no, la razon.
     * @param borrados     rutas exactas eliminadas, con su tamano.
     */
    public record Poda(long archivosBorrados, int carpetasBorradas, long bytesBorrados,
                       String motivoNoPoda, List<String> borrados) {

        public static final Poda NO_SOLICITADA = new Poda(0, 0, 0L, null, List.of());

        public boolean pudo() {
            return motivoNoPoda == null;
        }
    }

    /**
     * true si todo cuadra: verificacion limpia, sin fallos de copia ni de lectura
     * y, si se pidio poda, con la poda efectivamente hecha.
     */
    public boolean exitoso() {
        return fallos == 0
                && verificacion.cuadra()
                && erroresEscaneo.isEmpty()
                && poda.pudo();
    }
}
