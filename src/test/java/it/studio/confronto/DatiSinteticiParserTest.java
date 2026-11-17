package it.studio.confronto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatiSinteticiParserTest {

    private static final String TESTO = String.join("\n",
            "MENU' PRINCIPALE", "Dati sintetici", "Gestione", "qualcosa prima della finestra",
            "Dati Sintetici (9200000001 - AZIENDA DI PROVA ...)",
            "Dati Generali",
            "Codice Fiscale", "01234567890",
            "Posizione Contributiva", "9200000001",
            "Gestione", "Aziende DM",
            "Denominazione", "AZIENDA DI PROVA",
            "Tipologia Posizione", "Datore Lavoro",
            "Indirizzo PEC", "ESEMPIO@PEC.IT",
            "Inizio attività con dipendenti", "07/06/2003",
            "Stato Azienda", "Riattivata",
            "Data Variazione Stato", "08/02/2017",
            "Sede Competenza", "9200 - VITERBO",
            "Indirizzo", "VIA DI PROVA 8, 00100 ROMA",
            "Inquadramento",
            "Attivitá Dichiarata", "COMPAGNIA DI DANZA",
            "Accentramento", "A1",
            "Descrizione Accentramento", "POSIZIONE SINGOLA, SENZA UNITA' OPERATIVE, NON AUTORIZZATA",
            "ALL'ACCENTRAMENTO CONTRIBUTIVO",
            "Codice Statistico Contributivo", "11808",
            "Codice ISTAT attivitá economica", "932999",
            "Codice ateco 2007", "900109",
            "Descrizione Inquadramento",
            "Storico Inquadramento",
            "Excel", "PDF", "Stampa", "Codice Fiscale", "valore dello storico");

    @Test
    void leggeDatiGeneraliEInquadramento() {
        Map<String, String> d = DatiSinteticiParser.analizza(TESTO);
        assertEquals("01234567890", d.get("Codice Fiscale"));
        assertEquals("9200000001", d.get("Posizione Contributiva"));
        assertEquals("Aziende DM", d.get("Gestione"));
        assertEquals("AZIENDA DI PROVA", d.get("Denominazione"));
        assertEquals("VIA DI PROVA 8, 00100 ROMA", d.get("Indirizzo"));
        assertEquals("COMPAGNIA DI DANZA", d.get("Attività Dichiarata"));
        assertEquals("POSIZIONE SINGOLA, SENZA UNITA' OPERATIVE, NON AUTORIZZATA ALL'ACCENTRAMENTO CONTRIBUTIVO",
                d.get("Descrizione Accentramento"));
        assertEquals("11808", d.get("Codice Statistico Contributivo"));
        assertEquals("932999", d.get("Codice ISTAT attività economica"));
        assertEquals("900109", d.get("Codice ATECO"));
        assertEquals("Codice ateco 2007", d.get("Etichetta ATECO"));
        assertEquals("", d.get("Descrizione Inquadramento"));
    }

    @Test
    void leggeImpaginazioneATabella() {
        String testo = String.join("\n", "Dati Generali",
                "Codice Fiscale\tPosizione Contributiva\tGestione",
                "01234567890\t9200000001\tAziende DM",
                "Inquadramento",
                "Codice Statistico Contributivo\tCodice ateco 2025",
                "11808\t900109");
        Map<String, String> d = DatiSinteticiParser.analizza(testo);
        assertEquals("9200000001", d.get("Posizione Contributiva"));
        assertEquals("Aziende DM", d.get("Gestione"));
        assertEquals("900109", d.get("Codice ATECO"));
        assertEquals("Codice ateco 2025", d.get("Etichetta ATECO"));
    }

    @Test
    void finestraVuotaOAssente() {
        assertTrue(DatiSinteticiParser.analizza("MENU' PRINCIPALE\nDati sintetici").isEmpty());
        Map<String, String> soloEtichette = DatiSinteticiParser.analizza(
                "Dati Generali\nCodice Fiscale\nPosizione Contributiva\nInquadramento\nCodice Statistico Contributivo");
        assertEquals("", soloEtichette.get("Posizione Contributiva"));
        assertEquals("", soloEtichette.get("Codice Statistico Contributivo"));
    }
}
