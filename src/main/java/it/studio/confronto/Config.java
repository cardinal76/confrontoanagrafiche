package it.studio.confronto;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class Config {

    private final Properties props = new Properties();

    public Config(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(r);
        }
        // impostazioni personali: stanno in un file a parte, non tracciato da git, e prevalgono
        Path locale = file.resolveSibling("config.local.properties");
        if (Files.exists(locale)) {
            try (Reader r = Files.newBufferedReader(locale, StandardCharsets.UTF_8)) {
                props.load(r);
            }
        }
    }

    /** Valore facoltativo: stringa vuota se assente. */
    public String opt(String chiave) {
        return props.getProperty(chiave, "").trim();
    }

    public String opt(String chiave, String predefinito) {
        String v = opt(chiave);
        return v.isEmpty() ? predefinito : v;
    }

    /** Valore obbligatorio: errore chiaro se non e' stato compilato. */
    public String req(String chiave) {
        String v = opt(chiave);
        if (v.isEmpty()) {
            throw new IllegalStateException("Manca '" + chiave + "' in config.properties");
        }
        return v;
    }
}
