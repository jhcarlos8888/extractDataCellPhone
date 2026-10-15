package com.extractdatacellphone.model;

import java.util.List;

/**
 * Resultado de la verificacion post-copia.
 *
 * La verificacion no es cosmética: en la recuperacion real un primer intento devolvio
 * "exit 0" en todos los pasos pero omitia 2075 archivos de WhatsApp Business.
 * Solo comparar contra el origen los delato.
 *
 * @param archivosEsperados  archivos de contenido que debian copiarse.
 * @param archivosPresentes  archivos realmente presentes en el destino.
 * @param bytesEsperados     suma de bytes esperada.
 * @param bytesPresentes     suma de bytes realmente copiada.
 * @param muestraRevisada    archivos comparados por checksum md5.
 * @param muestraCoincidente de ellos, cuantos coincidian byte a byte.
 * @param discrepancias      hallazgos concretos, vacio si todo cuadra.
 */
public record Verificacion(
        long archivosEsperados,
        long archivosPresentes,
        long bytesEsperados,
        long bytesPresentes,
        int muestraRevisada,
        int muestraCoincidente,
        List<String> discrepancias) {

    public boolean cuadra() {
        return discrepancias.isEmpty()
                && archivosEsperados == archivosPresentes
                && bytesEsperados == bytesPresentes;
    }
}
