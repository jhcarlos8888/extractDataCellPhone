package com.extractdatacellphone.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Cubre las reglas de alcance y clasificacion.
 *
 * La seccion de tipos MIME existe por una perdida real: Tika clasifica las notas
 * de voz .opus de WhatsApp como "application/ogg" y ciertos .docx/.pptx como
 * "application/x-tika-ooxml". Ninguno de los dos empieza por image/, video/ ni
 * audio/, asi que una lista de prefijos los habria descartado a todos.
 */
class ExtractDataCellPhonePropertiesTest {

    private ExtractDataCellPhoneProperties propiedades;

    @BeforeEach
    void setUp() {
        propiedades = new ExtractDataCellPhoneProperties();
    }

    // --- Tipos cuyo MIME no sigue el prefijo ---------------------------------

    @Test
    void aceptaLosTiposMultimediaQueNoEmpiezanPorAudioNiVideo() {
        assertTrue(propiedades.esMimeContenido("application/ogg"),
                "las notas de voz .opus de WhatsApp llegan como application/ogg");
        assertTrue(propiedades.esMimeContenido("application/mp4"));
    }

    @Test
    void aceptaLosDocumentosOfficeDetectadosComoTikaOoxml() {
        assertTrue(propiedades.esMimeContenido("application/x-tika-ooxml"),
                "los .docx y .pptx reales llegan como application/x-tika-ooxml");
        assertTrue(propiedades.esMimeContenido("text/html"));
    }

    @Test
    void sigueAceptandoLosPrefijosHabituales() {
        assertTrue(propiedades.esMimeContenido("image/heic"));
        assertTrue(propiedades.esMimeContenido("video/3gpp"));
        assertTrue(propiedades.esMimeContenido("audio/flac"));
        assertTrue(propiedades.esMimeContenido("application/pdf"));
    }

    @Test
    void sigueRechazandoLoQueNoEsContenidoDeUnaPersona() {
        assertFalse(propiedades.esMimeContenido("application/octet-stream"));
        assertFalse(propiedades.esMimeContenido("application/xml"));
        assertFalse(propiedades.esMimeContenido("text/javascript"));
        assertFalse(propiedades.esMimeContenido(null));
        assertFalse(propiedades.esMimeContenido(""));
        assertFalse(propiedades.esMimeContenido("   "));
    }

    // --- Alcance ---------------------------------------------------------------

    @Test
    void soloLasCarpetasDeUsuarioYAndroidEntranEnAlcance() {
        assertTrue(propiedades.esCarpetaUsuario("DCIM"));
        assertTrue(propiedades.esCarpetaUsuario("documents"),
                "la comparacion de carpetas no distingue mayusculas");
        assertTrue(propiedades.esCarpetaUsuario("Download"));
        assertFalse(propiedades.esCarpetaUsuario("Android"),
                "Android no es carpeta de usuario: es un contenedor");
        assertFalse(propiedades.esCarpetaUsuario("MiCarpeta"));
    }

    @Test
    void soloLosPaquetesDeMensajeriaEntranDentroDeAndroid() {
        assertTrue(propiedades.esPaqueteMensajeria("com.whatsapp"));
        assertTrue(propiedades.esPaqueteMensajeria("com.whatsapp.w4b"));
        assertTrue(propiedades.esPaqueteMensajeria("org.telegram.messenger"));
        assertTrue(propiedades.esPaqueteMensajeria("com.discord"));
        assertFalse(propiedades.esPaqueteMensajeria("com.instagram.android"));
        assertFalse(propiedades.esPaqueteMensajeria("com.scopely.monopolygo"));
        assertFalse(propiedades.esPaqueteMensajeria("com.android.vending"));
    }

    // --- Extensiones y nombres basura ------------------------------------------

    @Test
    void lasExtensionesBasuraSeDescartanEnTodasPartes() {
        assertTrue(propiedades.esExtensionIgnorada("fichero.tmp"));
        assertTrue(propiedades.esExtensionIgnorada("informe.HASH"),
                "la extension se compara sin distinguir mayusculas");
        assertTrue(propiedades.esExtensionIgnorada("backup.db"));
        assertTrue(propiedades.esExtensionIgnorada("msgstore.db.crypt14"));
        assertFalse(propiedades.esExtensionIgnorada("foto.jpg"));
    }

    @Test
    void losTiposDeSoloAppsNoAplicanAFueraDeLasApps() {
        assertTrue(propiedades.esExtensionIgnoradaEnApps("nota.txt"));
        assertTrue(propiedades.esExtensionIgnoradaEnApps("paquete.zip"));
        assertFalse(propiedades.esExtensionIgnorada("nota.txt"),
                "un .txt fuera de las apps no se descarta: puede ser del usuario");
        assertFalse(propiedades.esExtensionIgnorada("paquete.zip"),
                "un .zip fuera de las apps no se descarta: puede ser del usuario");
    }

    @Test
    void descartaLosArchivosBasuraSinExtensionPorElNombre() {
        assertTrue(propiedades.esNombreIgnorado("patch_1.33.9_304_27"),
                "no tiene extension, por eso hay que cazarlo por nombre");
        assertTrue(propiedades.esNombreIgnorado("PATCH-2.0"));
        assertTrue(propiedades.esNombreIgnorado("paquete.zip.patch"));
        assertFalse(propiedades.esNombreIgnorado("VID_20261010_0106.mp4"));
        assertFalse(propiedades.esNombreIgnorado("fotografia.jpg"));
    }

    // --- Acoplamiento anotacion <-> application.yml ----------------------------
    //
    // El prefijo vive en DOS sitios que deben coincidir: la seccion raiz del
    // application.yml y el @ConfigurationProperties de la clase. Si divergen, la
    // app ARRANCA igualmente (Spring ignora raices YAML desconocidas) y usa los
    // valores por defecto en silencio: un fallo silencioso, que es exactamente el
    // tipo de fallo que esta herramienta existe para evitar. Por eso se liga el
    // YAML real con el prefijo leido por reflexion desde la propia anotacion.

    @Test
    void elApplicationYmlRealSeEnlazaBajoElPrefijoDeLaAnotacion() throws Exception {
        String prefijo = ExtractDataCellPhoneProperties.class
                .getAnnotation(ConfigurationProperties.class).prefix();

        List<PropertySource<?>> fuentes =
                new YamlPropertySourceLoader().load("aplicacion", new ClassPathResource("application.yml"));
        Binder binder = new Binder(ConfigurationPropertySources.from(fuentes));

        ExtractDataCellPhoneProperties enlazadas = binder
                .bind(prefijo, ExtractDataCellPhoneProperties.class)
                .orElseThrow(() -> new AssertionError(
                        "application.yml no tiene la seccion '" + prefijo
                                + "': el prefijo de la anotacion y el del YAML han divergido"));

        assertEquals(List.of("DCIM", "Pictures", "Movies", "Documents"),
                enlazadas.getCarpetasUsuario().subList(0, 4),
                "carpetas-usuario debe llegar del YAML a la propiedad");
        assertTrue(enlazadas.getPaquetesMensajeria().contains("com.whatsapp"));
        assertTrue(enlazadas.getPaquetesMensajeria().contains("com.whatsapp.w4b"),
                "si w4b no llega del YAML, se pierden en silencio 2075 archivos");
        assertEquals(8388608L, enlazadas.getMaxTamanoTruncado());
        assertEquals(20, enlazadas.getMuestraVerificacion());
    }
}
