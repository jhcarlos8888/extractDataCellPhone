package com.extractdatacellphone.cli;

import com.extractdatacellphone.config.ExtractDataCellPhoneProperties;
import com.extractdatacellphone.model.ArchivoOrigen;
import com.extractdatacellphone.model.Clasificacion;
import com.extractdatacellphone.model.Escaneo;
import com.extractdatacellphone.model.Informe;
import com.extractdatacellphone.model.ResultadoCopia;
import com.extractdatacellphone.model.Verificacion;
import com.extractdatacellphone.reporte.GeneradorInforme;
import com.extractdatacellphone.servicio.Copiador;
import com.extractdatacellphone.servicio.Escaneador;
import com.extractdatacellphone.servicio.Podador;
import com.extractdatacellphone.servicio.Verificador;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Punto de entrada de la recuperacion por linea de comandos.
 *
 * No se expone ningun servidor: la herramienta se usa asi
 *
 *   java -jar extractDataCellPhone-1.0.0.jar --origen=/ruta/origen --destino=/ruta/destino
 *
 * Codigos de salida:
 *   0  recuperacion completa y verificada
 *   1  error fatal (origen inexistente, argumentos invalidos, excepcion)
 *   2  termino pero hay hallazgos que revisar (fallos de copia o discrepancias)
 */
@Component
public class CliExtractDataCellPhone implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CliExtractDataCellPhone.class);

    private final Escaneador escaneador;
    private final Copiador copiador;
    private final Verificador verificador;
    private final Podador podador;
    private final GeneradorInforme generadorInforme;
    private final ExtractDataCellPhoneProperties propiedades;

    private boolean podar;

    @Value("${origen:}") private String origenParametro;
    @Value("${destino:}") private String destinoParametro;
    @Value("${excluir:}") private String excluirParametro;
    @Value("${sobreescribir:}") private String sobreescribirParametro;
    @Value("${detectar-truncados:}") private String truncadosParametro;
    @Value("${muestra-verificacion:}") private String muestraParametro;
    @Value("${carpetas:}") private String carpetasParametro;
    @Value("${mensajeria:}") private String mensajeriaParametro;
    @Value("${podar:}") private String podarParametro;

    public CliExtractDataCellPhone(Escaneador escaneador,
                          Copiador copiador,
                          Verificador verificador,
                          Podador podador,
                          GeneradorInforme generadorInforme,
                          ExtractDataCellPhoneProperties propiedades) {
        this.escaneador = escaneador;
        this.copiador = copiador;
        this.verificador = verificador;
        this.podador = podador;
        this.generadorInforme = generadorInforme;
        this.propiedades = propiedades;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path origen;
        Path destino;
        try {
            origen = validarRuta(origenParametro, "--origen", false);
            destino = validarRuta(destinoParametro, "--destino", true);
            // Las opciones se aplican antes: comprobarRelacion depende de --podar.
            aplicarOpciones();
            comprobarRelacion(origen, destino);
        } catch (IllegalArgumentException e) {
            LOG.error("argumentos invalidos: {}", e.getMessage());
            mostrarUso();
            salir(1);
            return;
        }

        long inicio = System.currentTimeMillis();
        LOG.info("origen  : {}", origen);
        LOG.info("destino : {}", destino);
        LOG.info("reglas  : {}", propiedades);

        // 1) Barrido y clasificacion por contenido real.
        LOG.info("1/4 Escaneando el origen...");
        Escaneo escaneo = escaneador.escanear(origen);
        List<ArchivoOrigen> contenido = escaneo.deClasificacion(Clasificacion.CONTENIDO);
        List<ArchivoOrigen> parciales = escaneo.deClasificacion(Clasificacion.CONTENIDO_PARCIAL);
        LOG.info("    {} archivos escaneados: {} contenido, {} parciales, {} descartados",
                escaneo.archivos().size(), contenido.size(), parciales.size(),
                escaneo.descartados());
        long indeterminados = escaneo.contar(Clasificacion.INDETERMINADO);
        if (indeterminados > 0) {
            LOG.warn("    {} indeterminados: el origen no dejo identificar su contenido "
                    + "(vacio o ilegible). Sus copias en destino quedan protegidas de la poda.",
                    indeterminados);
        }

        // 2) Copia reflejando la estructura, reanudable.
        LOG.info("2/4 Copiando...");
        List<ArchivoOrigen> aCopiar = new ArrayList<>(contenido);
        aCopiar.addAll(parciales);
        List<ResultadoCopia> resultados = copiador.copiar(destino, aCopiar);

        long copiados = resultados.stream().filter(ResultadoCopia::copiado).count();
        long existentes = resultados.stream().filter(ResultadoCopia::yaExistia).count();
        List<String> erroresCopia = resultados.stream()
                .filter(ResultadoCopia::fallo)
                .map(r -> r.archivo().rutaRelativa() + " -> " + r.error())
                .toList();
        long bytesCopiados = resultados.stream()
                .filter(ResultadoCopia::copiado)
                .mapToLong(r -> r.archivo().tamano())
                .sum();
        LOG.info("    {} copiados, {} ya existian, {} fallos", copiados, existentes,
                erroresCopia.size());

        // 3) Verificacion contra el origen, no contra lo que creemos haber copiado.
        LOG.info("3/4 Verificando contra el origen...");
        Verificacion verificacion = verificador.verificar(destino, contenido, parciales);

        // 4) Poda opcional. Va DESPUES de copiar y verificar: primero se garantiza
        //    que esta todo lo que debe, y solo despues se retira lo que sobra.
        boolean podaFallo = false;
        Informe.Poda podaInforme = Informe.Poda.NO_SOLICITADA;
        if (podar) {
            LOG.info("4/4 Podando el destino segun la configuracion...");
            try {
                Podador.ResumenPodar r = podador.podar(origen, destino, escaneo);
                if (r.abstenido()) {
                    // El Podador se abstiene cuando el escaneo no es fiable: el
                    // conjunto de rutas esperado podria estar incompleto. El motivo
                    // ya esta logueado por el propio Podador.
                    podaFallo = true;
                    podaInforme = new Informe.Poda(0, 0, 0L,
                            "el escaneo del origen no es fiable", List.of());
                } else {
                    podaInforme = new Informe.Poda(r.archivosBorrados(), r.carpetasBorradas(),
                            r.bytesBorrados(), null, r.detalleBorrados());
                    LOG.info("    {} archivos podados, {} carpetas vacias eliminadas, {} liberados",
                            r.archivosBorrados(), r.carpetasBorradas(), r.bytesBorrados());
                }
            } catch (IOException | RuntimeException e) {
                // Una poda interrumpida no pierde datos: deja sobras, que se
                // retiran al volver a ejecutar con --podar. Se captura tambien
                // RuntimeException porque Files.walk propaga UncheckedIOException.
                podaFallo = true;
                podaInforme = new Informe.Poda(0, 0, 0L, "poda interrumpida: " + e.getMessage(),
                        List.of());
                LOG.error("la poda se interumpio y quedaron sobras: {}", e.getMessage());
            }
        }

        // 4) Informe.
        Informe informe = new Informe(origen, destino, escaneo.archivos().size(),
                contenido.size(), parciales.size(), escaneo.descartados(), indeterminados,
                copiados, existentes, erroresCopia.size(), bytesCopiados,
                escaneo.errores(), erroresCopia, verificacion, podaInforme,
                System.currentTimeMillis() - inicio);

        System.out.println();
        System.out.println(generadorInforme.formatear(informe));
        try {
            Path archivoInforme = generadorInforme.guardar(informe, destino);
            LOG.info("Informe guardado en {}", archivoInforme);
        } catch (RuntimeException e) {
            LOG.warn("no se pudo guardar el informe: {}", e.getMessage());
        }

        salir(informe.exitoso() && !podaFallo ? 0 : 2);
    }

    // --- Validaciones y opciones ----------------------------------------------

    private Path validarRuta(String valor, String nombre, boolean crearSiNoExiste) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("falta la ruta " + nombre);
        }
        Path ruta = Path.of(valor.trim()).toAbsolutePath().normalize();
        if (!crearSiNoExiste && !Files.isDirectory(ruta)) {
            throw new IllegalArgumentException(nombre + " no es un directorio: " + ruta);
        }
        if (crearSiNoExiste) {
            try {
                Files.createDirectories(ruta);
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        nombre + " no se puede crear (" + e.getMessage() + "): " + ruta);
            }
        }
        return ruta;
    }

    /**
     * Evita las dos relaciones de contencion ingenuas.
     *
     * La primera (destino dentro del origen) generaria una copia infinita. La
     * segunda (origen dentro del destino) es mas peligrosa aun con --podar: el
     * escaneo del origen produciria rutas esperadas bajo el destino, y todo lo
     * demas que hubiera en el destino, incluido el propio origen, se borraria.
     */
    private void comprobarRelacion(Path origen, Path destino) {
        if (destino.startsWith(origen)) {
            throw new IllegalArgumentException(
                    "el destino esta dentro del origen; eso generaria una copia infinita");
        }
        // Con --podar esta relacion es destructiva: el escaneo produciria rutas
        // esperadas bajo el destino y todo lo demas, incluido el propio origen,
        // se borraria. Sin --podar es inocuo, porque la herramienta solo escribe.
        if (podar && origen.startsWith(destino)) {
            throw new IllegalArgumentException(
                    "el origen esta dentro del destino; con --podar la herramienta "
                    + "borraria el propio origen");
        }
    }

    private void aplicarOpciones() {
        if (existe(excluirParametro)) {
            propiedades.setExcluirCarpetas(lista(excluirParametro));
        }
        if (existe(carpetasParametro)) {
            propiedades.setCarpetasUsuario(lista(carpetasParametro));
        }
        if (existe(mensajeriaParametro)) {
            propiedades.setPaquetesMensajeria(lista(mensajeriaParametro));
        }
        if (existe(podarParametro)) {
            podar = Boolean.parseBoolean(podarParametro.trim());
        }
        if (existe(sobreescribirParametro)) {
            propiedades.setSobreescribir(Boolean.parseBoolean(sobreescribirParametro.trim()));
        }
        if (existe(truncadosParametro)) {
            propiedades.setDetectarTruncados(Boolean.parseBoolean(truncadosParametro.trim()));
        }
        if (existe(muestraParametro)) {
            try {
                propiedades.setMuestraVerificacion(Integer.parseInt(muestraParametro.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "--muestra-verificacion debe ser un numero: " + muestraParametro);
            }
        }
    }

    private static boolean existe(String valor) {
        return valor != null && !valor.isBlank();
    }

    private static List<String> lista(String valor) {
        return Arrays.stream(valor.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void mostrarUso() {
        System.err.println("""
                Uso:
                  java -jar extractDataCellPhone-1.0.0.jar --origen=<ruta> --destino=<ruta> [opciones]

                Obligatorio:
                  --origen=<ruta>    Carpeta a recuperar (celular montado, USB, disco...).
                  --destino=<ruta>   Carpeta donde quedara la recuperacion.

                Opcional:
                  --excluir=a,b              Carpetas adicionales a saltar (ej: juanCarlosNotesObsidian).
                  --carpetas=a,b             Carpetas de usuario a incluir. Por defecto: DCIM, Pictures,
                                             Movies, Documents, Download, Music, Recordings, Ringtones...
                  --mensajeria=paquete,..    Paquetes de Android a incluir. Por defecto: WhatsApp,
                                             WhatsApp Business, Telegram, Messenger, Signal, Discord...
                  --sobreescribir=true       Re-copia aunque el destino ya tenga el mismo tamano.
                  --detectar-truncados=false No separa los archivos que parecen incompletos.
                  --muestra-verificacion=N   Archivos verificados por md5 (0 desactiva).
                  --podar=true               Borra del destino lo que la configuracion no contempla.
                                             Protege SOSPECHOSOS_PARCIALES y el informe.

                Codigos de salida: 0 = verificado, 2 = hay hallazgos, 1 = error fatal.
                """);
    }

    private void salir(int codigo) {
        // System.exit dispara el shutdown hook de Spring, que cierra el contexto.
        System.exit(codigo);
    }
}
