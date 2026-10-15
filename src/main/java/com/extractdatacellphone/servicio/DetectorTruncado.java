package com.extractdatacellphone.servicio;

import com.extractdatacellphone.config.ExtractDataCellPhoneProperties;
import org.springframework.stereotype.Component;

/**
 * Detecta archivos que parecen DESCARGAS POR TROZOS incompletas.
 *
 * Motivo real: al recuperar WhatsApp aparecieron MP4 que no se reproducian. Al medirlos,
 * todos eran multiplos exactos de 65536 bytes (262144, 196608, 131072, 1114112):
 * el cliente descarga el video en bloques de 64 KiB y esos fragmentos quedaron a medias.
 *
 * Se aplica un limite de tamano superior para no marcar por error un video grande
 * legitimo que por casualidad sea multiplo del bloque. La regla es configurable
 * y puede desactivarse por completo con "detectar-truncados=false".
 */
@Component
public class DetectorTruncado {

    private final ExtractDataCellPhoneProperties propiedades;

    public DetectorTruncado(ExtractDataCellPhoneProperties propiedades) {
        this.propiedades = propiedades;
    }

    /**
     * @param tamano tamano en bytes del archivo.
     * @return true si el archivo parece un fragmento incompleto.
     */
    public boolean esTruncado(long tamano) {
        if (!propiedades.isDetectarTruncados()) {
            return false;
        }
        long bloque = propiedades.getBloqueTruncado();
        if (bloque <= 0 || tamano <= 0) {
            return false;
        }
        if (tamano > propiedades.getMaxTamanoTruncado()) {
            return false;
        }
        return tamano % bloque == 0;
    }
}
