package com.extractdatacellphone.model;

/**
 * Resultado de clasificar un archivo del origen.
 *
 * La clasificacion se decide por el TIPO REAL detectado en los bytes magicos,
 * nunca por la extension: en la practica real encontramos archivos ".tmp",
 * ".thumbnail", ".bizprofile" y uno sin extension alguna que eran JPEG/MP4/PDF
 * verdaderos, y a la vez ".crypt14"/".nomedia" que no aportan nada recuperable.
 */
public enum Clasificacion {

    /** Contenido recuperable normal: se copia reflejando la estructura del origen. */
    CONTENIDO,

    /** Archivo que parece truncado/parcial: va a una carpeta etiquetada aparte. */
    CONTENIDO_PARCIAL,

    /**
     * No se pudo determinar que es: vacio, o bytes que no identifican ningun tipo.
     *
     * No es lo mismo que IGNORADO. IGNORADO es una decision de la configuracion
     * ("los .tmp no interesan"); INDETERMINADO es una respuesta que no llego. En
     * MTP es habitual que un archivo se degrade en silencio sin lanzar excepcion,
     * y tratar eso como basura haria que la poda borrara del destino una copia
     * que si era valida. Por eso no se copia, pero SI se protege de la poda.
     */
    INDETERMINADO,

    /** Basura o metadata: no se copia. */
    IGNORADO
}
