package com.extractdatacellphone.servicio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.extractdatacellphone.config.ExtractDataCellPhoneProperties;
import com.extractdatacellphone.model.ArchivoOrigen;
import com.extractdatacellphone.model.Clasificacion;
import com.extractdatacellphone.model.Escaneo;
import com.extractdatacellphone.model.ResultadoCopia;
import com.extractdatacellphone.support.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CopiadorTest {

    @TempDir
    Path origen;

    @TempDir
    Path destino;

    private ExtractDataCellPhoneProperties propiedades;
    private Escaneador escaneador;
    private Copiador copiador;

    @BeforeEach
    void setUp() {
        propiedades = new ExtractDataCellPhoneProperties();
        DetectorTruncado truncado = new DetectorTruncado(propiedades);
        escaneador = new Escaneador(new DetectorContenido(), truncado, propiedades);
        copiador = new Copiador(propiedades);
    }

    @Test
    void reflejaLaEstructuraDelOrigen() throws IOException {
        escribir("DCIM/Camera/foto.jpg", Fixtures.jpeg());
        escribir("Documents/informe.pdf", Fixtures.pdf());

        copiarTodo();

        assertTrue(Files.isRegularFile(destino.resolve("DCIM/Camera/foto.jpg")));
        assertTrue(Files.isRegularFile(destino.resolve("Documents/informe.pdf")));
        assertEquals(Files.size(origen.resolve("DCIM/Camera/foto.jpg")),
                Files.size(destino.resolve("DCIM/Camera/foto.jpg")));
    }

    @Test
    void noCreaCarpetasVacias() throws IOException {
        // El telefono real tenia 163 carpetas vacias que solo ensuciaron el resultado.
        Files.createDirectories(origen.resolve("Recordings"));
        Files.createDirectories(origen.resolve("Pictures/Polish"));
        escribir("DCIM/foto.jpg", Fixtures.jpeg());

        copiarTodo();

        assertTrue(Files.exists(destino.resolve("DCIM/foto.jpg")));
        assertFalse(Files.exists(destino.resolve("Recordings")),
                "una carpeta sin archivos no debe reproducirse");
        assertFalse(Files.exists(destino.resolve("Pictures/Polish")));
    }

    @Test
    void mandaLosParcialesACarpetaEtiquetada() throws IOException {
        escribir("Download/video_incompleto.mp4", Fixtures.mp4Truncado());
        escribir("DCIM/foto.jpg", Fixtures.jpeg());

        copiarTodo();

        assertTrue(Files.isRegularFile(destino.resolve(
                ArchivoOrigen.CARPETA_PARCIALES + "/Download/video_incompleto.mp4")),
                "los fragmentos incompletos van aparte y etiquetados");
        assertTrue(Files.isRegularFile(destino.resolve("DCIM/foto.jpg")));
    }

    @Test
    void esReanudableSinVolverACopiarLoQueYaEsta() throws IOException {
        byte[] contenido = Fixtures.jpeg();
        escribir("DCIM/foto.jpg", contenido);

        List<ResultadoCopia> primera = copiarTodo();
        List<ResultadoCopia> segunda = copiarTodo();

        assertEquals(1, primera.stream().filter(ResultadoCopia::copiado).count());
        assertEquals(1, segunda.stream().filter(ResultadoCopia::yaExistia).count(),
                "la segunda pasada debe omitir lo ya copiado: asi se reanuda una copia interrumpida");
        assertEquals(0, segunda.stream().filter(ResultadoCopia::copiado).count());
    }

    @Test
    void siElDestinoEstaIncompletoSeVuelveACopiar() throws IOException {
        escribir("DCIM/foto.jpg", Fixtures.jpeg());
        copiarTodo();

        // Alguien trunco la copia: el tamano ya no coincide.
        Path copia = destino.resolve("DCIM/foto.jpg");
        Files.write(copia, new byte[] {1, 2, 3});

        List<ResultadoCopia> segunda = copiarTodo();

        assertEquals(1, segunda.stream().filter(ResultadoCopia::copiado).count(),
                "una copia truncada no cuenta como completa");
        assertEquals(Fixtures.jpeg().length, Files.size(copia));
    }

    @Test
    void unFalloNoDetieneElRestoDeLaCopia() throws IOException {
        escribir("DCIM/foto1.jpg", Fixtures.jpeg());
        escribir("DCIM/foto2.jpg", Fixtures.jpeg());
        escribir("Documents/sub/informe.pdf", Fixtures.pdf());

        // Bloqueo deterministico: un ARCHIVO donde el destino necesita un directorio.
        // Se eligio esto frente a chmod porque los permisos no se aplican al ejecutar
        // como root, y un test que puede no fallar no prueba nada.
        Path bloqueo = destino.resolve("Documents");
        Files.createDirectories(bloqueo.getParent());
        Files.write(bloqueo, new byte[] {1});

        List<ResultadoCopia> resultados = copiarTodo();

        assertEquals(3, resultados.size(), "todos los archivos deben procesarse");
        assertEquals(1, resultados.stream().filter(ResultadoCopia::fallo).count(),
                "solo debe fallar el archivo bloqueado");
        assertEquals(2, resultados.stream().filter(ResultadoCopia::copiado).count(),
                "los demas archivos deben copiarse pese al fallo");
        assertTrue(Files.isRegularFile(destino.resolve("DCIM/foto1.jpg")));
        assertTrue(Files.isRegularFile(destino.resolve("DCIM/foto2.jpg")));
    }

    // --- utilidades ------------------------------------------------------------

    private void escribir(String relativa, byte[] contenido) throws IOException {
        Path destinoArchivo = origen.resolve(relativa);
        Files.createDirectories(destinoArchivo.getParent());
        Files.write(destinoArchivo, contenido);
    }

    private List<ResultadoCopia> copiarTodo() throws IOException {
        Escaneo escaneo = escaneador.escanear(origen);
        List<ArchivoOrigen> aCopiar = escaneo.deClasificacion(Clasificacion.CONTENIDO).stream()
                .collect(Collectors.toList());
        aCopiar.addAll(escaneo.deClasificacion(Clasificacion.CONTENIDO_PARCIAL));
        return copiador.copiar(destino, aCopiar);
    }
}
