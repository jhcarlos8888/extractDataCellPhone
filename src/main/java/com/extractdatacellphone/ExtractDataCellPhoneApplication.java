package com.extractdatacellphone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Extractor de archivos de celulares y almacenamientos.
 *
 * Arranca como una herramienta de linea de comandos: no abre puerto ni expone API.
 * Ver {@code com.extractdatacellphone.cli.CliExtractDataCellPhone} para el uso y los codigos de salida.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ExtractDataCellPhoneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExtractDataCellPhoneApplication.class, args);
    }
}
