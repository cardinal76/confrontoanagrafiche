package it.studio.confronto;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scaricatore per la VECCHIA interfaccia del Cassetto Previdenziale Contribuente.
 *
 * Percorso: Ricerca avanzata (finestra) -> codice fiscale -> posizione contributiva -> menu Dati Sintetici.
 */
public class InpsDownloader extends InpsBase {

    private static final String VISTA_SOGGETTI = "per visualizzare le relative";
    private static final String VISTA_POSIZIONI = "per selezionarla";
    private static final String VOCE_MENU = "Dati Sintetici";
    private static final String GRUPPO_MENU = "Dati sintetici";

    static final String FILE_APRI_RICERCA = "inps-apri-ricerca.txt";

    /** Registra nella pagina il percorso CSS dell'ultimo elemento cliccato dall'utente. */
    private static final String REGISTRA_CLIC = """
            () => {
              window.__clicAppreso = null;
              document.addEventListener('click', e => {
                let el = e.target;
                const parti = [];
                while (el && el.nodeType === 1 && el !== document.body) {
                  if (el.id) { parti.unshift('#' + CSS.escape(el.id)); break; }
                  let n = 1, s = el;
                  while ((s = s.previousElementSibling)) { if (s.tagName === el.tagName) n++; }
                  parti.unshift(el.tagName.toLowerCase() + ':nth-of-type(' + n + ')');
                  el = el.parentElement;
                }
                if (!parti[0].startsWith('#')) { parti.unshift('body'); }
                window.__clicAppreso = parti.join(' > ');
              }, true);
            }
            """;

    private String cfInVista;

    public InpsDownloader(Page page, Config cfg, Path dir, Scanner tastiera) {
        super(page, cfg, dir, tastiera);
    }

    // ------------------------------------------------------------------ elenco dei soggetti

    @Override
    protected Map<String, String> soggettiDalPortale() {
        apriRicerca();
        mostraElencoSoggetti();
        massimoPerPagina();

        Map<String, String> trovati = new LinkedHashMap<>();
        for (int pagina = 0; pagina < 1000; pagina++) {
            int prima = trovati.size();
            for (Locator riga : visibili(app().locator("tbody tr")).all()) {
                List<String> celle = riga.locator("td").allInnerTexts();
                if (celle.size() >= 2 && !celle.get(0).isBlank()) {
                    trovati.putIfAbsent(celle.get(0).trim(), celle.get(1).trim());
                }
            }
            Locator successivo = visibili(app().getByText("Successivo", new Frame.GetByTextOptions().setExact(true)));
            if (successivo.count() == 0 || (pagina > 0 && trovati.size() == prima)) {
                break;
            }
            boolean ultima = Boolean.TRUE.equals(successivo.first().evaluate(
                    "e => !!e.closest('.disabled, [disabled], [aria-disabled=true]')"));
            if (ultima) {
                break;
            }
            successivo.first().click();
            page.waitForTimeout(400);
        }

        Integer attesi = totaleDichiarato();
        if (attesi != null && attesi != trovati.size()) {
            log("ATTENZIONE: il portale dichiara " + attesi + " soggetti, ne ho letti " + trovati.size());
            diagnostica("elenco-incompleto");
        }
        return trovati;
    }

    private Integer totaleDichiarato() {
        Locator info = visibili(app().getByText(Pattern.compile("di\\s+[\\d.]+\\s+elementi")));
        if (info.count() == 0) {
            return null;
        }
        Matcher m = Pattern.compile("di\\s+([\\d.]+)\\s+elementi").matcher(info.first().innerText());
        return m.find() ? Integer.valueOf(m.group(1).replace(".", "")) : null;
    }

    // ------------------------------------------------------------------ posizioni di un soggetto

    @Override
    protected List<Map<String, String>> posizioni(String cf) {
        if (!(cf.equals(cfInVista) && visibile(VISTA_POSIZIONI))) {
            apriRicerca();
            mostraElencoSoggetti();
            Locator cerca = visibili(app().locator("input[type='search'], label:has-text('Cerca') input")).first();
            cerca.fill(cf);
            Locator link = visibili(app().locator("td").getByText(cf, new Locator.GetByTextOptions().setExact(true)));
            link.first().click();
            attendi(() -> visibile(VISTA_POSIZIONI), 15000, "elenco delle posizioni di " + cf);
            cfInVista = cf;
            massimoPerPagina();
        }

        List<String> testate = visibili(app().locator("th")).allInnerTexts().stream().map(String::trim).toList();
        int iPos = colonna(testate, "posizione", 2);
        int iGest = colonna(testate, "gestione", 1);
        int iDescr = colonna(testate, "descrizione", 3);
        int iSede = colonna(testate, "sede", 4);
        int iStato = colonna(testate, "stato", 5);

        List<Map<String, String>> posizioni = new ArrayList<>();
        for (Locator riga : visibili(app().locator("tbody tr")).all()) {
            List<String> celle = riga.locator("td").allInnerTexts().stream().map(String::trim).toList();
            if (celle.size() <= Math.max(iPos, iStato) || celle.get(iPos).isEmpty()) {
                continue;
            }
            Map<String, String> p = new LinkedHashMap<>();
            p.put("posizione", celle.get(iPos));
            p.put("gestione", celle.get(iGest));
            p.put("descrizione", celle.get(iDescr));
            p.put("sede", celle.get(iSede));
            p.put("stato", celle.get(iStato));
            posizioni.add(p);
        }
        return posizioni;
    }

    static int colonna(List<String> testate, String parola, int predefinita) {
        for (int i = 0; i < testate.size(); i++) {
            if (testate.get(i).toLowerCase().contains(parola)) {
                return i;
            }
        }
        return predefinita;
    }

    // ------------------------------------------------------------------ dati sintetici

    /** Seleziona la posizione e legge i dati sintetici; se la finestra resta vuota la chiude e riprova. */
    @Override
    protected Map<String, String> leggiPosizione(String cf, String posizione) {
        Map<String, String> migliore = Map.of();
        for (int tentativo = 1; tentativo <= tentativi; tentativo++) {
            posizioni(cf);
            visibili(app().locator("td").getByText(posizione, new Locator.GetByTextOptions().setExact(true)))
                    .first().click();
            cfInVista = null;
            // il portale ha bisogno di un attimo per registrare la posizione scelta, altrimenti
            // la finestra risponde "Ticket non valido"; ad ogni nuovo tentativo si aspetta di piu'
            page.waitForTimeout(pausaSelezioneMs * tentativo);
            apriDatiSintetici();

            long scadenza = System.currentTimeMillis() + attesaMs;
            while (System.currentTimeMillis() < scadenza) {
                if (errorePortale()) {
                    log("  " + posizione + ": la finestra mostra un messaggio di errore del portale");
                    break;
                }
                Map<String, String> dati = datiSinteticiOvunque(posizione, true);
                if (!dati.getOrDefault("Denominazione", "").isEmpty()) {
                    migliore = dati;
                    if (haInquadramento(dati)) {
                        break;
                    }
                }
                page.waitForTimeout(400);
            }
            chiudiFinestra();
            if (haInquadramento(migliore)) {
                return migliore;
            }
            log("  " + posizione + ": dati sintetici non caricati, tentativo " + tentativo + " di " + tentativi);
        }
        if (migliore.isEmpty()) {
            throw new IllegalArgumentException("Dati sintetici vuoti per la posizione " + posizione);
        }
        return migliore;
    }

    private void apriDatiSintetici() {
        Frame f = app();
        Frame.GetByTextOptions esatto = new Frame.GetByTextOptions().setExact(true);
        if (!attendiSenzaErrore(() -> !visibile(VISTA_POSIZIONI), 5000)) {
            // la Ricerca avanzata non si e' chiusa da sola dopo la scelta della posizione
            visibili(f.getByText("Chiudi")).last().click();
            attendi(() -> !visibile(VISTA_POSIZIONI), 5000, "chiusura della Ricerca avanzata");
        }
        if (visibili(f.getByText(VOCE_MENU, esatto)).count() == 0) {
            visibili(f.getByText(GRUPPO_MENU, esatto)).first().click();
        }
        visibili(f.getByText(VOCE_MENU, esatto)).first().click();
    }

    private boolean finestraAperta() {
        for (Frame f : page.frames()) {
            try {
                if (visibili(f.getByText("Dati Generali", new Frame.GetByTextOptions().setExact(true))).count() > 0
                        || visibili(f.getByText("Dati Sintetici (")).count() > 0) {
                    return true;
                }
            } catch (RuntimeException ignorata) {
                // frame sparito nel frattempo
            }
        }
        return false;
    }

    private void chiudiFinestra() {
        if (!finestraAperta()) {
            return;
        }
        List<String> selettori = new ArrayList<>(elenco(cfg.opt("inps.sel.chiudiFinestra")));
        selettori.addAll(List.of(".jsPanel-btn-close", ".ui-dialog-titlebar-close", ".k-window-titlebar .k-i-close",
                ".fa-times", ".fa-close", ".fa-xmark", "[aria-label='Close']", "[aria-label='Chiudi']",
                "[title='Chiudi']", "[title='Close']"));
        for (String sel : selettori) {
            for (Frame f : page.frames()) {
                try {
                    Locator x = visibili(f.locator(sel));
                    if (x.count() > 0) {
                        x.last().click(new Locator.ClickOptions().setTimeout(2000));
                        page.waitForTimeout(400);
                        if (!finestraAperta()) {
                            return;
                        }
                    }
                } catch (RuntimeException ignorata) {
                    // selettore non adatto: si prova il successivo
                }
            }
        }
        log("  Non trovo il pulsante di chiusura della finestra: ricarico la pagina");
        diagnostica("chiusura-finestra");
        ripristina();
    }

    // ------------------------------------------------------------------ ricerca avanzata

    private void apriRicerca() {
        if (ricercaAperta()) {
            return;
        }
        // 1) selettori noti: quello imparato in una esecuzione precedente, quello in configurazione, i piu' comuni
        List<String> selettori = new ArrayList<>();
        Path appreso = dir.resolve(FILE_APRI_RICERCA);
        try {
            if (Files.exists(appreso)) {
                selettori.add(Files.readString(appreso, StandardCharsets.UTF_8).trim());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        selettori.addAll(elenco(cfg.opt("inps.sel.apriRicerca")));
        selettori.addAll(List.of(".fa-search", ".fa-magnifying-glass", ".glyphicon-search", "[class*='search' i]",
                "[title*='icerca' i]", "[aria-label*='icerca' i]", "[onclick*='icerca' i]", "[href*='icerca' i]",
                "[data-target*='icerca' i]", "text=Ricerca avanzata"));
        List<Locator> candidati = new ArrayList<>();
        for (String sel : selettori) {
            if (!sel.isEmpty()) {
                candidati.add(app().locator(sel));
            }
        }
        // 2) gli elementi cliccabili subito dopo il campo "Posizione contributiva" del menu (la lente)
        candidati.add(app().locator("input[placeholder*='osizione' i]").first()
                .locator("xpath=following::*[self::button or self::a or self::i or self::span][position() <= 4]"));
        for (Locator gruppo : candidati) {
            try {
                for (Locator candidato : visibili(gruppo).all()) {
                    candidato.click(new Locator.ClickOptions().setTimeout(2000));
                    if (attendiSenzaErrore(this::ricercaAperta, 3000)) {
                        return;
                    }
                }
            } catch (RuntimeException ignorata) {
                // candidato non adatto: si prova il successivo
            }
        }

        // 3) si chiede all'utente di aprirla una volta e si memorizza dove ha cliccato
        diagnostica("apertura-ricerca");
        for (Frame f : page.frames()) {
            try {
                f.evaluate(REGISTRA_CLIC);
            } catch (RuntimeException ignorata) {
                // frame non accessibile
            }
        }
        chiedi("Non riesco ad aprire la Ricerca avanzata: aprila tu nel browser con un clic, poi premi INVIO.\n"
                + "Memorizzo dove clicchi, cosi' dalla prossima volta la apro da solo.");
        attendi(this::ricercaAperta, 15000, "Ricerca avanzata");
        for (Frame f : page.frames()) {
            try {
                Object percorso = f.evaluate("() => window.__clicAppreso || null");
                if (percorso != null && !percorso.toString().isBlank()) {
                    Files.writeString(appreso, percorso.toString(), StandardCharsets.UTF_8);
                    log("Ho memorizzato come aprire la Ricerca avanzata (" + FILE_APRI_RICERCA + ")");
                    break;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (RuntimeException ignorata) {
                // frame non accessibile
            }
        }
    }

    private boolean ricercaAperta() {
        return visibile(VISTA_SOGGETTI) || visibile(VISTA_POSIZIONI);
    }

    /** Se la Ricerca avanzata mostra le posizioni di un soggetto, torna all'elenco dei soggetti. */
    private void mostraElencoSoggetti() {
        cfInVista = null;
        attendi(() -> {
            if (visibile(VISTA_SOGGETTI)) {
                return true;
            }
            if (visibile(VISTA_POSIZIONI)) {
                try {
                    visibili(app().getByText("Torna indietro")).first().click(new Locator.ClickOptions().setTimeout(3000));
                } catch (RuntimeException ignorata) {
                    // si riprova al giro successivo
                }
            }
            return false;
        }, 20000, "elenco dei soggetti");
    }

    /** Imposta "Visualizza N elementi" al valore piu' alto, per ridurre le pagine da scorrere. */
    private void massimoPerPagina() {
        try {
            Locator scelta = visibili(app().locator("select[name$='_length'], label:has-text('Visualizza') select"));
            if (scelta.count() > 0) {
                String massimo = scelta.first().locator("option").last().getAttribute("value");
                scelta.first().selectOption(massimo);
                page.waitForTimeout(400);
            }
        } catch (RuntimeException e) {
            log("  Non riesco a cambiare il numero di elementi per pagina: proseguo a pagine");
        }
    }

    // ------------------------------------------------------------------ utilita'

    /** Il frame che contiene l'applicazione del cassetto (puo' essere la pagina stessa o un iframe). */
    private Frame app() {
        for (Frame f : page.frames()) {
            try {
                if (f.getByText("Dati di dettaglio").count() > 0) {
                    return f;
                }
            } catch (RuntimeException ignorata) {
                // frame sparito nel frattempo
            }
        }
        return page.mainFrame();
    }

    /** Ricarica la pagina; se la sessione e' scaduta chiede di rientrare. */
    @Override
    protected void ripristina() {
        cfInVista = null;
        try {
            page.reload();
            page.waitForTimeout(1500);
        } catch (RuntimeException ignorata) {
            // si verifica comunque la sessione qui sotto
        }
        if (!visibile("Dati di dettaglio") && !visibile(GRUPPO_MENU)) {
            chiedi("Il cassetto previdenziale non risulta aperto (sessione scaduta?): rientra, poi premi INVIO");
        }
    }
}
