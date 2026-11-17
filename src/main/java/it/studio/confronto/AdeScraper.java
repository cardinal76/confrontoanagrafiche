package it.studio.confronto;

import com.microsoft.playwright.Page;
import it.studio.confronto.Modello.AnagraficaAde;

import java.util.List;

/**
 * Legge l'anagrafica del cassetto fiscale di un delegante.
 * Il percorso (URL e selettori) sta in config.properties: e' un'ipotesi di flusso
 * da adattare alle pagine reali del portale.
 */
public class AdeScraper {

    private final Page page;
    private final Config cfg;

    public AdeScraper(Page page, Config cfg) {
        this.page = page;
        this.cfg = cfg;
    }

    public AnagraficaAde leggi(String codiceFiscale) {
        page.navigate(cfg.req("ade.url.selezione"));
        page.fill(cfg.req("ade.sel.inputCf"), codiceFiscale);
        page.click(cfg.req("ade.sel.conferma"));

        String urlAnagrafica = cfg.opt("ade.url.anagrafica");
        if (urlAnagrafica.isEmpty()) {
            page.click(cfg.req("ade.sel.linkAnagrafica"));
        } else {
            page.navigate(urlAnagrafica);
        }

        String denominazione = page.locator(cfg.req("ade.sel.denominazione")).first().innerText().trim();
        String prevalente = page.locator(cfg.req("ade.sel.atecoPrevalente")).first().innerText().trim();
        String selSecondari = cfg.opt("ade.sel.atecoSecondari");
        List<String> secondari = selSecondari.isEmpty()
                ? List.of()
                : page.locator(selSecondari).allInnerTexts().stream().map(String::trim).toList();
        return new AnagraficaAde(denominazione, prevalente, secondari);
    }
}
