package com.extractdatacellphone.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Reglas de alcance y clasificacion.
 *
 * El objetivo es recuperar lo que le sirve a una persona normal, no caché de sistema.
 * Por eso hay DOS filtros y no uno solo:
 *
 *  1) ALCANCE DE CARPETAS. Solo entran las carpetas de usuario (DCIM, Documents,
 *     Download...) y, dentro de Android, los paquetes de aplicaciones de mensajeria.
 *     De lo contrario se cuelan caches de juegos, tiendas y procesos del sistema
 *     que solo contienen trozos de descargas y logs.
 *
 *  2) CONTENIDO REAL. Para lo que entra en alcance, el tipo se decide por los bytes
 *     magicos y nunca por la extension, porque la extension miente en los dos sentidos:
 *     hay archivos ".tmp" que son JPEG de verdad, y hay archivos sin extension
 *     (por ejemplo un PDF de 67 MB) que un filtro por extension ignoraria por completo.
 */
@Component
@ConfigurationProperties(prefix = "extractdatacellphone")
public class ExtractDataCellPhoneProperties {

    /** Prefijos de tipo MIME considerados contenido recuperable. */
    private Set<String> prefijosMime = new LinkedHashSet<>(List.of(
            "image/", "video/", "audio/"));

    /**
     * Contenedores multimedia cuyo MIME NO empieza por image/, video/ ni audio/.
     *
     * Esta lista existe porque Tika devuelve tipos que parecen de "aplicacion"
     * para archivos que son multimedia de verdad. Detectado con archivos reales:
     *
     *   - application/ogg   -> las notas de voz .opus de WhatsApp. Con solo los
     *                          prefijos habrian SE PERDIDO TODAS.
     *   - application/mp4   -> ciertos envoltorios MP4.
     *
     * No confiar en que "parece multimedia" es exactamente el fallo que causa
     * perdidas silenciosas de contenido valido.
     */
    private Set<String> mimeMultimedia = new LinkedHashSet<>(List.of(
            "application/ogg",
            "application/mp4"));

    /** Tipos MIME exactos que tambien se recuperan (documentos y empaquetados). */
    private Set<String> mimeDocumentos = new LinkedHashSet<>(List.of(
            // document/x-tika-ooxml: Tika lo devuelve para .docx/.pptx/.xlsx reales
            // cuando no resuelve el contenedor. Detectado con documentos propios.
            "application/x-tika-ooxml",
            "text/html",
            "application/pdf",
            "application/msword",
            "application/vnd.ms-excel",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
            "application/epub+zip",
            "application/rtf",
            "text/rtf",
            "text/plain",
            "text/csv",
            "text/markdown",
            "application/zip",
            "application/x-zip-compressed",
            "application/x-rar-compressed",
            "application/vnd.rar",
            "application/x-7z-compressed",
            "application/gzip",
            "application/x-tar",
            "application/vnd.android.package-archive"));

    /**
     * Extensiones descartadas en TODAS partes. Son marcadores, metadatos y basura
     * de aplicaciones: nunca representan un documento de un usuario.
     */
    private Set<String> extensionesIgnoradas = new LinkedHashSet<>(List.of(
            "nomedia", "database_uuid", "bin", "log", "chck", "eab",
            "crypt14", "crypt12", "crypt11", "was",
            "db", "db-wal", "db-shm", "journal", "lock",
            "tmp", "temp", "ver", "hash", "json", "cache", "idx", "part"));

    /**
     * Extensiones descartadas SOLO dentro de Android.
     *
     * Un ".txt" en Documents puede ser una nota del usuario y un ".zip" en Download
     * algo que descargo. Dentro de una aplicacion, en cambio, esos archivos son
     * recursos o paquetes internos que no le sirven a nadie.
     *
     * Las de JavaScript, HTML y CSS estan aqui por lo mismo: son paginas y scripts
     * incrustados en los visores web de las apps, descubiertos al clasificar
     * muestras reales del almacenamiento.
     */
    private Set<String> extensionesIgnoradasEnApps = new LinkedHashSet<>(List.of(
            "txt", "zip", "apk", "obb", "dex", "so", "arsc", "xml", "pb", "pbm",
            "js", "css", "htm", "html", "manifest", "batch", "jwt", "bizprofile",
            "thumbnail", "properties", "conf", "cfg", "ini"));

    /**
     * Expresiones regulares de nombres de archivo descartadas.
     *
     * Necesarias porque hay archivos basura SIN extension, como
     * "patch_1.33.9_304_27", que una lista de extensiones nunca alcanzaria a ver.
     */
    private List<String> patronesNombreIgnorados = List.of(
            "(?i)^patch[_-].*",
            "(?i)^.*\\.patch$",
            "(?i)^.*\\.part\\d*$");

    /** Carpetas de primer nivel que contienen material del usuario. */
    private List<String> carpetasUsuario = List.of(
            "DCIM", "Pictures", "Movies", "Documents", "Download", "DownloadHelper",
            "Music", "Recordings", "Ringtones", "Alarms", "Audiobooks",
            "Notifications", "Podcasts");

    /**
     * Paquetes de aplicaciones de mensajeria cuyo contenido si interesa.
     * Dentro de Android solo se desciende en estos.
     */
    private List<String> paquetesMensajeria = List.of(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "com.facebook.orca",
            "org.thoughtcrime.securesms",
            "com.discord",
            "com.google.android.apps.messaging",
            "com.skype.raider",
            "com.zoom.us",
            "com.microsoft.teams");

    /**
     * Tamano de bloque usado por los clientes de mensajeria para descargar por trozos.
     * 65536 bytes (64 KiB) fue el patron observado en los MP4 truncados de WhatsApp.
     */
    private long bloqueTruncado = 65536L;

    /** Umbral superior para no apartar por error un video grande legitimo. */
    private long maxTamanoTruncado = 8388608L;

    private boolean detectarTruncados = true;
    private int muestraVerificacion = 20;
    private boolean sobreescribir = false;

    /** Exclusiones adicionales por nombre de carpeta, ademas del alcance. */
    private List<String> excluirCarpetas = List.of();

    private transient List<Pattern> patronesCompilados;

    // --- Alcance ---------------------------------------------------------------

    /** true si esta carpeta de primer nivel contiene material del usuario. */
    public boolean esCarpetaUsuario(String nombre) {
        return contieneIgnoreCase(carpetasUsuario, nombre);
    }

    /** true si este paquete de Android es una aplicacion de mensajeria. */
    public boolean esPaqueteMensajeria(String paquete) {
        return contieneIgnoreCase(paquetesMensajeria, paquete);
    }

    public boolean esCarpetaExcluida(String nombreCarpeta) {
        return contieneIgnoreCase(excluirCarpetas, nombreCarpeta);
    }

    // --- Clasificacion ---------------------------------------------------------

    /** true si el tipo MIME es recuperable por prefijo (image/, video/, audio/). */
    public boolean esMimeContenido(String mime) {
        if (mime == null || mime.isBlank()) {
            return false;
        }
        String m = mime.toLowerCase(Locale.ROOT);
        for (String prefijo : prefijosMime) {
            if (m.startsWith(prefijo)) {
                return true;
            }
        }
        return mimeMultimedia.contains(m) || mimeDocumentos.contains(m);
    }

    /** true si la extension se descarta en cualquier parte del arbol. */
    public boolean esExtensionIgnorada(String nombreArchivo) {
        return extensionesIgnoradas.contains(extensionDe(nombreArchivo));
    }

    /** true si la extension se descarta, pero solo cuando el archivo esta en Android. */
    public boolean esExtensionIgnoradaEnApps(String nombreArchivo) {
        return extensionesIgnoradasEnApps.contains(extensionDe(nombreArchivo));
    }

    /** true si el nombre coincide con algun patron de basura sin extension. */
    public boolean esNombreIgnorado(String nombreArchivo) {
        for (Pattern patron : patronesCompilados()) {
            if (patron.matcher(nombreArchivo).matches()) {
                return true;
            }
        }
        return false;
    }

    private List<Pattern> patronesCompilados() {
        if (patronesCompilados == null) {
            List<Pattern> compilados = new ArrayList<>();
            for (String texto : patronesNombreIgnorados) {
                if (texto == null || texto.isBlank()) {
                    continue;
                }
                try {
                    compilados.add(Pattern.compile(texto));
                } catch (PatternSyntaxException e) {
                    compilados.add(Pattern.compile(Pattern.quote(texto)));
                }
            }
            patronesCompilados = List.copyOf(compilados);
        }
        return patronesCompilados;
    }

    private static String extensionDe(String nombreArchivo) {
        int punto = nombreArchivo.lastIndexOf('.');
        if (punto < 0 || punto == nombreArchivo.length() - 1) {
            return "";
        }
        return nombreArchivo.substring(punto + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean contieneIgnoreCase(List<String> lista, String valor) {
        if (lista == null || valor == null) {
            return false;
        }
        for (String elemento : lista) {
            if (elemento != null && elemento.equalsIgnoreCase(valor)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> normalizar(Set<String> valores) {
        if (valores == null) {
            return new LinkedHashSet<>();
        }
        Set<String> salida = new LinkedHashSet<>();
        for (String s : valores) {
            if (s != null && !s.isBlank()) {
                salida.add(s.trim().toLowerCase(Locale.ROOT));
            }
        }
        return salida;
    }

    private static List<String> copia(List<String> valores) {
        return (valores == null) ? List.of() : List.copyOf(valores);
    }

    // --- Getters y setters -----------------------------------------------------

    public Set<String> getPrefijosMime() { return prefijosMime; }
    public void setPrefijosMime(Set<String> v) { this.prefijosMime = normalizar(v); }

    public Set<String> getMimeDocumentos() { return mimeDocumentos; }
    public void setMimeDocumentos(Set<String> v) { this.mimeDocumentos = normalizar(v); }

    public Set<String> getMimeMultimedia() { return mimeMultimedia; }
    public void setMimeMultimedia(Set<String> v) { this.mimeMultimedia = normalizar(v); }

    public Set<String> getExtensionesIgnoradas() { return extensionesIgnoradas; }
    public void setExtensionesIgnoradas(Set<String> v) { this.extensionesIgnoradas = normalizar(v); }

    public Set<String> getExtensionesIgnoradasEnApps() { return extensionesIgnoradasEnApps; }
    public void setExtensionesIgnoradasEnApps(Set<String> v) {
        this.extensionesIgnoradasEnApps = normalizar(v);
    }

    public List<String> getPatronesNombreIgnorados() { return patronesNombreIgnorados; }
    public void setPatronesNombreIgnorados(List<String> v) {
        this.patronesNombreIgnorados = copia(v);
        this.patronesCompilados = null;
    }

    public List<String> getCarpetasUsuario() { return carpetasUsuario; }
    public void setCarpetasUsuario(List<String> v) { this.carpetasUsuario = copia(v); }

    public List<String> getPaquetesMensajeria() { return paquetesMensajeria; }
    public void setPaquetesMensajeria(List<String> v) { this.paquetesMensajeria = copia(v); }

    public long getBloqueTruncado() { return bloqueTruncado; }
    public void setBloqueTruncado(long v) { this.bloqueTruncado = v; }

    public long getMaxTamanoTruncado() { return maxTamanoTruncado; }
    public void setMaxTamanoTruncado(long v) { this.maxTamanoTruncado = v; }

    public boolean isDetectarTruncados() { return detectarTruncados; }
    public void setDetectarTruncados(boolean v) { this.detectarTruncados = v; }

    public int getMuestraVerificacion() { return muestraVerificacion; }
    public void setMuestraVerificacion(int v) { this.muestraVerificacion = v; }

    public boolean isSobreescribir() { return sobreescribir; }
    public void setSobreescribir(boolean v) { this.sobreescribir = v; }

    public List<String> getExcluirCarpetas() { return excluirCarpetas; }
    public void setExcluirCarpetas(List<String> v) { this.excluirCarpetas = copia(v); }

    @Override
    public String toString() {
        return "ExtractDataCellPhoneProperties{"
                + "carpetasUsuario=" + carpetasUsuario.size() + " carpetas"
                + ", paquetesMensajeria=" + paquetesMensajeria.size() + " paquetes"
                + ", prefijosMime=" + prefijosMime
                + ", mimeDocumentos=" + mimeDocumentos.size() + " tipos"
                + ", extensionesIgnoradas=" + extensionesIgnoradas
                + ", extensionesIgnoradasEnApps=" + extensionesIgnoradasEnApps
                + ", patronesNombre=" + patronesNombreIgnorados.size()
                + ", detectarTruncados=" + detectarTruncados
                + ", muestraVerificacion=" + muestraVerificacion
                + ", excluirCarpetas=" + excluirCarpetas
                + '}';
    }
}
