package it.studio.confronto;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdeParserTest {

    private static final String PAGINA = String.join("\n",
            "Cassetto fiscale - Consultazioni",
            "sei nel cassetto delegato di 01234567890 - ALFA SOCIETA' A RESPONSABILITA' LIMITATA",
            "Anagrafica", "Dati anagrafici", "Altre attività", "Altri luoghi di esercizio", "Rappresentanti",
            "Dati anagrafici", "Dati principali",
            "ALFA SOCIETA' A RESPONSABILITA' LIMITATA Codice fiscale 01234567890",
            "Natura giuridica SOCIETA' A RESPONSABILITA' LIMITATA (02)",
            "Sede legale in VIA ROMA 12 00186 ROMA (RM)",
            "a decorrere dal 16/4/2018 (fonte del dato C.C.I.A.A.)",
            "Domicilio fiscale in VIA MILANO 3 00186 ROMA (RM)",
            "a decorrere dal 16/4/2018 (fonte del dato C.C.I.A.A.)",
            "Luogo di esercizio in VIA NAPOLI 7 00186 ROMA (RM)",
            "a decorrere dal 16/4/2018 (fonte del dato C.C.I.A.A.)",
            "precedenti denominazioni / nature giuridiche", "precedenti sedi legali", "precedenti domicili fiscali",
            "precedenti luoghi di esercizio", "dati del rappresentante",
            "Dati relativi alla partita IVA e attività",
            "Partita IVA 01234567890 attribuita il 22/1/2018 - autorizzata ad effettuare operazioni intracomunitarie",
            "Stato ATTIVA",
            "Attività esercitata PRODUZIONE CINEMATOGRAFICA, VIDEO E PROGRAMMI TV (591100)",
            "a decorrere dal 1/11/2020 (fonte del dato C.C.I.A.A.)",
            "E' possibile generare il QR code(in formato PDF o immagine) contenente i dati relativi alla Partita IVA 01234567890",
            "QR code pdf", "QR code gif",
            "precedenti attività esercitate",
            "Uffici associati al domicilio fiscale",
            "UFF. TERR. DI ROMA 1 - TRASTEVERE (TJN)", "D.P. I DI ROMA (TK2)", "DR DEL LAZIO (913)");

    @Test
    void datiPrincipaliEPartitaIva() {
        Map<String, String> d = AdeParser.analizza(PAGINA);
        assertEquals("ALFA SOCIETA' A RESPONSABILITA' LIMITATA", d.get("Denominazione"));
        assertEquals("SOCIETA' A RESPONSABILITA' LIMITATA (02)", d.get("Natura giuridica"));
        assertEquals("VIA ROMA 12 00186 ROMA (RM)", d.get("Sede legale"));
        assertEquals("VIA MILANO 3 00186 ROMA (RM)", d.get("Domicilio fiscale"));
        assertEquals("VIA NAPOLI 7 00186 ROMA (RM)", d.get("Luogo di esercizio"));
        assertEquals("01234567890", d.get("Partita IVA"));
        assertEquals("22/1/2018", d.get("Data attribuzione P.IVA"));
        assertEquals("ATTIVA", d.get("Stato P.IVA"));
        assertEquals("PRODUZIONE CINEMATOGRAFICA, VIDEO E PROGRAMMI TV", d.get("Attività esercitata"));
        assertEquals("591100", d.get("ATECO"));
        assertEquals("1/11/2020", d.get("Decorrenza attività"));
        assertEquals("", d.get("Altri ATECO"));
    }

    @Test
    void codiciSecondariDallaPaginaAltreAttivita() {
        Map<String, String> d = AdeParser.analizza(PAGINA + "\n" + AdeParser.PAGINA_ALTRE_ATTIVITA + "\n"
                + "Altre attività\nAttività COMMERCIO AL DETTAGLIO (471110) a decorrere dal 1/1/2021\n"
                + "Attività PRODUZIONE CINEMATOGRAFICA (591100)\nprecedenti attività\nVECCHIA ATTIVITA (999999)");
        assertEquals("591100", d.get("ATECO"));
        assertEquals("471110", d.get("Altri ATECO"));
    }

    @Test
    void paginaNonRiconosciuta() {
        Map<String, String> d = AdeParser.analizza("Sessione scaduta\nAccedi di nuovo 12/12/2026 (123456)");
        assertEquals("", d.get("Denominazione"));
        assertEquals("", d.get("ATECO"));
        assertEquals(AdeParser.CAMPI.size(), d.size());
    }
}
