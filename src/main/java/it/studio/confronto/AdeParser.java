package it.studio.confronto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Estrae denominazione e codici ATECO dal testo delle pagine del cassetto fiscale.
 * Le regole sono provvisorie: il testo grezzo di ogni cliente viene comunque salvato,
 * quindi si possono affinare e rielaborare senza riscaricare nulla.
 */
public final class AdeParser {

    public record Anagrafica(String denominazione, List<String> ateco) {
    }

    private static final Pattern DENOMINAZIONE = Pattern.compile(
            "^(denominazione o ragione sociale|denominazione|ragione sociale|cognome e nome)\\s*:?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern COGNOME = Pattern.compile("^cognome\\s*:?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOME = Pattern.compile("^nome\\s*:?\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CODICE = Pattern.compile("(?<![\\d./])(\\d{2}\\.\\d{2}\\.\\d{1,2}|\\d{6})(?![\\d./])");
    /** Righe che parlano dell'attivita' economica: i codici si cercano li' e nelle due righe successive. */
    private static final Pattern RIGA_ATTIVITA = Pattern.compile("attivit|ateco", Pattern.CASE_INSENSITIVE);

    private AdeParser() {
    }

    public static Anagrafica analizza(String testo) {
        List<String> righe = Arrays.stream(testo.split("\\R")).map(String::trim).filter(r -> !r.isEmpty()).toList();
        String denominazione = "";
        String cognome = "";
        String nome = "";
        Set<String> ateco = new LinkedHashSet<>();
        int finestra = 0;

        for (int i = 0; i < righe.size(); i++) {
            String riga = righe.get(i);
            String successiva = i + 1 < righe.size() ? righe.get(i + 1) : "";

            Matcher m = DENOMINAZIONE.matcher(riga);
            if (denominazione.isEmpty() && m.matches()) {
                denominazione = valore(m.group(2), successiva);
            }
            m = COGNOME.matcher(riga);
            if (cognome.isEmpty() && m.matches()) {
                cognome = valore(m.group(1), successiva);
            }
            m = NOME.matcher(riga);
            if (nome.isEmpty() && m.matches()) {
                nome = valore(m.group(1), successiva);
            }

            if (RIGA_ATTIVITA.matcher(riga).find()) {
                finestra = 3;
            }
            if (finestra > 0) {
                finestra--;
                Matcher c = CODICE.matcher(riga);
                while (c.find()) {
                    ateco.add(c.group(1));
                }
            }
        }
        if (denominazione.isEmpty()) {
            denominazione = (cognome + " " + nome).trim();
        }
        return new Anagrafica(denominazione, new ArrayList<>(ateco));
    }

    /** Il valore sta sulla stessa riga dell'etichetta oppure, se li' non c'e' nulla, sulla riga successiva. */
    private static String valore(String sullaRiga, String successiva) {
        String v = sullaRiga.trim();
        return v.isEmpty() ? successiva : v;
    }
}
