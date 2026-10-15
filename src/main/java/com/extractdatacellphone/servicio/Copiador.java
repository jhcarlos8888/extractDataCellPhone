package com.extractdatacellphone.servicio;

import com.extractdatacellphone.config.ExtractDataCellPhoneProperties;
import com.extractdatacellphone.model.ArchivoOrigen;
import com.extractdatacellphone.model.ResultadoCopia;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Copia los archivos clasificados hacia el destino.
 *
 * Tres decisiones tomadas a partir de una recuperacion real:
 *  - Se refleja la estructura del origen. Cualquier reordenamiento inventado
 *    complica despues comprobar que nada falto.
 *  - Es reanudable: si el destino ya tiene un archivo del mismo tamano se omite.
 *    Importante en MTP, donde una copia de 3 GB puede interrumpirse.
 *  - Solo se crean los directorios necesarios para un archivo real.
 *    Nunca carpetas vacias: en el caso real habia 163 y no aportaban nada.
 */
@Component
public class Copiador {

    private static final Logger LOG = LoggerFactory.getLogger(Copiador.class);
    private static final int AVANCE_CADA = 200;

    private final ExtractDataCellPhoneProperties propiedades;

    public Copiador(ExtractDataCellPhoneProperties propiedades) {
        this.propiedades = propiedades;
    }

    public List<ResultadoCopia> copiar(Path destino, List<ArchivoOrigen> archivos) {
        List<ResultadoCopia> resultados = new ArrayList<>(archivos.size());
        int procesados = 0;

        for (ArchivoOrigen archivo : archivos) {
            resultados.add(copiarUno(destino, archivo));
            procesados++;
            if (procesados % AVANCE_CADA == 0) {
                LOG.info("copiados {} de {}", procesados, archivos.size());
            }
        }
        return List.copyOf(resultados);
    }

    private ResultadoCopia copiarUno(Path destino, ArchivoOrigen archivo) {
        Path destinoFinal = archivo.destinoEn(destino);

        try {
            if (!propiedades.isSobreescribir() && yaEstaCompleto(archivo, destinoFinal)) {
                return ResultadoCopia.existente(archivo, destinoFinal);
            }

            Path padre = destinoFinal.getParent();
            if (padre != null) {
                Files.createDirectories(padre);
            }

            Files.copy(archivo.ruta(), destinoFinal, StandardCopyOption.REPLACE_EXISTING);

            if (archivo.ruta().toFile().lastModified() > 0) {
                boolean ok = destinoFinal.toFile()
                        .setLastModified(archivo.ruta().toFile().lastModified());
                if (!ok) {
                    LOG.debug("no se pudo conservar la fecha de {}", destinoFinal);
                }
            }

            return ResultadoCopia.ok(archivo, destinoFinal);

        } catch (IOException | RuntimeException e) {
            String detalle = e.getClass().getSimpleName() + ": " + e.getMessage();
            LOG.warn("fallo al copiar {} -> {}", archivo.ruta(), detalle);
            return ResultadoCopia.fallo(archivo, destinoFinal, detalle);
        }
    }

    /**
     * Reanudacion: considera completo un archivo que ya existe con el mismo tamano.
     * Es la comprobacion barata que permitio re-ejecutar la copia sin repetir gigas.
     */
    private static boolean yaEstaCompleto(ArchivoOrigen archivo, Path destinoFinal) {
        try {
            return Files.isRegularFile(destinoFinal)
                    && Files.size(destinoFinal) == archivo.tamano();
        } catch (IOException e) {
            return false;
        }
    }
}
