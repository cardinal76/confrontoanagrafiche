package it.studio.confronto;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class Normalizzatore {

    private static final Pattern CODICE_ATECO = Pattern.compile("\\d{2}(?:\\.?\\d{1,2}){0,2}");

    /** Forme giuridiche per esteso o spaziate, ricondotte alla sigla. L'ordine conta: prima le piu' lunghe. */
    private static final Map<Pattern, String> FORME_GIURIDICHE = new LinkedHashMap<>();

    static {
        forma("SOCIETA A RESPONSABILITA LIMITATA SEMPLIFICATA", "SRLS");
        forma("SOCIETA A RESPONSABILITA LIMITATA", "SRL");
        forma("SOCIETA PER AZIONI", "SPA");
        forma("SOCIETA IN NOME COLLETTIVO", "SNC");
        forma("SOCIETA IN ACCOMANDITA SEMPLICE", "SAS");
        forma("SOCIETA IN ACCOMANDITA PER AZIONI", "SAPA");
        forma("SOCIETA SEMPLICE", "SS");
        forma("SOCIETA COOPERATIVA", "SOC COOP");
        forma("S R L S", "SRLS");
        forma("S R L", "SRL");
        forma("S P A", "SPA");
        forma("S N C", "SNC");
        forma("S A S", "SAS");
    }

    private Normalizzatore() {
    }

    private static void forma(String estesa, String sigla) {
        FORME_GIURIDICHE.put(Pattern.compile("\\b" + estesa + "\\b"), sigla);
    }

    /**
     * Estrae il primo codice ATECO da un testo (es. "62.01.00 - Produzione di software")
     * e lo porta a 6 cifre senza punti. Restituisce null se non trova un codice.
     */
    public static String ateco(String testo) {
        if (testo == null) {
            return null;
        }
        Matcher m = CODICE_ATECO.matcher(testo);
        if (!m.find()) {
            return null;
        }
        String cifre = m.group().replace(".", "");
        return (cifre + "000000").substring(0, 6);
    }

    /** Maiuscole, senza accenti, apostrofi e punteggiatura, con le forme giuridiche ricondotte alla sigla. */
    public static String denominazione(String testo) {
        if (testo == null) {
            return "";
        }
        String s = Normalizer.normalize(testo, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.toUpperCase().replaceAll("['’`.]", "");
        s = s.replaceAll("[^A-Z0-9&]", " ").replaceAll("\\s+", " ").trim();
        for (Map.Entry<Pattern, String> f : FORME_GIURIDICHE.entrySet()) {
            s = f.getKey().matcher(s).replaceAll(f.getValue());
        }
        return s;
    }

    /** Stesse parole in ordine alfabetico: rende uguali "ROSSI MARIO" e "MARIO ROSSI". */
    public static String paroleOrdinate(String denominazioneNormalizzata) {
        return Arrays.stream(denominazioneNormalizzata.split(" ")).sorted().collect(Collectors.joining(" "));
    }
}
