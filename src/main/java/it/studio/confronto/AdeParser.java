package it.studio.confronto;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Estrae i dati dalla pagina "Dati anagrafici" del cassetto fiscale (Consultazioni):
 * sezioni "Dati principali" e "Dati relativi alla partita IVA e attività".
 *
 * Esempio del testo della pagina:
 *   ALFA SOCIETA' A RESPONSABILITA' LIMITATA Codice fiscale 01234567890
 *   Natura giuridica SOCIETA' A RESPONSABILITA' LIMITATA (02)
 *   Sede legale in VIA ROMA 1 00100 ROMA (RM) a decorrere dal 16/4/2018 (fonte del dato C.C.I.A.A.)
 *   Partita IVA 01234567890 attribuita il 22/1/2018 ... Stato ATTIVA
 *   Attività esercitata PRODUZIONE CINEMATOGRAFICA, VIDEO E PROGRAMMI TV (591100) a decorrere dal 1/11/2020
 */
public final class AdeParser {

    /** Separa, nel testo salvato, la pagina "Altre attività" da quella dei dati anagrafici. */
    public static final String PAGINA_ALTRE_ATTIVITA = "=== PAGINA ALTRE ATTIVITA ===";

    public static final String DENOMINAZIONE = "Denominazione";
    public static final String ATECO = "ATECO";

    /** Campi nell'ordine in cui finiscono nel file di uscita. */
    public static final List<String> CAMPI = List.of(DENOMINAZIONE, "Natura giuridica", "Sede legale",
            "Domicilio fiscale", "Luogo di esercizio", "Partita IVA", "Data attribuzione P.IVA", "Stato P.IVA",
            "Attività esercitata", ATECO, "Decorrenza attività", "Altri ATECO");

    private static final String FINE_VALORE = "(?= a decorrere| Sede legale| Domicilio fiscale| Luogo di esercizio"
            + "| precedenti | dati del rappresentante| Dati relativi|$)";
    private static final String DATA = "(\\d{1,2}/\\d{1,2}/\\d{4})";
    private static final Pattern CODICE = Pattern.compile("\\((\\d{6})\\)");

    private AdeParser() {
    }

    public static Map<String, String> analizza(String testoCompleto) {
        String[] pagine = testoCompleto.split(Pattern.quote(PAGINA_ALTRE_ATTIVITA), 2);
        String t = pagine[0].replaceAll("\\s+", " ").trim();
        Map<String, String> dati = new LinkedHashMap<>();
        for (String campo : CAMPI) {
            dati.put(campo, "");
        }

        dati.put(DENOMINAZIONE, primo(t, "Dati principali (.+?) Codice fiscale [A-Z0-9]{11,16}\\b"));
        dati.put("Natura giuridica", primo(t, "Natura giuridica (.+?)" + FINE_VALORE));
        dati.put("Sede legale", primo(t, "Sede legale in (.+?)" + FINE_VALORE));
        dati.put("Domicilio fiscale", primo(t, "Domicilio fiscale in (.+?)" + FINE_VALORE));
        dati.put("Luogo di esercizio", primo(t, "Luogo di esercizio in (.+?)" + FINE_VALORE));

        // sezione della partita IVA: fino alle attivita' precedenti (cessate) o agli uffici
        String iva = primo(t, "Dati relativi alla partita IVA(.*?)(?= precedenti attivit| Uffici associati|$)");
        dati.put("Partita IVA", primo(iva, "Partita IVA (\\d{11})"));
        dati.put("Data attribuzione P.IVA", primo(iva, "attribuita il " + DATA));
        dati.put("Stato P.IVA", primo(iva, "Stato ([A-ZÀ-Ý']+(?: [A-ZÀ-Ý']+)*)\\b"));
        Matcher attivita = Pattern.compile("Attivit\\S+ esercitata (.+?) ?\\((\\d{6})\\)(?: a? ?decorrere dal " + DATA + ")?")
                .matcher(iva);
        if (attivita.find()) {
            dati.put("Attività esercitata", attivita.group(1).trim());
            dati.put(ATECO, attivita.group(2));
            dati.put("Decorrenza attività", attivita.group(3) == null ? "" : attivita.group(3));
        }

        // altri codici: quelli ulteriori nella sezione della partita IVA e quelli della pagina "Altre attività"
        Set<String> altri = new LinkedHashSet<>();
        raccogliCodici(iva, altri);
        if (pagine.length > 1) {
            String altre = pagine[1].replaceAll("\\s+", " ");
            raccogliCodici(primo(altre, "^(.*?)(?= precedenti |$)"), altri);
        }
        altri.remove(dati.get(ATECO));
        if (dati.get(ATECO).isEmpty() && !altri.isEmpty()) {
            String primoCodice = altri.iterator().next();
            dati.put(ATECO, primoCodice);
            altri.remove(primoCodice);
        }
        dati.put("Altri ATECO", String.join(", ", altri));
        return dati;
    }

    private static void raccogliCodici(String testo, Set<String> codici) {
        Matcher m = CODICE.matcher(testo);
        while (m.find()) {
            codici.add(m.group(1));
        }
    }

    private static String primo(String testo, String regex) {
        Matcher m = Pattern.compile(regex).matcher(testo);
        return m.find() ? m.group(1).trim() : "";
    }
}
