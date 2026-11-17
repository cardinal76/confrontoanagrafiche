package it.studio.confronto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SchedaPosizioneTest {

    @Test
    void leggeLaSchedaDellaNuovaInterfaccia() {
        String testo = String.join("\n",
                "Home del servizio", "/", "Posizione Contributiva",
                "Posizione Contributiva",
                "N.9200000001", "AZIENDA DI PROVA",
                "Nascondi Dettaglio", "Deseleziona Posizione",
                "Posizione Contributiva", "9200000001",
                "Gestione", "Aziende DM",
                "Indirizzo", "VIA DI PROVA , 8 - 00100 ROMA (RM)",
                "Sede", "9200 VITERBO",
                "Stato", "Riattivata",
                "Soggetto Contribuente", "01234567890", "AZIENDA DI PROVA",
                "Indirizzo", "VIA DI PROVA , 8 - 00100 ROMA (RM)",
                "E-mail", "PROVA@PEC.IT",
                "Gestione", "Aziende DM");
        Map<String, String> d = InpsNuovoDownloader.schedaPosizione(testo);
        assertEquals("9200000001", d.get("Posizione Contributiva"));
        assertEquals("01234567890", d.get("Codice Fiscale"));
        assertEquals("AZIENDA DI PROVA", d.get("Denominazione"));
        assertEquals("Aziende DM", d.get("Gestione"));
        assertEquals("Riattivata", d.get("Stato Azienda"));
        assertEquals("9200 VITERBO", d.get("Sede Competenza"));
        assertEquals("VIA DI PROVA , 8 - 00100 ROMA (RM)", d.get("Indirizzo"));
        assertEquals("PROVA@PEC.IT", d.get("Indirizzo PEC"));
    }

    @Test
    void inquadramentoSenzaDatiGenerali() {
        Map<String, String> d = DatiSinteticiParser.analizza(String.join("\n",
                "Menu", "Dati sintetici", "Inquadramento",
                "Codice Statistico Contributivo", "11808",
                "Codice ateco 2007", "900109",
                "Descrizione Inquadramento",
                "Il tuo parere è importante", "altro testo", "ancora testo", "testo che non deve entrare", "fine"));
        assertEquals("11808", d.get("Codice Statistico Contributivo"));
        assertEquals("900109", d.get("Codice ATECO"));
        assertEquals("Il tuo parere è importante altro testo ancora testo", d.get("Descrizione Inquadramento"));
    }
}
