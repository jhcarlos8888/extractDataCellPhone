package com.extractdatacellphone.servicio;

import com.extractdatacellphone.model.ArchivoOrigen;
import com.extractdatacellphone.model.Clasificacion;
import com.extractdatacellphone.model.Escaneo;
import com.extractdatacellphone.reporte.GeneradorInforme;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Deja el destino reflejando exactamente lo que dice la configuracion.
 *
 * Es la parte destructiva de la herramienta, asi que su regla es una sola y no
 * admite interpretacion: se borra un archivo unicamente si NO esta en el conjunto
 * de rutas que el escaneo del origen determino que deben existir. No se borra nada
 * por parecer basura, por Extension ni por tamano; si el escaneo lo marcara como
 * recuperable, se conserva aunque el archivo este vacio o corrompido.
 *
 * Dos protecciones son innegociables:
 *
 *   - la carpeta de parciales (SOSPECHOSOS_PARCIALES), porque es donde la propia
 *     herramienta manda lo incompleto y donde se recolocan fragmentos que ya no
 *     existen en el origen;
 *   - el informe de extraccion, que es archivo generado por nosotros.
 */
@Component
public class Podador {

    private static final Logger LOG = LoggerFactory.getLogger(Podador.class);

    private final Escaneador escaneador;

    public Podador(Escaneador escaneador) {
        this.escaneador = escaneador;
    }

    /**
     * @param archivosBorrados archivos eliminados del destino.
     * @param carpetasBorradas carpetas que quedaron vacias y se eliminaron.
     * @param bytesBorrados espacio liberado.
     * @param abstenido true si no se podo nada porque el escaneo no era fiable.
     * @param detalleBorrados rutas exactas eliminadas. Una poda borra, y quien
     *                        borra deja constancia de que y de cuanto.
     */
    public record ResumenPodar(int archivosBorrados, int carpetasBorradas, long bytesBorrados,
                               boolean abstenido, List<String> detalleBorrados) {
        public static final ResumenPodar VACIO =
                new ResumenPodar(0, 0, 0L, false, List.of());
    }

    /**
     * @param origen carpeta de la que salio el escaneo. Se usa para confirmar
     *               contra ella antes de borrar algo del destino.
     * @param destino carpeta de respaldo.
     * @param escaneo resultado del barrido del ORIGEN, ya clasificado.
     */
    public ResumenPodar podar(Path origen, Path destino, Escaneo escaneo) throws IOException {
        if (Files.notExists(destino)) {
            return ResumenPodar.VACIO;
        }

        // Si el escaneo tuvo errores, el conjunto de rutas esperado esta
        // INCOMPLETO: los archivos ilegibles quedaron fuera de el. Poder contra
        // ese conjunto borraria copias validas que ya estaban en el destino, y la
        // verificacion no lo delataria porque compara contra esa misma lista
        // incompleta. Ante la duda, no se borra absolutamente nada.
        if (!escaneo.errores().isEmpty()) {
            LOG.error("NO SE PODO: el escaneo del origen tiene {} error(es), asi que el "
                    + "conjunto de rutas esperado no es fiable. Vuelve a ejecutar cuando "
                    + "el origen responda bien.", escaneo.errores().size());
            escaneo.errores().forEach(e -> LOG.error("    {}", e));
            return new ResumenPodar(0, 0, 0L, true, List.of());
        }

        // Un escaneo vacio sin errores es tan peligroso como uno fallido: casi
        // siempre significa que --origen apunta a la carpeta equivocada o que el
        // montaje no devolvio nada. Poder entonces borraria el respaldo entero.
        if (escaneo.recuperables() == 0L) {
            LOG.error("NO SE PODO: el escaneo no encontro ningun archivo recuperable en el "
                    + "origen. Revisa que --origen apunte a la carpeta correcta y que el "
                    + "dispositivo este respondiendo.");
            return new ResumenPodar(0, 0, 0L, true, List.of());
        }

        Set<Path> esperados = rutasEsperadas(destino, escaneo);
        List<Path> candidatos = new ArrayList<>();
        try (var flujo = Files.walk(destino)) {
            for (Path p : (Iterable<Path>) flujo::iterator) {
                if (Files.isRegularFile(p)) {
                    candidatos.add(p);
                }
            }
        }

        int borrados = 0;
        long bytes = 0L;
        int salvados = 0;
        List<String> detalle = new ArrayList<>();
        for (Path p : candidatos) {
            Path normal = p.toAbsolutePath().normalize();
            if (protegido(destino, normal) || esperados.contains(normal)) {
                continue;
            }

            // Antes de borrar se confirma contra el ORIGEN. El barrido puede omitir
            // un archivo sin reportar ningun error, cosa habitual en MTP, y fiarse
            // solo de el borraria una copia cuyo original sigue estando ahi. El
            // escaneo dice que NO debe existir; el origen dice que SI: gana el origen.
            Path original = origen.resolve(destino.relativize(normal));
            if (Files.isRegularFile(original) && esRecuperable(escaneador.reclasificar(origen, original))) {
                salvados++;
                LOG.warn("salvado de la poda: {} sigue en el origen como contenido recuperable "
                        + "y el barrido no lo vio", destino.relativize(normal));
                continue;
            }

            try {
                long tamano = 0L;
                try {
                    tamano = Files.size(normal);
                } catch (IOException e) {
                    // Si no se puede medir el tamano, aun asi se borra: lo que no
                    // se puede medir no debe salvar a un archivo podable.
                    LOG.debug("no se pudo leer el tamano de {} -> {}", normal, e.getMessage());
                }
                Files.delete(normal);
                // Se suma solo tras un borrado exitoso, para no reportar liberado
                // espacio que sigue ocupado.
                bytes += tamano;
                borrados++;
                detalle.add(destino.relativize(normal) + " (" + tamano + " bytes)");
                LOG.info("podado: {} ({} bytes)", destino.relativize(normal), tamano);
            } catch (IOException e) {
                // Un fallo puntual no debe abortar el resto de la poda.
                LOG.warn("no se pudo podar {} -> {}", normal, e.getMessage());
            }
        }
        if (salvados > 0) {
            LOG.warn("{} archivo(s) salvados de la poda porque siguen en el origen; el barrido "
                    + "no los vio. Vuelve a ejecutar para incorporarlos.", salvados);
        }

        int carpetas = podarDirectoriosVacios(destino);
        return new ResumenPodar(borrados, carpetas, bytes, false, List.copyOf(detalle));
    }

    /** true si el archivo si deberia estar en el destino. */
    private static boolean esRecuperable(ArchivoOrigen a) {
        return a.clasificacion() == Clasificacion.CONTENIDO
                || a.clasificacion() == Clasificacion.CONTENIDO_PARCIAL
                || a.clasificacion() == Clasificacion.INDETERMINADO;
    }

    /**
     * Rutas exactas que deben existir en el destino segun el escaneo del origen.
     *
     * Incluye lo indeterminado: no se copia, pero si se protege de la poda. Un
     * archivo que en el origen quedo vacio o ilegible puede tener en el destino
     * una copia que si era buena, y borrarla por una degradacion silenciosa del
     * origen seria perder datos que aun existian.
     */
    private Set<Path> rutasEsperadas(Path destino, Escaneo escaneo) {
        Set<Path> esperados = new HashSet<>();
        for (ArchivoOrigen archivo : escaneo.archivos()) {
            if (esRecuperable(archivo)) {
                esperados.add(archivo.destinoEn(destino).toAbsolutePath().normalize());
            }
        }
        return esperados;
    }

    /** true si el archivo pertenece a la propia herramienta y no debe tocarse. */
    private boolean protegido(Path destino, Path archivo) {
        Path relativa = destino.relativize(archivo);
        if (relativa.getNameCount() > 0
                && ArchivoOrigen.CARPETA_PARCIALES.equals(relativa.getName(0).toString())) {
            return true;
        }
        return relativa.getNameCount() == 1
                && GeneradorInforme.NOMBRE.equals(relativa.getName(0).toString());
    }

    /** Elimina las carpetas que quedaron vacias, de mas profunda a superficial. */
    private int podarDirectoriosVacios(Path destino) throws IOException {
        List<Path> carpetas = new ArrayList<>();
        try (var flujo = Files.walk(destino)) {
            for (Path p : (Iterable<Path>) flujo::iterator) {
                if (Files.isDirectory(p)) {
                    carpetas.add(p);
                }
            }
        }
        // De mas profundas a menos, para que una carpeta madre solo se elimine
        // cuando sus hijas ya lo estan.
        Collections.reverse(carpetas);

        int quitadas = 0;
        for (Path carpeta : carpetas) {
            if (carpeta.equals(destino)) {
                continue;
            }
            Path relativa = destino.relativize(carpeta);
            if (relativa.getNameCount() > 0
                    && ArchivoOrigen.CARPETA_PARCIALES.equals(relativa.getName(0).toString())) {
                continue;
            }
            try (DirectoryStream<Path> contenido = Files.newDirectoryStream(carpeta)) {
                if (!contenido.iterator().hasNext()) {
                    Files.delete(carpeta);
                    quitadas++;
                }
            }
        }
        return quitadas;
    }
}
