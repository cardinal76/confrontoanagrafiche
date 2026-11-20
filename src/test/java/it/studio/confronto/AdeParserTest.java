package it.studio.confronto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdeParserTest {

    @Test
    void societaConAttivitaPrevalenteESecondaria() {
        AdeParser.Anagrafica a = AdeParser.analizza(String.join("\n",
                "Cassetto fiscale", "Dati anagrafici",
                "Codice fiscale", "01234567890",
                "Denominazione", "AZIENDA DI PROVA S.R.L.",
                "Data di costituzione 07/06/2003", "CAP 00100", "Telefono 061234567",
                "Attività",
                "Codice attività\tDescrizione\tTipo",
                "62.01.00\tPRODUZIONE DI SOFTWARE\tPrevalente",
                "47.11.10\tIPERMERCATI\tSecondaria"));
        assertEquals("AZIENDA DI PROVA S.R.L.", a.denominazione());
        assertEquals(List.of("62.01.00", "47.11.10"), a.ateco());
    }

    @Test
    void personaFisicaEValoriSullaStessaRiga() {
        AdeParser.Anagrafica a = AdeParser.analizza(String.join("\n",
                "Cognome: ROSSI", "Nome: MARIO", "Data di nascita: 01/01/1980",
                "Codice ATECO: 432101 - INSTALLAZIONE DI IMPIANTI ELETTRICI"));
        assertEquals("ROSSI MARIO", a.denominazione());
        assertEquals(List.of("432101"), a.ateco());
    }

    @Test
    void nessunDatoRiconosciuto() {
        AdeParser.Anagrafica a = AdeParser.analizza("Sessione scaduta\nAccedi di nuovo 12/12/2026 123456");
        assertEquals("", a.denominazione());
        assertEquals(List.of(), a.ateco());
    }
}
