package com.extractdatacellphone.servicio;

import com.extractdatacellphone.config.ExtractDataCellPhoneProperties;
import com.extractdatacellphone.model.ArchivoOrigen;
import com.extractdatacellphone.model.Clasificacion;
import com.extractdatacellphone.model.Verificacion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Comprueba que el destino realmente contiene lo que debe contener.
 *
 * La verificacion no es decorativa: en la recuperacion real un primer intento devolvio
 * "exit 0" en todos los pasos pero omitia 2075 archivos de WhatsApp Business.
 * Solo comparar contra el origen los delato.
 *
 * Tres comprobaciones, ninguna de ellas opcional:
 *  1. Conteo de archivos de contenido vs archivos presentes en el destino.
 *  2. Suma de bytes: detecta copias truncadas que el conteo no alcanza a ver.
 *  3. Checksum md5 de una muestra, comparando origen contra destino.
 *
 * Se compara contra el ORIGEN, nunca contra la lista de lo que el propio proceso
 * cree haber copiado: de ahi sale la capacidad de detectar omisiones.
 */
@Component
public class Verificador {

    private static final Logger LOG = LoggerFactory.getLogger(Verificador.class);

    private final ExtractDataCellPhoneProperties propiedades;

    public Verificador(ExtractDataCellPhoneProperties propiedades) {
        this.propiedades = propiedades;
    }

    public Verificacion verificar(Path destino,
                                  List<ArchivoOrigen> contenido,
                                  List<ArchivoOrigen> parciales) {
        List<ArchivoOrigen> esperados = new ArrayList<>(contenido);
        esperados.addAll(parciales);

        long bytesEsperados = esperados.stream().mapToLong(ArchivoOrigen::tamano).sum();

        long presentes = 0;
        long bytesPresentes = 0;
        List<String> discrepancias = new ArrayList<>();

        for (ArchivoOrigen archivo : esperados) {
            Path destinoFinal = archivo.destinoEn(destino);
            try {
                if (Files.isRegularFile(destinoFinal)) {
                    presentes++;
                    bytesPresentes += Files.size(destinoFinal);
                } else {
                    discrepancias.add("AUSENTE EN DESTINO: " + archivo.rutaRelativa());
                }
            } catch (IOException e) {
                discrepancias.add("no se pudo medir " + destinoFinal + ": " + e.getMessage());
            }
        }

        if (presentes != esperados.size()) {
            discrepancias.add("conteo: esperados " + esperados.size()
                    + ", presentes " + presentes);
        }
        if (bytesPresentes != bytesEsperados) {
            discrepancias.add("bytes: esperados " + bytesEsperados
                    + ", presentes " + bytesPresentes);
        }

        int[] muestra = verificarMuestra(destino, esperados, discrepancias);

        return new Verificacion(esperados.size(), presentes, bytesEsperados, bytesPresentes,
                muestra[0], muestra[1], List.copyOf(discrepancias));
    }

    /** @return {muestraRevisada, muestraCoincidente} */
    private int[] verificarMuestra(Path destino,
                                   List<ArchivoOrigen> esperados,
                                   List<String> discrepancias) {
        int solicitada = propiedades.getMuestraVerificacion();
        if (solicitada <= 0 || esperados.isEmpty()) {
            return new int[] {0, 0};
        }

        List<ArchivoOrigen> barajado = new ArrayList<>(esperados);
        Collections.shuffle(barajado, new Random());

        int revisada = 0;
        int coincidente = 0;
        int limite = Math.min(solicitada, barajado.size());

        for (int i = 0; i < limite; i++) {
            ArchivoOrigen archivo = barajado.get(i);
            Path destinoFinal = archivo.destinoEn(destino);
            try {
                String origenMd5 = md5(archivo.ruta());
                String destinoMd5 = md5(destinoFinal);
                revisada++;
                if (origenMd5.equals(destinoMd5)) {
                    coincidente++;
                } else {
                    discrepancias.add("CHECKSUM DISTINTO: " + archivo.rutaRelativa()
                            + " (origen " + origenMd5 + ", destino " + destinoMd5 + ")");
                }
            } catch (IOException e) {
                discrepancias.add("no se pudo verificar " + archivo.rutaRelativa()
                        + ": " + e.getMessage());
            }
        }

        LOG.info("muestra md5: {}/{} coinciden", coincidente, revisada);
        return new int[] {revisada, coincidente};
    }

    private static String md5(Path archivo) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            try (InputStream in = Files.newInputStream(archivo)) {
                byte[] buffer = new byte[8192];
                int leidos;
                while ((leidos = in.read(buffer)) > 0) {
                    digest.update(buffer, 0, leidos);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 no disponible", e);
        }
    }

    /** Filtra los archivos que deben copiarse realmente. */
    public static List<ArchivoOrigen> deClase(List<ArchivoOrigen> todos, Clasificacion c) {
        return todos.stream().filter(a -> a.clasificacion() == c).toList();
    }
}
