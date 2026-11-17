package it.studio.confronto;

import it.studio.confronto.Modello.AnagraficaAde;
import it.studio.confronto.Modello.EsitoAteco;
import it.studio.confronto.Modello.EsitoDenominazione;
import it.studio.confronto.Modello.PosizioneInps;
import it.studio.confronto.Modello.RigaConfronto;
import org.apache.commons.text.similarity.JaroWinklerSimilarity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Confrontatore {

    private final double sogliaSimilarita;
    private final JaroWinklerSimilarity jaroWinkler = new JaroWinklerSimilarity();

    public Confrontatore(double sogliaSimilarita) {
        this.sogliaSimilarita = sogliaSimilarita;
    }

    /**
     * Produce una riga per ogni matricola INPS del codice fiscale.
     * Se manca una delle due fonti produce una sola riga con la nota relativa.
     */
    public List<RigaConfronto> confronta(String cf, AnagraficaAde ade, List<PosizioneInps> inps, String note) {
        List<RigaConfronto> righe = new ArrayList<>();
        String secondari = ade == null ? "" : String.join(", ", ade.atecoSecondari());

        if (inps == null || inps.isEmpty()) {
            String nota = unisci(note, ade == null ? "Assente in entrambe le fonti" : "Presente solo in AdE");
            righe.add(new RigaConfronto(cf, "",
                    ade == null ? "" : ade.denominazione(), "", null, EsitoDenominazione.NON_CONFRONTABILE,
                    ade == null ? "" : ade.atecoPrevalente(), secondari, "", EsitoAteco.NON_CONFRONTABILE, nota));
            return righe;
        }

        for (PosizioneInps p : inps) {
            if (ade == null) {
                righe.add(new RigaConfronto(cf, p.matricola(), "", p.denominazione(), null,
                        EsitoDenominazione.NON_CONFRONTABILE, "", "", p.ateco(),
                        EsitoAteco.NON_CONFRONTABILE, unisci(note, "Presente solo in INPS")));
                continue;
            }
            Double similarita = similarita(ade.denominazione(), p.denominazione());
            righe.add(new RigaConfronto(cf, p.matricola(), ade.denominazione(), p.denominazione(), similarita,
                    esitoDenominazione(ade.denominazione(), p.denominazione(), similarita),
                    ade.atecoPrevalente(), secondari, p.ateco(), esitoAteco(ade, p.ateco()), note));
        }
        return righe;
    }

    EsitoAteco esitoAteco(AnagraficaAde ade, String atecoInps) {
        String inps = Normalizzatore.ateco(atecoInps);
        String prevalente = Normalizzatore.ateco(ade.atecoPrevalente());
        if (inps == null || prevalente == null) {
            return EsitoAteco.NON_CONFRONTABILE;
        }
        if (inps.equals(prevalente)) {
            return EsitoAteco.OK;
        }
        boolean secondario = ade.atecoSecondari().stream()
                .map(Normalizzatore::ateco)
                .filter(Objects::nonNull)
                .anyMatch(inps::equals);
        return secondario ? EsitoAteco.UGUALE_A_SECONDARIO : EsitoAteco.DIVERSO;
    }

    private Double similarita(String denomAde, String denomInps) {
        String a = Normalizzatore.denominazione(denomAde);
        String b = Normalizzatore.denominazione(denomInps);
        if (a.isEmpty() || b.isEmpty()) {
            return null;
        }
        double diretta = jaroWinkler.apply(a, b);
        double ordinata = jaroWinkler.apply(Normalizzatore.paroleOrdinate(a), Normalizzatore.paroleOrdinate(b));
        return Math.max(diretta, ordinata);
    }

    private EsitoDenominazione esitoDenominazione(String denomAde, String denomInps, Double similarita) {
        if (similarita == null) {
            return EsitoDenominazione.NON_CONFRONTABILE;
        }
        String a = Normalizzatore.denominazione(denomAde);
        String b = Normalizzatore.denominazione(denomInps);
        if (a.equals(b) || Normalizzatore.paroleOrdinate(a).equals(Normalizzatore.paroleOrdinate(b))) {
            return EsitoDenominazione.OK;
        }
        return similarita >= sogliaSimilarita ? EsitoDenominazione.SIMILE : EsitoDenominazione.DIVERSA;
    }

    private static String unisci(String a, String b) {
        return a == null || a.isBlank() ? b : a + "; " + b;
    }
}
