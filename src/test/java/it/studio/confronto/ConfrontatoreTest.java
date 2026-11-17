package it.studio.confronto;

import it.studio.confronto.Modello.AnagraficaAde;
import it.studio.confronto.Modello.EsitoAteco;
import it.studio.confronto.Modello.EsitoDenominazione;
import it.studio.confronto.Modello.PosizioneInps;
import it.studio.confronto.Modello.RigaConfronto;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfrontatoreTest {

    private final Confrontatore confrontatore = new Confrontatore(0.92);

    @Test
    void atecoNormalizzatoASeiCifre() {
        assertEquals("620100", Normalizzatore.ateco("62.01.00 - Produzione di software"));
        assertEquals("620100", Normalizzatore.ateco("62.01"));
        assertEquals("620100", Normalizzatore.ateco("620100"));
        assertEquals("471110", Normalizzatore.ateco(" 47.11.1 "));
        assertNull(Normalizzatore.ateco("non disponibile"));
        assertNull(Normalizzatore.ateco(null));
    }

    @Test
    void denominazioneNormalizzata() {
        assertEquals("ALFA SRL", Normalizzatore.denominazione("Alfa S.r.l."));
        assertEquals("ALFA SRL", Normalizzatore.denominazione("ALFA SOCIETA' A RESPONSABILITA' LIMITATA"));
        assertEquals("ALFA SRL", Normalizzatore.denominazione("ALFA  S R L"));
        assertEquals("ALFA SRLS", Normalizzatore.denominazione("Alfa società a responsabilità limitata semplificata"));
        assertEquals("ROSSI & C SNC", Normalizzatore.denominazione("Rossi & C. S.n.c."));
    }

    @Test
    void tuttoCorrisponde() {
        RigaConfronto r = unaRiga(
                new AnagraficaAde("ALFA S.R.L.", "62.01.00", List.of()),
                new PosizioneInps("1234567890", "Alfa Srl", "620100"));
        assertEquals(EsitoAteco.OK, r.esitoAteco());
        assertEquals(EsitoDenominazione.OK, r.esitoDenominazione());
    }

    @Test
    void personaFisicaConNomeInvertito() {
        RigaConfronto r = unaRiga(
                new AnagraficaAde("ROSSI MARIO", "43.21.01", List.of()),
                new PosizioneInps("1", "Mario Rossi", "43.21.01"));
        assertEquals(EsitoDenominazione.OK, r.esitoDenominazione());
    }

    @Test
    void atecoInpsUgualeAUnSecondario() {
        RigaConfronto r = unaRiga(
                new AnagraficaAde("ALFA SRL", "62.01.00", List.of("47.11.10 - Ipermercati", "63.11.19")),
                new PosizioneInps("1", "ALFA SRL", "63.11.19"));
        assertEquals(EsitoAteco.UGUALE_A_SECONDARIO, r.esitoAteco());
    }

    @Test
    void atecoEDenominazioneDiversi() {
        RigaConfronto r = unaRiga(
                new AnagraficaAde("ALFA SRL", "62.01.00", List.of()),
                new PosizioneInps("1", "BETA COSTRUZIONI SPA", "41.20.00"));
        assertEquals(EsitoAteco.DIVERSO, r.esitoAteco());
        assertEquals(EsitoDenominazione.DIVERSA, r.esitoDenominazione());
    }

    @Test
    void denominazioneSimile() {
        RigaConfronto r = unaRiga(
                new AnagraficaAde("OFFICINA MECCANICA BIANCHI SRL", "25.62.00", List.of()),
                new PosizioneInps("1", "OFFICINA MECCANICA BIANCHI SNC", "25.62.00"));
        assertEquals(EsitoDenominazione.SIMILE, r.esitoDenominazione());
    }

    @Test
    void unaRigaPerOgniMatricola() {
        List<RigaConfronto> righe = confrontatore.confronta("01234567890",
                new AnagraficaAde("ALFA SRL", "62.01.00", List.of()),
                List.of(new PosizioneInps("1", "ALFA SRL", "62.01.00"), new PosizioneInps("2", "ALFA SRL", "41.20.00")),
                null);
        assertEquals(2, righe.size());
        assertEquals(EsitoAteco.OK, righe.get(0).esitoAteco());
        assertEquals(EsitoAteco.DIVERSO, righe.get(1).esitoAteco());
    }

    @Test
    void presenteInUnaSolaFonte() {
        RigaConfronto soloAde = confrontatore.confronta("X",
                new AnagraficaAde("ALFA SRL", "62.01.00", List.of()), List.of(), null).get(0);
        assertTrue(soloAde.note().contains("solo in AdE"));
        assertEquals(EsitoAteco.NON_CONFRONTABILE, soloAde.esitoAteco());

        RigaConfronto soloInps = confrontatore.confronta("X", null,
                List.of(new PosizioneInps("1", "ALFA SRL", "62.01.00")), "Errore lettura AdE: timeout").get(0);
        assertTrue(soloInps.note().contains("Errore lettura AdE"));
        assertTrue(soloInps.note().contains("solo in INPS"));
    }

    @Test
    void excelAndataERitorno() throws Exception {
        Path dir = Files.createTempDirectory("confronto");
        Path report = dir.resolve("confronto.xlsx");
        ExcelIO.scriviReport(report, confrontatore.confronta("01234567890",
                new AnagraficaAde("ALFA SRL", "62.01.00", List.of()),
                List.of(new PosizioneInps("1", "ALFA SRL", "62.01.00")), null));
        // il report ha il codice fiscale in colonna A, quindi si rilegge come elenco clienti
        assertEquals(List.of("01234567890"), ExcelIO.leggiCodiciFiscali(report));
    }

    private RigaConfronto unaRiga(AnagraficaAde ade, PosizioneInps inps) {
        return confrontatore.confronta("01234567890", ade, List.of(inps), null).get(0);
    }
}
