package com.extractdatacellphone.servicio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.extractdatacellphone.config.ExtractDataCellPhoneProperties;
import com.extractdatacellphone.model.ArchivoOrigen;
import com.extractdatacellphone.model.Clasificacion;
import com.extractdatacellphone.model.Escaneo;
import com.extractdatacellphone.support.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reproduce la estructura de un almacenamiento real: carpetas de usuario,
 * paquetes de mensajeria y mucho paquete de aplicacion que NO interesa.
 */
class EscaneadorTest {

    @TempDir
    Path origen;

    private ExtractDataCellPhoneProperties propiedades;
    private Escaneador escaneador;

    @BeforeEach
    void setUp() {
        propiedades = new ExtractDataCellPhoneProperties();
        escaneador = new Escaneador(new DetectorContenido(),
                new DetectorTruncado(propiedades), propiedades);
    }

    // --- Alcance ---------------------------------------------------------------

    @Test
    void recuperaLoQueHayEnLasCarpetasDeUsuario() throws IOException {
        escribir("DCIM/Camera/foto.jpg", Fixtures.jpeg());
        escribir("Pictures/captura.png", Fixtures.png());
        escribir("Documents/Lector de PDF/informe.pdf", Fixtures.pdf());
        escribir("Download/video.mp4", Fixtures.mp4(900));
        escribir("Movies/pelicula.mp4", Fixtures.mp4(700));
        escribir("Ringtones/tono.mp3", new byte[] {(byte) 0x49, (byte) 0x44, 0x33});

        List<String> rutas = rutas(escaneador.escanear(origen));

        assertTrue(rutas.containsAll(List.of(
                "DCIM/Camera/foto.jpg", "Pictures/captura.png",
                "Documents/Lector de PDF/informe.pdf", "Download/video.mp4",
                "Movies/pelicula.mp4", "Ringtones/tono.mp3")),
                "las carpetas de usuario deben entrar: " + rutas);
    }

    @Test
    void noSeMetenPaquetesDeAplicacionesQueNoSonDeMensajeria() throws IOException {
        // Caches de juegos, tiendas y bancos: lo que ensucia una recuperacion real.
        escribir("Android/data/com.scopely.monopolygo/files/unity.jpg", Fixtures.jpeg());
        escribir("Android/data/com.greencod.pinballdeluxereloaded/x.mp4", Fixtures.mp4(900));
        escribir("Android/media/com.instagram.android/foto.jpg", Fixtures.jpeg());
        escribir("Android/obb/com.juego.gigante/imagen.png", Fixtures.png());
        escribir("DCIM/foto.jpg", Fixtures.jpeg());

        List<String> rutas = rutas(escaneador.escanear(origen));

        assertEquals(List.of("DCIM/foto.jpg"), rutas,
                "solo debe entrar la carpeta de usuario");
    }

    @Test
    void siEntraEnLosPaquetesDeMensajeria() throws IOException {
        escribir("Android/media/com.whatsapp/WhatsApp/Media/imagen.jpg", Fixtures.jpeg());
        escribir("Android/media/com.whatsapp.w4b/WhatsApp Business/Media/foto.jpg", Fixtures.jpeg());
        escribir("Android/media/org.telegram.messenger/Telegram/Video/video.mp4",
                Fixtures.mp4(900));
        escribir("Android/data/com.discord/files/audio.opus",
                new byte[] {(byte) 0x4F, (byte) 0x67, (byte) 0x67, (byte) 0x53});

        List<String> rutas = rutas(escaneador.escanear(origen));

        assertEquals(4, rutas.size(),
                "los paquetes de mensajeria deben entrar completos: " + rutas);
    }

    @Test
    void recuperaLasNotasDeVozOpus() throws IOException {
        // Regresion de un fallo real: Tika clasifica el .opus como application/ogg,
        // que no empieza por audio/. Con una lista que solo mirara prefijos, se
        // habrian descartado TODAS las notas de voz de WhatsApp.
        escribir("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes/"
                + "202641/PTT-20261009-WA0002.opus", Fixtures.opus());

        ArchivoOrigen nota = escaneador.escanear(origen).archivos().get(0);

        assertEquals(Clasificacion.CONTENIDO, nota.clasificacion(),
                "una nota de voz .opus es contenido recuperable");
        assertEquals("application/ogg", nota.mime(),
                "el tipo real de un .opus es application/ogg");
    }

    @Test
    void ignoraLosArchivosSuelosEnLaRaiz() throws IOException {
        // En la practica real habia un "badbuff.bin" suelto en la raiz.
        escribir("badbuff.bin", new byte[] {1, 2, 3});
        escribir("DCIM/foto.jpg", Fixtures.jpeg());

        Map<String, ArchivoOrigen> porRuta = porRuta(escaneador.escanear(origen));

        assertEquals(Clasificacion.IGNORADO, porRuta.get("badbuff.bin").clasificacion());
        assertTrue(porRuta.get("badbuff.bin").motivo().contains("raiz"));
    }

    // --- Extensiones -----------------------------------------------------------

    @Test
    void clasificaPorContenidoNoPorExtension() throws IOException {
        escribir("Documents/CATALOGO MAYOR TENIS- VALSU", Fixtures.pdf());
        escribir("Android/media/com.whatsapp/WhatsApp/.Shared/abc=.tmp", Fixtures.jpeg());
        escribir("Documents/marcador.nomedia", new byte[0]);
        escribir("Documents/datos.bin", new byte[] {1, 2, 3});

        Map<String, ArchivoOrigen> porRuta = porRuta(escaneador.escanear(origen));

        assertEquals(Clasificacion.CONTENIDO,
                porRuta.get("Documents/CATALOGO MAYOR TENIS- VALSU").clasificacion(),
                "un PDF sin extension debe recuperarse");
        assertEquals(Clasificacion.IGNORADO,
                porRuta.get("Android/media/com.whatsapp/WhatsApp/.Shared/abc=.tmp")
                        .clasificacion(),
                "aunque el archivo este en mensajeria, .tmp se descarta siempre");
        assertEquals(Clasificacion.IGNORADO,
                porRuta.get("Documents/marcador.nomedia").clasificacion());
        assertEquals(Clasificacion.IGNORADO,
                porRuta.get("Documents/datos.bin").clasificacion());
    }

    @Test
    void txtYZipSeConservanFueraDeLasAppsPeroNoDentro() throws IOException {
        // Un .txt en Documents puede ser una nota del usuario; dentro de una app
        // es un recurso interno. Igual con un .zip descargado.
        escribir("Documents/nota.txt", Fixtures.texto());
        escribir("Download/paquete.zip", Fixtures.zip());
        escribir("Android/media/com.whatsapp/WhatsApp/recursos.txt", Fixtures.texto());
        escribir("Android/media/com.whatsapp/WhatsApp/paquete.zip", Fixtures.zip());

        Map<String, ArchivoOrigen> porRuta = porRuta(escaneador.escanear(origen));

        assertEquals(Clasificacion.CONTENIDO,
                porRuta.get("Documents/nota.txt").clasificacion(),
                "una nota .txt del usuario si debe recuperarse");
        assertEquals(Clasificacion.CONTENIDO,
                porRuta.get("Download/paquete.zip").clasificacion(),
                "un .zip descargado por el usuario si debe recuperarse");
        assertEquals(Clasificacion.IGNORADO,
                porRuta.get("Android/media/com.whatsapp/WhatsApp/recursos.txt").clasificacion());
        assertEquals(Clasificacion.IGNORADO,
                porRuta.get("Android/media/com.whatsapp/WhatsApp/paquete.zip").clasificacion());
    }

    @Test
    void descartaLosArchivosTipoPatchQueNoTienenExtension() throws IOException {
        // "patch_1.33.9_304_27" no tiene extension, asi que una lista de extensiones
        // nunca lo alcanzaria. Se descarta por el nombre.
        escribir("Android/media/com.whatsapp/WhatsApp/patch_1.33.9_304_27",
                new byte[] {1, 2, 3, 4});
        escribir("Download/patch_2.0.0.mp4", Fixtures.mp4(900));

        Map<String, ArchivoOrigen> porRuta = porRuta(escaneador.escanear(origen));

        assertEquals(Clasificacion.IGNORADO,
                porRuta.get("Android/media/com.whatsapp/WhatsApp/patch_1.33.9_304_27")
                        .clasificacion());
        assertTrue(porRuta.get("Android/media/com.whatsapp/WhatsApp/patch_1.33.9_304_27")
                .motivo().contains("basura"));
        assertEquals(Clasificacion.IGNORADO,
                porRuta.get("Download/patch_2.0.0.mp4").clasificacion(),
                "el patron aplica aunque tenga extension");
    }

    @Test
    void separaLosArchivosQueParecenDescargasIncompletas() throws IOException {
        escribir("Android/media/com.whatsapp/WhatsApp/.Shared/video=.tmp",
                Fixtures.mp4Truncado());

        Map<String, ArchivoOrigen> porRuta = porRuta(escaneador.escanear(origen));

        // .tmp se descarta antes de llegar a la deteccion de truncado.
        assertEquals(Clasificacion.IGNORADO,
                porRuta.get("Android/media/com.whatsapp/WhatsApp/.Shared/video=.tmp")
                        .clasificacion());
    }

    @Test
    void unMp4TruncadoEnCarpetaDeUsuarioSiSeSepara() throws IOException {
        escribir("Download/video_incompleto.mp4", Fixtures.mp4Truncado());

        Map<String, ArchivoOrigen> porRuta = porRuta(escaneador.escanear(origen));

        assertEquals(Clasificacion.CONTENIDO_PARCIAL,
                porRuta.get("Download/video_incompleto.mp4").clasificacion());
    }

    // --- Otras reglas ----------------------------------------------------------

    @Test
    void respetaLasCarpetasExcluidas() throws IOException {
        propiedades.setExcluirCarpetas(List.of("juanCarlosNotesObsidian"));
        escribir("juanCarlosNotesObsidian/nota.md", "# privado".getBytes());
        escribir("DCIM/foto.jpg", Fixtures.jpeg());

        List<String> rutas = rutas(escaneador.escanear(origen));

        assertEquals(List.of("DCIM/foto.jpg"), rutas);
    }

    @Test
    void lasCarpetasOcultasNoSeExcluyenPorquePuedenContenerContenido() throws IOException {
        // En el caso real, .Statuses de WhatsApp guardaba estados verdaderos.
        escribir("Android/media/com.whatsapp/WhatsApp/Media/.Statuses/estado.jpg",
                Fixtures.jpeg());

        List<ArchivoOrigen> encontrados = escaneador.escanear(origen).archivos();

        assertEquals(1, encontrados.size());
        assertEquals(Clasificacion.CONTENIDO, encontrados.get(0).clasificacion());
    }

    @Test
    void elAlcanceSePuedeAmpliarDesdeLaConfiguracion() throws IOException {
        propiedades.setCarpetasUsuario(List.of("DCIM", "MiCarpeta"));
        escribir("MiCarpeta/dato.jpg", Fixtures.jpeg());
        escribir("Documents/otro.pdf", Fixtures.pdf());

        List<String> rutas = rutas(escaneador.escanear(origen));

        assertEquals(List.of("MiCarpeta/dato.jpg"), rutas,
                "las carpetas configuradas deben sustituir a las por defecto");
    }

    @Test
    void descartaLosArchivosSuelosEnLosContenedoresDeAndroid() throws IOException {
        // Un archivo suelto en Android/media o Android/data no pertenece a ningun
        // paquete de mensajeria. Sin esta regla entraria al destino y quedaria
        // inmune a la poda, porque el escaneo lo marcara como contenido.
        escribir("Android/media/foto_suelta.jpg", Fixtures.jpeg());
        escribir("Android/data/dato_suelto.jpg", Fixtures.jpeg());
        escribir("Android/media/com.whatsapp/WhatsApp/Media/img.jpg", Fixtures.jpeg());

        Map<String, ArchivoOrigen> porRuta = porRuta(escaneador.escanear(origen));

        assertEquals(Clasificacion.IGNORADO,
                porRuta.get("Android/media/foto_suelta.jpg").clasificacion());
        assertEquals(Clasificacion.IGNORADO,
                porRuta.get("Android/data/dato_suelto.jpg").clasificacion());
        assertEquals(Clasificacion.CONTENIDO,
                porRuta.get("Android/media/com.whatsapp/WhatsApp/Media/img.jpg").clasificacion(),
                "lo que si esta dentro de un paquete de mensajeria sigue entrando");
    }

    @Test
    void distingueLoIndeterminadoDeLoDescartadoPorRegla() throws IOException {
        // Un archivo vacio o con bytes no identificables no es basura decidida por
        // una regla: es una respuesta que no llego. Sus copias en destino se
        // protegen de la poda. Lo que decide una regla sigue siendo podable.
        escribir("Documents/archivo.dato", new byte[] {1, 2, 3});
        escribir("Documents/vacio.jpg", new byte[0]);
        escribir("Documents/datos.bin", new byte[] {1, 2, 3});

        Map<String, ArchivoOrigen> porRuta = porRuta(escaneador.escanear(origen));

        assertEquals(Clasificacion.INDETERMINADO,
                porRuta.get("Documents/archivo.dato").clasificacion(),
                "bytes no identificables no son basura decidida");
        assertEquals(Clasificacion.INDETERMINADO,
                porRuta.get("Documents/vacio.jpg").clasificacion(),
                "un archivo vacio es una respuesta que no llego");
        assertEquals(Clasificacion.IGNORADO,
                porRuta.get("Documents/datos.bin").clasificacion(),
                "lo que decide una regla sigue siendo descartado y podable");
    }

    @Test
    void explicaEnElInformePorQueDescartoCadaArchivo() throws IOException {
        escribir("Documents/marcador.nomedia", new byte[0]);

        Map<String, ArchivoOrigen> porRuta = porRuta(escaneador.escanear(origen));

        assertFalse(porRuta.isEmpty());
        assertTrue(porRuta.get("Documents/marcador.nomedia").motivo().contains("nomedia"));
    }

    // --- utilidades ------------------------------------------------------------

    private void escribir(String relativa, byte[] contenido) throws IOException {
        Path destino = origen.resolve(relativa);
        Files.createDirectories(destino.getParent());
        Files.write(destino, contenido);
    }

    private static List<String> rutas(Escaneo escaneo) {
        return escaneo.archivos().stream()
                .map(ArchivoOrigen::rutaRelativa)
                .sorted()
                .toList();
    }

    private static Map<String, ArchivoOrigen> porRuta(Escaneo escaneo) {
        return escaneo.archivos().stream()
                .collect(Collectors.toMap(ArchivoOrigen::rutaRelativa, a -> a));
    }
}
