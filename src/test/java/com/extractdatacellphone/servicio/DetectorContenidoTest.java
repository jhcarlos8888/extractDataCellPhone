package com.extractdatacellphone.servicio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.extractdatacellphone.support.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifica la decision central del proyecto: el TIPO REAL manda sobre la extension.
 *
 * Los casos vienen de una recuperacion real:
 *  - un PDF de 67 MB que no tenia extension alguna y un filtro por extension habria ignorado;
 *  - archivos ".tmp" y ".thumbnail" que resultaron ser JPEG verdaderos.
 */
class DetectorContenidoTest {

    @TempDir
    Path temp;

    private DetectorContenido detector;

    @BeforeEach
    void setUp() {
        detector = new DetectorContenido();
    }

    @Test
    void detectaUnPdfQueNoTieneExtension() throws IOException {
        Path archivo = temp.resolve("CATALOGO MAYOR TENIS- VALSU");
        Files.write(archivo, Fixtures.pdf());

        assertEquals("application/pdf", detector.detectar(archivo),
                "un archivo sin extension debe identificarse por sus bytes");
    }

    @Test
    void noSeDejaEnganarPorUnaExtensionQueMiente() throws IOException {
        // Una imagen JPEG bautizada como ".tmp": la extension sugiere basura, el contenido no.
        Path archivo = temp.resolve("GtIXAoYk5sVfwK5CIa7LMgdz8IhWSAMN1ncaGXNncl4=.tmp");
        Files.write(archivo, Fixtures.jpeg());

        assertEquals("image/jpeg", detector.detectar(archivo),
                "la extension .tmp no debe hacer descartar un JPEG real");
    }

    @Test
    void detectaPngSinAyudaDeLaExtension() throws IOException {
        Path archivo = temp.resolve("f7df9e655b05a1d93c3d6106035d2817");
        Files.write(archivo, Fixtures.png());

        assertEquals("image/png", detector.detectar(archivo));
    }

    @Test
    void detectaMp4PorElSuBoxFtyp() throws IOException {
        Path archivo = temp.resolve("video_sin_extension");
        Files.write(archivo, Fixtures.mp4(64));

        assertEquals("video/mp4", detector.detectar(archivo));
    }

    @Test
    void bytesSinFirmaReconocibleNoSeVuelvenRecuperablesPorSuNombre() throws IOException {
        Path archivo = temp.resolve("documento.docx");
        Files.write(archivo, new byte[] {0x11, 0x22, 0x33, 0x44, 0x55});

        assertEquals("application/octet-stream", detector.detectar(archivo),
                "un nombre convincente no convierte datos ilegibles en un documento");
    }

    @Test
    void unArchivoIlegibleSeReportaComoFalloNoComoBasura() {
        // Si esto devolviera "application/octet-stream", el archivo se descartaria en
        // silencio con el motivo enganoso de "tipo no recuperable", y el usuario
        // nunca sabria que un archivo real no se pudo leer.
        Path inexistente = temp.resolve("no_llego_a_existir.jpg");

        assertThrows(IOException.class, () -> detector.detectar(inexistente),
                "un fallo de lectura debe propagarse para quedar registrado como error");
    }
}
