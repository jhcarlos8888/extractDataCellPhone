package com.extractdatacellphone.servicio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.extractdatacellphone.config.ExtractDataCellPhoneProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Los MP4 de WhatsApp que no se reproducian median multiplos exactos de 65536 bytes:
 * 262144, 196608, 131072, 1114112. Ese es el patron que esta regla busca.
 */
class DetectorTruncadoTest {

    private DetectorTruncado detector;
    private ExtractDataCellPhoneProperties propiedades;

    @BeforeEach
    void setUp() {
        propiedades = new ExtractDataCellPhoneProperties();
        detector = new DetectorTruncado(propiedades);
    }

    @Test
    void marcaLosTamanosObservadosEnLaRecuperacionReal() {
        long[] observados = {262144L, 196608L, 131072L, 1114112L, 65536L};
        for (long tamano : observados) {
            assertTrue(detector.esTruncado(tamano),
                    tamano + " es multiplo de 65536 y debe marcarse como parcial");
        }
    }

    @Test
    void noMarcaUnArchivoDeTamanoNormal() {
        assertFalse(detector.esTruncado(10208L), "JPEG de 10208 bytes: contenido completo");
        assertFalse(detector.esTruncado(67397854L), "PDF de 67 MB: contenido completo");
        assertFalse(detector.esTruncado(1048577L), "1 MiB + 1 no es multiplo del bloque");
    }

    @Test
    void noMarcaUnVideoGrandeAunqueSeaMultiploDelBloque() {
        // 16 MiB es multiplo de 65536 pero supera el umbral: se trata como completo
        // para no arriesgarnos a apartar un video legitimo.
        assertFalse(detector.esTruncado(16L * 1024 * 1024));
    }

    @Test
    void respetaLaDesactivacionPorConfiguracion() {
        propiedades.setDetectarTruncados(false);
        assertFalse(detector.esTruncado(262144L));
    }

    @Test
    void toleraValoresDegenerados() {
        assertFalse(detector.esTruncado(0L), "un archivo vacio no es un fragmento valido");
        assertFalse(detector.esTruncado(-5L));

        propiedades.setBloqueTruncado(0);
        assertFalse(detector.esTruncado(262144L), "bloque 0 no debe provocar division por cero");
    }
}
