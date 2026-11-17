package it.studio.confronto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** CSV minimale con separatore punto e virgola, usato come salvataggio progressivo. */
public final class Csv {

    private Csv() {
    }

    public static void accoda(Path file, List<String> campi) throws IOException {
        String riga = campi.stream()
                .map(c -> "\"" + (c == null ? "" : c.replace("\"", "\"\"").replaceAll("\\R", " ")) + "\"")
                .collect(Collectors.joining(";")) + System.lineSeparator();
        Files.writeString(file, riga, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    public static List<List<String>> leggi(Path file) throws IOException {
        List<List<String>> righe = new ArrayList<>();
        if (!Files.exists(file)) {
            return righe;
        }
        for (String linea : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (linea.isBlank()) {
                continue;
            }
            List<String> campi = new ArrayList<>();
            StringBuilder sb = new StringBuilder();
            boolean traApici = false;
            for (int i = 0; i < linea.length(); i++) {
                char c = linea.charAt(i);
                if (traApici) {
                    if (c == '"' && i + 1 < linea.length() && linea.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else if (c == '"') {
                        traApici = false;
                    } else {
                        sb.append(c);
                    }
                } else if (c == '"') {
                    traApici = true;
                } else if (c == ';') {
                    campi.add(sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
            campi.add(sb.toString());
            righe.add(campi);
        }
        return righe;
    }
}
