package com.extractdatacellphone.servicio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.extractdatacellphone.config.ExtractDataCellPhoneProperties;
import com.extractdatacellphone.model.Escaneo;
import com.extractdatacellphone.reporte.GeneradorInforme;
import com.extractdatacellphone.support.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * La poda es la unica parte destructiva de la herramienta, asi que las pruebas
 * se centran en lo que NO debe borrar, que es donde un error cuesta datos.
 */
class PodadorTest {

    @TempDir
    Path origen;

    @TempDir
    Path destino;

    private ExtractDataCellPhoneProperties propiedades;
    private Escaneador escaneador;
    private Podador podador;

    @BeforeEach
    void setUp() {
        propiedades = new ExtractDataCellPhoneProperties();
        escaneador = new Escaneador(new DetectorContenido(),
                new DetectorTruncado(propiedades), propiedades);
        podador = new Podador(escaneador);
    }

    @Test
    void borraLoQueLaConfiguracionNoContempla() throws IOException {
        // En el origen solo hay material de usuario; en el destino habia ademas
        // restos de una poda anterior hecha con reglas mas permisivas.
        escribirEn(origen, "DCIM/foto.jpg", Fixtures.jpeg());

        escribirEn(destino, "DCIM/foto.jpg", Fixtures.jpeg());
        escribirEn(destino, "Android/media/com.instagram.android/foto.jpg", Fixtures.jpeg());
        escribirEn(destino, "Android/data/com.juego/cache.jpg", Fixtures.jpeg());
        escribirEn(destino, "Download/video.tmp", Fixtures.mp4(500));

        Podador.ResumenPodar resumen = podar();

        assertEquals(3, resumen.archivosBorrados());
        assertTrue(Files.isRegularFile(destino.resolve("DCIM/foto.jpg")), "lo valido se queda");
        assertFalse(Files.exists(destino.resolve("Android/media/com.instagram.android")));
        assertFalse(Files.exists(destino.resolve("Android/data/com.juego")));
        assertFalse(Files.exists(destino.resolve("Download/video.tmp")));
    }

    @Test
    void noBorraNadaCuandoTodoEncaja() throws IOException {
        escribirEn(origen, "DCIM/foto.jpg", Fixtures.jpeg());
        escribirEn(origen, "Documents/informe.pdf", Fixtures.pdf());

        escribirEn(destino, "DCIM/foto.jpg", Fixtures.jpeg());
        escribirEn(destino, "Documents/informe.pdf", Fixtures.pdf());

        Podador.ResumenPodar resumen = podar();

        assertEquals(0, resumen.archivosBorrados());
        assertEquals(0, resumen.carpetasBorradas());
        assertTrue(Files.isRegularFile(destino.resolve("DCIM/foto.jpg")));
        assertTrue(Files.isRegularFile(destino.resolve("Documents/informe.pdf")));
    }

    @Test
    void protegeLaCarpetaDeParcialesAunqueNoEsteEnElOrigen() throws IOException {
        // Es donde la herramienta manda lo incompleto. Ademas es donde se
        // recolocan fragmentos que ya no existen en el origen, por lo que
        // borrarlos seria irreversible.
        escribirEn(origen, "DCIM/foto.jpg", Fixtures.jpeg());

        escribirEn(destino, "DCIM/foto.jpg", Fixtures.jpeg());
        escribirEn(destino, "SOSPECHOSOS_PARCIALES/WhatsApp/fragmento.mp4",
                Fixtures.mp4Truncado());

        Podador.ResumenPodar resumen = podar();

        assertEquals(0, resumen.archivosBorrados());
        assertTrue(Files.isRegularFile(destino.resolve(
                "SOSPECHOSOS_PARCIALES/WhatsApp/fragmento.mp4")),
                "los parciales jamas se podan");
    }

    @Test
    void protegeElInformeDeRecuperacion() throws IOException {
        escribirEn(origen, "DCIM/foto.jpg", Fixtures.jpeg());

        escribirEn(destino, "DCIM/foto.jpg", Fixtures.jpeg());
        escribirEn(destino, GeneradorInforme.NOMBRE, "informe".getBytes());

        Podador.ResumenPodar resumen = podar();

        assertEquals(0, resumen.archivosBorrados());
        assertTrue(Files.isRegularFile(destino.resolve(GeneradorInforme.NOMBRE)),
                "el informe es archivo propio de la herramienta");
    }

    @Test
    void limpiaLasCarpetasQueQuedanVacias() throws IOException {
        escribirEn(origen, "DCIM/foto.jpg", Fixtures.jpeg());

        escribirEn(destino, "DCIM/foto.jpg", Fixtures.jpeg());
        escribirEn(destino, "Pictures/Polish/nested/imagen.png", Fixtures.png());

        Podador.ResumenPodar resumen = podar();

        assertEquals(1, resumen.archivosBorrados());
        assertEquals(3, resumen.carpetasBorradas(),
                "Pictures, Pictures/Polish y Pictures/Polish/nested");
        assertFalse(Files.exists(destino.resolve("Pictures")));
        assertTrue(Files.isRegularFile(destino.resolve("DCIM/foto.jpg")));
    }

    @Test
    void noCreaElDestinoNiFallaSiNoExiste() throws IOException {
        Path ausente = destino.resolve("que-no-existe");

        Podador.ResumenPodar resumen = podador.podar(origen, ausente, escaneador.escanear(origen));

        assertEquals(0, resumen.archivosBorrados());
        assertFalse(Files.exists(ausente), "la poda no crea carpetas");
    }

    @Test
    void seAbstieneDePodarCuandoElEscaneoTuvoErrores() throws IOException {
        // Es el defecto que mas danio hace: si el escaneo del origen falla en
        // MTP, el conjunto de rutas esperado queda incompleto. Poder contra el
        // borraria copias validas que ya estaban en el destino, y la verificacion
        // no lo delataria porque compara contra esa misma lista incompleta.
        escribirEn(origen, "DCIM/foto.jpg", Fixtures.jpeg());

        escribirEn(destino, "DCIM/foto.jpg", Fixtures.jpeg());
        escribirEn(destino, "Android/media/com.instagram.android/foto.jpg", Fixtures.jpeg());

        Escaneo escaneoFalloso = new Escaneo(List.of(),
                List.of("DCIM/otra.jpg -> IOException: lectura interrumpida"));

        Podador.ResumenPodar resumen = podador.podar(origen, destino, escaneoFalloso);

        assertTrue(resumen.abstenido(), "con un escaneo fallido no se podo nada");
        assertEquals(0, resumen.archivosBorrados());
        assertEquals(0, resumen.carpetasBorradas());
        assertTrue(Files.isRegularFile(destino.resolve(
                        "Android/media/com.instagram.android/foto.jpg")),
                "lo que entraria en el conjunto incompleto no se borra");
    }

    @Test
    void respetaLaExclusionDeCarpetasDelUsuario() throws IOException {
        propiedades.setExcluirCarpetas(java.util.List.of("juanCarlosNotesObsidian"));
        escribirEn(origen, "DCIM/foto.jpg", Fixtures.jpeg());

        escribirEn(destino, "DCIM/foto.jpg", Fixtures.jpeg());
        escribirEn(destino, "juanCarlosNotesObsidian/nota.md", "# privado".getBytes());

        Podador.ResumenPodar resumen = podar();

        assertEquals(1, resumen.archivosBorrados());
        assertFalse(Files.exists(destino.resolve("juanCarlosNotesObsidian")));
    }

    @Test
    void noPodaCuandoElEscaneoNoEncontroNadaRecuperable() throws IOException {
        // Es el caso del --origen equivocado: el barrido sale limpio pero vacio,
        // sin ningun error que delate nada. Sin esta salvaguarda la poda borraria
        // el respaldo entero y saldria con exit 0.
        escribirEn(destino, "DCIM/foto.jpg", Fixtures.jpeg());
        escribirEn(destino, "Documents/informe.pdf", Fixtures.pdf());

        Podador.ResumenPodar resumen = podar();

        assertTrue(resumen.abstenido(), "un escaneo vacio no autoriza a borrar nada");
        assertEquals(0, resumen.archivosBorrados());
        assertTrue(Files.isRegularFile(destino.resolve("DCIM/foto.jpg")));
        assertTrue(Files.isRegularFile(destino.resolve("Documents/informe.pdf")));
    }

    @Test
    void protegeLasCopiasDeArchivosDegradadosEnElOrigen() throws IOException {
        // Un archivo que en el origen quedo vacio no es basura: es una respuesta
        // que no llego. En el destino puede sobrevivir una copia que si era buena.
        escribirEn(origen, "DCIM/foto.jpg", new byte[0]);
        escribirEn(origen, "Documents/informe.pdf", Fixtures.pdf());

        escribirEn(destino, "DCIM/foto.jpg", Fixtures.jpeg());
        escribirEn(destino, "Documents/informe.pdf", Fixtures.pdf());

        Podador.ResumenPodar resumen = podar();

        assertEquals(0, resumen.archivosBorrados(),
                "una degradacion silenciosa del origen no debe costar la copia");
        assertTrue(Files.isRegularFile(destino.resolve("DCIM/foto.jpg")));
    }

    @Test
    void noBorraUnaCopiaCuyoOriginalSigueEnElOrigen() throws IOException {
        // Es el fallo que ocurrio en la recuperacion real: MTP omitio archivos sin
        // reportar ningun error, el barrido no los vio, y la poda borro copias cuyo
        // original seguia en el celular. El escaneo dijo que no; el origen dijo que
        // si. Gana el origen.
        escribirEn(origen, "DCIM/foto.jpg", Fixtures.jpeg());
        escribirEn(origen, "DCIM/olvidada.jpg", Fixtures.jpeg());

        escribirEn(destino, "DCIM/foto.jpg", Fixtures.jpeg());
        escribirEn(destino, "DCIM/olvidada.jpg", Fixtures.jpeg());

        Escaneo completo = escaneador.escanear(origen);
        Escaneo sinOlvidada = new Escaneo(
                completo.archivos().stream()
                        .filter(a -> !a.rutaRelativa().contains("olvidada"))
                        .toList(),
                List.of());

        Podador.ResumenPodar resumen = podador.podar(origen, destino, sinOlvidada);

        assertEquals(0, resumen.archivosBorrados());
        assertTrue(Files.isRegularFile(destino.resolve("DCIM/olvidada.jpg")),
                "si el original sigue ahi, el que mintio fue el barrido");
    }

    @Test
    void laRecomprobacionNoProtegeElContenidoDeOtrasApps() throws IOException {
        // La verificacion contra el origen no puede convertir en intocable el
        // contenido de una app que no es de mensajeria: el alcance lo decide la
        // configuracion, no lo que el celular tenga guardado.
        escribirEn(origen, "DCIM/foto.jpg", Fixtures.jpeg());
        escribirEn(origen, "Android/data/com.juego.particular/cache.jpg", Fixtures.jpeg());

        escribirEn(destino, "DCIM/foto.jpg", Fixtures.jpeg());
        escribirEn(destino, "Android/data/com.juego.particular/cache.jpg", Fixtures.jpeg());

        Podador.ResumenPodar resumen = podar();

        assertEquals(1, resumen.archivosBorrados());
        assertFalse(Files.exists(destino.resolve("Android/data/com.juego.particular")),
                "tenerlo en el celular no lo pone dentro del alcance");
    }

    @Test
    void borraLoQueYaDesaparecioDelOrigen() throws IOException {
        // La poda estricta sigue funcionando: si el original ya no existe, la copia
        // no esta respaldando nada y se retira dejando constancia.
        escribirEn(origen, "DCIM/foto.jpg", Fixtures.jpeg());

        escribirEn(destino, "DCIM/foto.jpg", Fixtures.jpeg());
        escribirEn(destino, "DCIM/borrada.jpg", Fixtures.jpeg());

        Podador.ResumenPodar resumen = podar();

        assertEquals(1, resumen.archivosBorrados());
        assertEquals(1, resumen.detalleBorrados().size());
        assertTrue(resumen.detalleBorrados().get(0).contains("borrada.jpg"),
                "la poda deja constancia de que borro");
        assertFalse(Files.exists(destino.resolve("DCIM/borrada.jpg")));
    }

    // --- utilidades ------------------------------------------------------------

    private Podador.ResumenPodar podar() throws IOException {
        Escaneo escaneo = escaneador.escanear(origen);
        return podador.podar(origen, destino, escaneo);
    }

    private static void escribirEn(Path raiz, String relativa, byte[] contenido)
            throws IOException {
        Path archivo = raiz.resolve(relativa);
        Files.createDirectories(archivo.getParent());
        Files.write(archivo, contenido);
    }
}
