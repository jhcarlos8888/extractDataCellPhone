package com.extractdatacellphone.support;

/**
 * Bytes de cabecera reales para fabricar archivos de prueba creibles.
 *
 * Se usan firmas autenticas porque toda la logica del proyecto descansa en detectar
 * el tipo por el contenido y no por la extension; un fixture poco realista invalidaria
 * las pruebas.
 */
public final class Fixtures {

    /** Tamano de bloque observado en las descargas incompletas de WhatsApp. */
    public static final long BLOQUE = 65536L;

    private Fixtures() {
    }

    public static byte[] jpeg() {
        byte[] cabecera = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00};
        return concat(cabecera, new byte[189]);
    }

    public static byte[] png() {
        byte[] cabecera = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52};
        return concat(cabecera, new byte[64]);
    }

    public static byte[] pdf() {
        return ("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n%%EOF\n")
                .getBytes();
    }

    /** Cabecera PK real, que es como Tika identifica un ZIP. */
    public static byte[] zip() {
        byte[] cabecera = {'P', 'K', 0x03, 0x04, 0x14, 0x00, 0x00, 0x00,
                0x08, 0x00, 0x00, 0x00, 0x00, 0x00};
        return concat(cabecera, new byte[64]);
    }

    /** Archivo de texto simple, que Tika detecta como text/plain. */
    public static byte[] texto() {
        return ("Una nota escrita por el usuario.\nSegunda linea de la nota.\n").getBytes();
    }

    /**
     * Cabecera de una nota de voz .opus de WhatsApp.
     *
     * Son los primeros 64 bytes de un PTT real del almacenamiento, no un archivo
     * inventado. Tika los clasifica como "application/ogg", un tipo que NO empieza
     * por audio/, video/ ni image/: si la lista de tipos exactos no lo contempla,
     * se descartan TODAS las notas de voz en silencio.
     */
    public static byte[] opus() {
        byte[] cabecera = {
            0x4f, 0x67, 0x67, 0x53, 0x00, 0x02, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x49, 0x2a,
            (byte) 0xf6, (byte) 0xa0, 0x01, 0x13, 0x4f, 0x70, 0x75, 0x73,
            0x48, 0x65, 0x61, 0x64, 0x01, 0x01, 0x38, 0x01,
            (byte) 0x80, 0x3e, 0x00, 0x00, 0x00, 0x00, 0x00, 0x4f,
            0x67, 0x67, 0x53, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        };
        return concat(cabecera, new byte[512]);
    }

    /**
     * Un MP4 valido de {@code tamano} bytes, con la cabecera "ftyp" que usa Tika
     * para identificarlo.
     */
    public static byte[] mp4(long tamano) {
        byte[] cabecera = {
                0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p',
                'm', 'p', '4', '2', 0x00, 0x00, 0x00, 0x00,
                'm', 'p', '4', '2', 'i', 's', 'o', 'm'};
        if (tamano <= cabecera.length) {
            byte[] pequeno = new byte[(int) Math.max(tamano, 0)];
            System.arraycopy(cabecera, 0, pequeno, 0, pequeno.length);
            return pequeno;
        }
        return concat(cabecera, new byte[(int) (tamano - cabecera.length)]);
    }

    /** Un MP4 del tamano exacto de una descarga por trozos incompleta. */
    public static byte[] mp4Truncado() {
        return mp4(4L * BLOQUE); // 262144 bytes, el tamano mas frecuente en el caso real
    }

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
