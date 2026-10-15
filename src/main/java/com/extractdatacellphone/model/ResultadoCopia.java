package com.extractdatacellphone.model;

import java.nio.file.Path;

/**
 * Resultado de intentar copiar un archivo.
 *
 * @param copiado              true si el archivo quedo completo en el destino.
 * @param yaExistia            true si se omite por reanudacion (mismo tamano ya presente).
 * @param error                mensaje de fallo, o null si no hubo ninguno.
 */
public record ResultadoCopia(
        ArchivoOrigen archivo,
        Path destino,
        boolean copiado,
        boolean yaExistia,
        String error) {

    public static ResultadoCopia ok(ArchivoOrigen a, Path destino) {
        return new ResultadoCopia(a, destino, true, false, null);
    }

    public static ResultadoCopia existente(ArchivoOrigen a, Path destino) {
        return new ResultadoCopia(a, destino, false, true, null);
    }

    public static ResultadoCopia fallo(ArchivoOrigen a, Path destino, String error) {
        return new ResultadoCopia(a, destino, false, false, error);
    }

    public boolean fallo() {
        return error != null;
    }
}
