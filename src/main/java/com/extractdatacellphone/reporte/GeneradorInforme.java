package com.extractdatacellphone.reporte;

import com.extractdatacellphone.model.Informe;
import com.extractdatacellphone.model.Verificacion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Formatea el informe para consola y lo deja escrito junto al destino.
 * Se deja archivo porque una recuperacion suele revisarse dias despues.
 */
@Component
public class GeneradorInforme {

    private static final int MAX_DETALLE = 40;

    public String formatear(Informe i) {
        Verificacion v = i.verificacion();
        StringBuilder sb = new StringBuilder();
        String linea = "=".repeat(66);

        sb.append(linea).append('\n');
        sb.append("  INFORME DE EXTRACCION DE ARCHIVOS\n");
        sb.append(linea).append('\n');
        sb.append("Origen      : ").append(i.origen()).append('\n');
        sb.append("Destino     : ").append(i.destino()).append('\n');
        sb.append("Duracion    : ").append(formatearDuracion(i.duracionMs())).append('\n');

        sb.append('\n').append("--- Barrido ---\n");
        sb.append(String.format("Archivos escaneados : %d%n", i.totalEscaneados()));
        sb.append(String.format("  recuperables      : %d%n", i.contenido() + i.parciales()));
        sb.append(String.format("    contenido       : %d%n", i.contenido()));
        sb.append(String.format("    parciales       : %d%n", i.parciales()));
        sb.append(String.format("  descartados       : %d%n", i.ignorados()));
        sb.append(String.format("    indeterminados  : %d%n", i.indeterminados()));

        sb.append('\n').append("--- Copia ---\n");
        sb.append(String.format("Copiados            : %d%n", i.copiados()));
        sb.append(String.format("Ya existian         : %d%n", i.yaExistian()));
        sb.append(String.format("Fallos              : %d%n", i.fallos()));
        sb.append(String.format("Bytes copiados      : %s%n", humanizar(i.bytesCopiados())));

        sb.append('\n').append("--- Verificacion ---\n");
        sb.append(String.format("Archivos esperados  : %d%n", v.archivosEsperados()));
        sb.append(String.format("Archivos presentes  : %d%n", v.archivosPresentes()));
        sb.append(String.format("Bytes esperados     : %s%n", humanizar(v.bytesEsperados())));
        sb.append(String.format("Bytes presentes     : %s%n", humanizar(v.bytesPresentes())));
        sb.append(String.format("Muestra md5         : %d/%d coinciden%n",
                v.muestraCoincidente(), v.muestraRevisada()));

        sb.append('\n').append("--- Poda ---\n");
        if (i.poda().pudo()) {
            sb.append(String.format("Archivos podados     : %d%n", i.poda().archivosBorrados()));
            sb.append(String.format("Carpetas eliminadas : %d%n", i.poda().carpetasBorradas()));
            sb.append(String.format("Bytes liberados     : %s%n",
                    humanizar(i.poda().bytesBorrados())));
            // Quien borra deja constancia. Una poda es la unica accion irreversible
            // de la herramienta, y sin este listado no hay forma de saber dias
            // despues que se fue.
            if (!i.poda().borrados().isEmpty()) {
                sb.append("Borrados:\n");
                int mostrados = 0;
                for (String b : i.poda().borrados()) {
                    if (mostrados++ >= MAX_DETALLE) {
                        sb.append(String.format("    ... y %d mas%n",
                                i.poda().borrados().size() - MAX_DETALLE));
                        break;
                    }
                    sb.append("    ").append(b).append('\n');
                }
            }
        } else {
            sb.append("NO SE REALIZO\n");
            sb.append("Motivo              : ").append(i.poda().motivoNoPoda()).append('\n');
        }

        if (!i.erroresEscaneo().isEmpty()) {
            sb.append('\n').append("--- Errores de lectura (").append(i.erroresEscaneo().size())
                    .append(") ---\n");
            agregarDetalle(sb, i.erroresEscaneo());
        }
        if (!i.erroresCopia().isEmpty()) {
            sb.append('\n').append("--- Errores de copia (").append(i.erroresCopia().size())
                    .append(") ---\n");
            agregarDetalle(sb, i.erroresCopia());
        }
        if (!v.discrepancias().isEmpty()) {
            sb.append('\n').append("--- Discrepancias (").append(v.discrepancias().size())
                    .append(") ---\n");
            agregarDetalle(sb, v.discrepancias());
        }

        sb.append('\n').append(linea).append('\n');
        sb.append(i.exitoso()
                ? "  RESULTADO: VERIFICADO CORRECTAMENTE\n"
                : "  RESULTADO: REVISAR, HAY HALLAZGOS\n");
        // Un informe que se revisa dias despues debe dejar claro si no se pudo podar:
        // si no, daria la sensacion de que el destino quedo conforme a la configuracion.
        if (!i.poda().pudo()) {
            sb.append("  AVISO: LA PODA NO SE REALIZO: ")
                    .append(i.poda().motivoNoPoda()).append('\n');
        }
        sb.append(linea).append('\n');
        return sb.toString();
    }

    /** Nombre del informe dentro del destino. Es archivo propio, jamas se poda. */
    public static final String NOMBRE = "informe_extraccion.txt";

    public Path guardar(Informe informe, Path destino) {
        Path archivo = destino.resolve(NOMBRE);
        try {
            if (Files.notExists(destino)) {
                Files.createDirectories(destino);
            }
            Files.writeString(archivo, formatear(informe), StandardCharsets.UTF_8);
            return archivo;
        } catch (IOException e) {
            throw new UncheckedIOException("no se pudo escribir el informe en " + archivo, e);
        }
    }

    private static void agregarDetalle(StringBuilder sb, List<String> elementos) {
        int limite = Math.min(elementos.size(), MAX_DETALLE);
        for (int i = 0; i < limite; i++) {
            sb.append("  - ").append(elementos.get(i)).append('\n');
        }
        if (elementos.size() > limite) {
            sb.append("  ... y ").append(elementos.size() - limite).append(" mas\n");
        }
    }

    private static String humanizar(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.1f MB", mb);
        }
        return String.format("%.2f GB", mb / 1024.0);
    }

    private static String formatearDuracion(long ms) {
        long segundos = ms / 1000;
        if (segundos < 60) {
            return segundos + " s";
        }
        return String.format("%d min %d s", segundos / 60, segundos % 60);
    }
}
