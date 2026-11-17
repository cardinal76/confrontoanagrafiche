package it.studio.confronto;

import java.util.List;

public final class Modello {

    private Modello() {
    }

    /** Dati letti dall'anagrafica del cassetto fiscale. */
    public record AnagraficaAde(String denominazione, String atecoPrevalente, List<String> atecoSecondari) {
    }

    /** Una posizione contributiva (matricola) letta dal fascicolo previdenziale. */
    public record PosizioneInps(String matricola, String denominazione, String ateco) {
    }

    public enum EsitoAteco {
        OK, UGUALE_A_SECONDARIO, DIVERSO, NON_CONFRONTABILE
    }

    public enum EsitoDenominazione {
        OK, SIMILE, DIVERSA, NON_CONFRONTABILE
    }

    public record RigaConfronto(
            String codiceFiscale,
            String matricola,
            String denominazioneAde,
            String denominazioneInps,
            Double similarita,
            EsitoDenominazione esitoDenominazione,
            String atecoAde,
            String atecoSecondariAde,
            String atecoInps,
            EsitoAteco esitoAteco,
            String note) {
    }
}
