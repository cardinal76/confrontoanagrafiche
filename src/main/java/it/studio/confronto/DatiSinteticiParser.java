package it.studio.confronto;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Estrae i campi "Dati Generali" e "Inquadramento" dal testo della finestra Dati Sintetici
 * del Cassetto Previdenziale. Lavora sul testo visibile, non sulla struttura HTML,
 * cosi' regge ai cambi di impaginazione.
 */
public final class DatiSinteticiParser {

    public static final String ATECO = "Codice ATECO";
    public static final String ETICHETTA_ATECO = "Etichetta ATECO";
    public static final String POSIZIONE = "Posizione Contributiva";
    public static final String CSC = "Codice Statistico Contributivo";

    /** Campi nell'ordine in cui finiscono nel file di uscita. */
    public static final List<String> CAMPI = List.of(
            "Codice Fiscale", POSIZIONE, "Gestione", "Denominazione", "Tipologia Posizione", "Indirizzo PEC",
            "Inizio attività con dipendenti", "Stato Azienda", "Data Variazione Stato", "Sede Competenza", "Indirizzo",
            "Attività Dichiarata", "Accentramento", "Descrizione Accentramento", CSC,
            "Codice ISTAT attività economica", ATECO, ETICHETTA_ATECO, "Descrizione Inquadramento");

    private static final int MAX_RIGHE_VALORE = 3;
    private static final Map<String, String> ETICHETTE = new LinkedHashMap<>();
    private static final Set<String> SEZIONI = Set.of("dati generali", "inquadramento", "storico inquadramento");

    static {
        for (String campo : CAMPI) {
            ETICHETTE.put(norm(campo), campo);
        }
        ETICHETTE.remove(norm(ATECO));
        ETICHETTE.remove(norm(ETICHETTA_ATECO));
    }

    private DatiSinteticiParser() {
    }

    /** Restituisce i campi trovati; mappa vuota se il testo non contiene la sezione "Dati Generali". */
    public static Map<String, String> analizza(String testo) {
        Map<String, String> dati = new LinkedHashMap<>();
        if (testo == null) {
            return dati;
        }
        List<List<String>> righe = new ArrayList<>();
        for (String linea : testo.split("\\R")) {
            List<String> celle = Arrays.stream(linea.split("\t")).map(String::trim).filter(c -> !c.isEmpty()).toList();
            if (!celle.isEmpty()) {
                righe.add(celle);
            }
        }
        int inizio = indiceSezione(righe, "dati generali", 0);
        if (inizio < 0) {
            // la nuova interfaccia puo' mostrare l'inquadramento senza la sezione dei dati generali
            inizio = indiceSezione(righe, "inquadramento", 0);
        }
        if (inizio < 0) {
            return dati;
        }
        int fine = indiceSezione(righe, "storico inquadramento", inizio);
        if (fine < 0) {
            fine = righe.size();
        }

        String corrente = null;
        int righeValore = 0;
        for (int i = inizio + 1; i < fine; i++) {
            List<String> riga = righe.get(i);
            // impaginazione a tabella: una riga di sole etichette seguita da una riga di valori
            if (riga.size() > 1 && riga.stream().allMatch(c -> etichetta(c) != null)
                    && i + 1 < fine && righe.get(i + 1).stream().noneMatch(c -> etichetta(c) != null)) {
                List<String> valori = righe.get(i + 1);
                for (int k = 0; k < riga.size(); k++) {
                    registra(dati, riga.get(k), k < valori.size() ? valori.get(k) : "");
                }
                i++;
                corrente = null;
                continue;
            }
            for (String cella : riga) {
                String campo = etichetta(cella);
                if (campo != null) {
                    registra(dati, cella, "");
                    corrente = campo;
                    righeValore = 0;
                } else if (SEZIONI.contains(norm(cella))) {
                    corrente = null;
                } else if (corrente != null && righeValore < MAX_RIGHE_VALORE) {
                    // un valore puo' andare a capo, ma non oltre: il resto e' altro testo della pagina
                    dati.merge(corrente, cella, (a, b) -> a.isEmpty() ? b : a + " " + b);
                    righeValore++;
                }
            }
        }
        return dati;
    }

    private static void registra(Map<String, String> dati, String etichettaOriginale, String valore) {
        String campo = etichetta(etichettaOriginale);
        dati.putIfAbsent(campo, valore);
        if (ATECO.equals(campo)) {
            // "Codice ateco 2007" oppure, in futuro, "Codice ateco 2025": serve per sapere la classificazione
            dati.putIfAbsent(ETICHETTA_ATECO, etichettaOriginale);
        }
    }

    private static String etichetta(String cella) {
        String n = norm(cella);
        return n.startsWith("codice ateco") ? ATECO : ETICHETTE.get(n);
    }

    private static int indiceSezione(List<List<String>> righe, String sezione, int da) {
        for (int i = da; i < righe.size(); i++) {
            if (righe.get(i).size() == 1 && norm(righe.get(i).get(0)).equals(sezione)) {
                return i;
            }
        }
        return -1;
    }

    static String norm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase().replaceAll("\\s+", " ").trim();
    }
}
