package com.extractdatacellphone.servicio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.extractdatacellphone.config.ExtractDataCellPhoneProperties;
import com.extractdatacellphone.model.ArchivoOrigen;
import com.extractdatacellphone.model.Clasificacion;
import com.extractdatacellphone.model.Escaneo;
import com.extractdatacellphone.model.ResultadoCopia;
import com.extractdatacellphone.model.Verificacion;
import com.extractdatacellphone.support.Fixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * La verificacion existe por un motivo concreto: el primer intento real devolvio
 * "exit 0" en todos los pasos mientras omitia 2075 archivos de WhatsApp Business.
 * Estas pruebas comprueban que esa omision quedaria al descubierto.
 */
class VerificadorTest {

    @TempDir
    Path origen;

    @TempDir
    Path destino;

    private ExtractDataCellPhoneProperties propiedades;
    private Escaneador escaneador;
    private Copiador copiador;
    private Verificador verificador;

    @BeforeEach
    void setUp() {
        propiedades = new ExtractDataCellPhoneProperties();
        DetectorTruncado truncado = new DetectorTruncado(propiedades);
        escaneador = new Escaneador(new DetectorContenido(), truncado, propiedades);
        copiador = new Copiador(propiedades);
        verificador = new Verificador(propiedades);
    }

    @Test
    void validaUnaCopiaCompleta() throws IOException {
        escribir("DCIM/a.jpg", Fixtures.jpeg());
        escribir("DCIM/b.jpg", Fixtures.jpeg());
        escribir("Documents/c.pdf", Fixtures.pdf());

        Verificacion v = ejecutarCopia();

        assertTrue(v.cuadra(), "discrepancias: " + v.discrepancias());
        assertEquals(3, v.archivosEsperados());
        assertEquals(3, v.archivosPresentes());
        assertEquals(v.bytesEsperados(), v.bytesPresentes());
        assertEquals(v.muestraRevisada(), v.muestraCoincidente());
    }

    @Test
    void delataUnArchivoQueSeOmitioEnLaCopia() throws IOException {
        // Este es exactamente el fallo del caso real: el proceso dice exito,
        // pero algo no llegó.
        String rutaOmitida = "Android/media/com.whatsapp/WhatsApp/Documents/omitted.pdf";
        escribir("DCIM/a.jpg", Fixtures.jpeg());
        escribir(rutaOmitida, Fixtures.pdf());

        Escaneo escaneo = escaneador.escanear(origen);
        List<ArchivoOrigen> contenido = escaneo.deClasificacion(Clasificacion.CONTENIDO);
        assertEquals(2, contenido.size(), "el escenario debe tener los dos archivos");

        // Se copia todo MENOS el indicado, fingiendo la omision silenciosa.
        List<ArchivoOrigen> aCopiar = contenido.stream()
                .filter(a -> !a.rutaRelativa().equals(rutaOmitida))
                .toList();
        copiador.copiar(destino, aCopiar);

        Verificacion v = verificador.verificar(destino, contenido, List.of());

        assertFalse(v.cuadra(), "la omision debe quedar al descubierto");
        assertTrue(v.discrepancias().stream()
                        .anyMatch(d -> d.contains("AUSENTE EN DESTINO") && d.contains("omitted.pdf")),
                "la discrepancia debe nombrar el archivo faltante: " + v.discrepancias());
    }

    @Test
    void delataUnaCopiaTruncadaPorqueLosBytesNoCuadran() throws IOException {
        byte[] bytes = Fixtures.jpeg();
        escribir("DCIM/a.jpg", bytes);

        Escaneo escaneo = escaneador.escanear(origen);
        List<ArchivoOrigen> contenido = escaneo.deClasificacion(Clasificacion.CONTENIDO);
        copiador.copiar(destino, contenido);

        // El archivo existe y tiene el nombre correcto, pero esta truncado.
        Files.write(destino.resolve("DCIM/a.jpg"), new byte[] {(byte) 0xFF, (byte) 0xD8});

        Verificacion v = verificador.verificar(destino, contenido, List.of());

        assertFalse(v.cuadra(), "una copia truncada no puede dar por bueno");
        assertTrue(v.discrepancias().stream().anyMatch(d -> d.contains("bytes")),
                "la suma de bytes debe delatarlo: " + v.discrepancias());
    }

    @Test
    void delataUnArchivoCorruptoPorElChecksum() throws IOException {
        byte[] bytes = Fixtures.jpeg();
        escribir("DCIM/a.jpg", bytes);

        Escaneo escaneo = escaneador.escanear(origen);
        List<ArchivoOrigen> contenido = escaneo.deClasificacion(Clasificacion.CONTENIDO);
        copiador.copiar(destino, contenido);

        // Mismo tamano, contenido distinto: solo el md5 lo detecta.
        byte[] alterado = bytes.clone();
        alterado[alterado.length - 1] ^= 0xFF;
        Files.write(destino.resolve("DCIM/a.jpg"), alterado);

        Verificacion v = verificador.verificar(destino, contenido, List.of());

        assertFalse(v.cuadra());
        assertTrue(v.discrepancias().stream().anyMatch(d -> d.contains("CHECKSUM")),
                "solo el checksum detecta un archivo del mismo tamano pero corrupto: "
                        + v.discrepancias());
    }

    @Test
    void permiteDesactivarLaMuestraDeChecksum() throws IOException {
        propiedades.setMuestraVerificacion(0);
        escribir("DCIM/a.jpg", Fixtures.jpeg());

        Verificacion v = ejecutarCopia();

        assertEquals(0, v.muestraRevisada());
        assertTrue(v.cuadra(), "aun sin muestra, el conteo y los bytes deben cuadrar");
    }

    // --- utilidades ------------------------------------------------------------

    private void escribir(String relativa, byte[] contenido) throws IOException {
        Path archivo = origen.resolve(relativa);
        Files.createDirectories(archivo.getParent());
        Files.write(archivo, contenido);
    }

    private Verificacion ejecutarCopia() throws IOException {
        Escaneo escaneo = escaneador.escanear(origen);
        List<ArchivoOrigen> contenido = escaneo.deClasificacion(Clasificacion.CONTENIDO);
        List<ArchivoOrigen> parciales = escaneo.deClasificacion(Clasificacion.CONTENIDO_PARCIAL);

        List<ResultadoCopia> resultados = copiador.copiar(destino, contenido);
        assertTrue(resultados.stream().noneMatch(ResultadoCopia::fallo),
                "la preparacion del escenario no debe fallar");

        return verificador.verificar(destino, contenido, parciales);
    }
}
