package it.studio.confronto;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scarica "Dati Generali" e "Inquadramento" di tutte le posizioni contributive visibili nella
 * Ricerca avanzata del Cassetto Previdenziale Contribuente.
 *
 * Percorso: Ricerca avanzata -> codice fiscale -> posizione contributiva -> menu Dati Sintetici.
 * Ogni posizione letta viene salvata subito, quindi il lavoro si puo' interrompere e riprendere.
 */
public class InpsDownloader {

    static final String FILE_SOGGETTI = "inps-soggetti.csv";
    static final String FILE_DATI = "inps-dati.csv";
    static final String FILE_COMPLETATI = "inps-completati.txt";
    static final String FILE_SCARTATE = "inps-scartate.csv";
    static final String FILE_ERRORI = "inps-errori.csv";
    static final String FILE_XLSX = "inps.xlsx";

    private static final String VISTA_SOGGETTI = "per visualizzare le relative";
    private static final String VISTA_POSIZIONI = "per selezionarla";
    private static final String VOCE_MENU = "Dati Sintetici";
    private static final String GRUPPO_MENU = "Dati sintetici";
    private static final int MAX_DIAGNOSTICHE = 10;

    private final Page page;
    private final Config cfg;
    private final Path dir;
    private final Scanner tastiera;
    private final long pausaMs;
    private final long attesaMs;
    private final long pausaSelezioneMs;
    private final int tentativi;
    private final List<String> gestioniAmmesse;
    private final List<String> statiEsclusi;

    private String cfInVista;
    private int diagnostiche;

    public InpsDownloader(Page page, Config cfg, Path dir, Scanner tastiera) {
        this.page = page;
        this.cfg = cfg;
        this.dir = dir;
        this.tastiera = tastiera;
        this.pausaMs = (long) (Double.parseDouble(cfg.opt("pausa.secondi", "2")) * 1000);
        this.attesaMs = (long) (Double.parseDouble(cfg.opt("inps.attesa.secondi", "20")) * 1000);
        this.pausaSelezioneMs = (long) (Double.parseDouble(cfg.opt("inps.pausa.selezione.secondi", "2")) * 1000);
        this.tentativi = Integer.parseInt(cfg.opt("inps.tentativi", "4"));
        this.gestioniAmmesse = elenco(cfg.opt("inps.gestioni", "Aziende DM"));
        this.statiEsclusi = elenco(cfg.opt("inps.stati.esclusi", "Cessata"));
        page.setDefaultTimeout(15000);
    }

    public void esegui() throws IOException {
        List<List<String>> soggetti = soggetti();
        Set<String> completati = new HashSet<>(Files.exists(dir.resolve(FILE_COMPLETATI))
                ? Files.readAllLines(dir.resolve(FILE_COMPLETATI), StandardCharsets.UTF_8) : List.of());
        Set<String> fatte = new HashSet<>();
        for (List<String> r : Csv.leggi(dir.resolve(FILE_DATI))) {
            fatte.add(r.get(0) + "|" + r.get(2));
        }

        int limite = Integer.parseInt(cfg.opt("inps.limite", "0"));
        int elaborati = 0;
        int erroriDiFila = 0;
        try {
            for (int i = 0; i < soggetti.size(); i++) {
                String cf = soggetti.get(i).get(0);
                if (completati.contains(cf)) {
                    continue;
                }
                if (limite > 0 && elaborati >= limite) {
                    log("Raggiunto il limite di prova (inps.limite=" + limite + ")");
                    break;
                }
                elaborati++;
                try {
                    int lette = elaboraSoggetto(cf, soggetti.get(i).get(1), fatte);
                    Files.writeString(dir.resolve(FILE_COMPLETATI), cf + System.lineSeparator(),
                            StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    log(String.format("[%d/%d] %s: %d posizioni lette", i + 1, soggetti.size(), cf, lette));
                    erroriDiFila = 0;
                } catch (RuntimeException e) {
                    erroriDiFila++;
                    String msg = String.valueOf(e.getMessage()).lines().findFirst().orElse("");
                    log(String.format("[%d/%d] %s: ERRORE %s", i + 1, soggetti.size(), cf, msg));
                    Csv.accoda(dir.resolve(FILE_ERRORI), List.of(cf, soggetti.get(i).get(1), msg));
                    diagnostica("errore-" + cf);
                    ripristina();
                    if (erroriDiFila >= 5) {
                        chiedi("5 errori di fila: controlla il browser (sessione scaduta?), poi premi INVIO per continuare");
                        erroriDiFila = 0;
                    }
                }
            }
        } finally {
            if (Files.exists(dir.resolve(FILE_DATI))) {
                List<String> testata = new ArrayList<>(List.of("CF soggetto", "Denominazione soggetto", "Posizione",
                        "Gestione (elenco)", "Descrizione azienda (elenco)", "Sede (elenco)", "Stato (elenco)"));
                testata.addAll(DatiSinteticiParser.CAMPI);
                testata.add("Note");
                ExcelIO.csvInXlsx(dir.resolve(FILE_DATI), dir.resolve(FILE_XLSX), testata);
                log("Dati salvati in " + dir.resolve(FILE_XLSX));
            }
        }
    }

    // ------------------------------------------------------------------ elenco dei soggetti

    /** Elenco (codice fiscale, denominazione) di tutti i soggetti in delega; letto una volta e poi riusato. */
    private List<List<String>> soggetti() throws IOException {
        Path file = dir.resolve(FILE_SOGGETTI);
        if (Files.exists(file)) {
            List<List<String>> salvati = Csv.leggi(file);
            log("Soggetti gia' in elenco (" + FILE_SOGGETTI + "): " + salvati.size());
            return salvati;
        }
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
        for (Map.Entry<String, String> s : trovati.entrySet()) {
            Csv.accoda(file, List.of(s.getKey(), s.getValue()));
        }
        log("Soggetti letti dall'elenco: " + trovati.size());
        return Csv.leggi(file);
    }

    private Integer totaleDichiarato() {
        Locator info = visibili(app().getByText(Pattern.compile("di\\s+[\\d.]+\\s+elementi")));
        if (info.count() == 0) {
            return null;
        }
        Matcher m = Pattern.compile("di\\s+([\\d.]+)\\s+elementi").matcher(info.first().innerText());
        return m.find() ? Integer.valueOf(m.group(1).replace(".", "")) : null;
    }

    // ------------------------------------------------------------------ un soggetto

    private int elaboraSoggetto(String cf, String denominazione, Set<String> fatte) throws IOException {
        List<Map<String, String>> posizioni = posizioni(cf);
        int lette = 0;
        for (Map<String, String> p : posizioni) {
            String posizione = p.get("posizione");
            if (fatte.contains(cf + "|" + posizione)) {
                continue;
            }
            String motivo = motivoScarto(p);
            if (motivo != null) {
                Csv.accoda(dir.resolve(FILE_SCARTATE), List.of(cf, denominazione, posizione,
                        p.get("gestione"), p.get("stato"), motivo));
                fatte.add(cf + "|" + posizione);
                continue;
            }

            Map<String, String> dati = leggiPosizione(cf, posizione);
            List<String> riga = new ArrayList<>(List.of(cf, denominazione, posizione,
                    p.get("gestione"), p.get("descrizione"), p.get("sede"), p.get("stato")));
            for (String campo : DatiSinteticiParser.CAMPI) {
                riga.add(dati.getOrDefault(campo, ""));
            }
            riga.add(dati.getOrDefault(DatiSinteticiParser.CSC, "").isEmpty() ? "Inquadramento non disponibile" : "");
            Csv.accoda(dir.resolve(FILE_DATI), riga);
            fatte.add(cf + "|" + posizione);
            lette++;
            page.waitForTimeout(pausaMs);
        }
        return lette;
    }

    private String motivoScarto(Map<String, String> p) {
        String stato = p.get("stato").toLowerCase();
        for (String escluso : statiEsclusi) {
            if (stato.contains(escluso.toLowerCase())) {
                return "Stato escluso";
            }
        }
        if (!gestioniAmmesse.isEmpty()
                && gestioniAmmesse.stream().noneMatch(g -> g.equalsIgnoreCase(p.get("gestione")))) {
            return "Gestione non richiesta";
        }
        return null;
    }

    /** Apre le posizioni contributive del codice fiscale nella Ricerca avanzata e le restituisce. */
    private List<Map<String, String>> posizioni(String cf) {
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

    private static int colonna(List<String> testate, String parola, int predefinita) {
        for (int i = 0; i < testate.size(); i++) {
            if (testate.get(i).toLowerCase().contains(parola)) {
                return i;
            }
        }
        return predefinita;
    }

    // ------------------------------------------------------------------ dati sintetici

    /** Seleziona la posizione e legge i dati sintetici; se la finestra resta vuota la chiude e riprova. */
    private Map<String, String> leggiPosizione(String cf, String posizione) {
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
                if (erroreInFinestra()) {
                    log("  " + posizione + ": la finestra mostra un messaggio di errore del portale");
                    break;
                }
                Map<String, String> dati = datiInFinestra(posizione);
                if (!dati.isEmpty()) {
                    migliore = dati;
                    if (!dati.getOrDefault(DatiSinteticiParser.CSC, "").isEmpty()) {
                        break;
                    }
                }
                page.waitForTimeout(400);
            }
            chiudiFinestra();
            if (!migliore.getOrDefault(DatiSinteticiParser.CSC, "").isEmpty()) {
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

    /** Dati della finestra aperta, solo se riferiti alla posizione attesa (evita di leggere la finestra precedente). */
    private Map<String, String> datiInFinestra(String posizione) {
        for (Frame f : page.frames()) {
            try {
                Map<String, String> dati = DatiSinteticiParser.analizza(f.locator("body").innerText());
                String letta = dati.getOrDefault(DatiSinteticiParser.POSIZIONE, "").replaceAll("\\D", "");
                if (!letta.isEmpty() && letta.equals(posizione.replaceAll("\\D", ""))
                        && !dati.getOrDefault("Denominazione", "").isEmpty()) {
                    return dati;
                }
            } catch (RuntimeException ignorata) {
                // frame non leggibile o in caricamento: si riprova al giro successivo
            }
        }
        return Map.of();
    }

    /** La finestra mostra un errore del portale (es. "500 - Ticket non valido") invece dei dati. */
    private boolean erroreInFinestra() {
        for (Frame f : page.frames()) {
            try {
                if (visibili(f.getByText("Ticket non valido")).count() > 0
                        || visibili(f.getByText("Messaggio di Avviso")).count() > 0) {
                    return true;
                }
            } catch (RuntimeException ignorata) {
                // frame sparito nel frattempo
            }
        }
        return false;
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
        if (visibile(VISTA_SOGGETTI) || visibile(VISTA_POSIZIONI)) {
            return;
        }
        List<String> selettori = new ArrayList<>(elenco(cfg.opt("inps.sel.apriRicerca")));
        selettori.addAll(List.of(".fa-search", ".fa-magnifying-glass", "text=Ricerca avanzata"));
        for (String sel : selettori) {
            try {
                for (Locator candidato : visibili(app().locator(sel)).all()) {
                    candidato.click(new Locator.ClickOptions().setTimeout(2000));
                    if (attendiSenzaErrore(() -> visibile(VISTA_SOGGETTI) || visibile(VISTA_POSIZIONI), 3000)) {
                        return;
                    }
                }
            } catch (RuntimeException ignorata) {
                // selettore non adatto: si prova il successivo
            }
        }
        diagnostica("apertura-ricerca");
        chiedi("Non riesco ad aprire la Ricerca avanzata: aprila tu nel browser, poi premi INVIO");
        attendi(() -> visibile(VISTA_SOGGETTI) || visibile(VISTA_POSIZIONI), 15000, "Ricerca avanzata");
    }

    /** Se la Ricerca avanzata mostra le posizioni di un soggetto, torna all'elenco dei soggetti. */
    private void mostraElencoSoggetti() {
        if (visibile(VISTA_POSIZIONI)) {
            visibili(app().getByText("Torna indietro")).first().click();
        }
        attendi(() -> visibile(VISTA_SOGGETTI), 15000, "elenco dei soggetti");
        cfInVista = null;
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

    private static Locator visibili(Locator l) {
        return l.locator("visible=true");
    }

    private boolean visibile(String testo) {
        try {
            return visibili(app().getByText(testo)).count() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void attendi(BooleanSupplier condizione, long ms, String cosa) {
        if (!attendiSenzaErrore(condizione, ms)) {
            throw new IllegalArgumentException("Attesa scaduta: " + cosa);
        }
    }

    private boolean attendiSenzaErrore(BooleanSupplier condizione, long ms) {
        long scadenza = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < scadenza) {
            if (condizione.getAsBoolean()) {
                return true;
            }
            page.waitForTimeout(250);
        }
        return condizione.getAsBoolean();
    }

    /** Riporta la pagina a uno stato pulito dopo un errore; se la sessione e' scaduta chiede di rientrare. */
    private void ripristina() {
        cfInVista = null;
        try {
            page.reload();
            page.waitForTimeout(1500);
        } catch (RuntimeException ignorata) {
            // si verifica comunque la sessione qui sotto
        }
        boolean sessioneAttiva = false;
        for (Frame f : page.frames()) {
            try {
                sessioneAttiva |= f.getByText("Dati di dettaglio").count() > 0;
            } catch (RuntimeException ignorata) {
                // frame sparito nel frattempo
            }
        }
        if (!sessioneAttiva) {
            chiedi("Il cassetto previdenziale non risulta aperto (sessione scaduta?): rientra, poi premi INVIO");
        }
    }

    /** Salva HTML e schermata dello stato attuale, per capire cosa non ha funzionato. Contiene dati dei clienti. */
    private void diagnostica(String nome) {
        if (diagnostiche >= MAX_DIAGNOSTICHE) {
            return;
        }
        diagnostiche++;
        try {
            Path cartella = Files.createDirectories(dir.resolve("diagnostica"));
            String base = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss")) + "-" + nome;
            page.screenshot(new Page.ScreenshotOptions().setPath(cartella.resolve(base + ".png")));
            int n = 0;
            for (Frame f : page.frames()) {
                Files.writeString(cartella.resolve(base + "-frame" + (n++) + ".html"), f.content(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            log("  Diagnostica non salvata: " + e.getMessage());
        }
    }

    private void chiedi(String messaggio) {
        System.out.println();
        System.out.println(messaggio);
        tastiera.nextLine();
    }

    private static List<String> elenco(String valori) {
        return Arrays.stream(valori.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static void log(String messaggio) {
        System.out.println(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + messaggio);
    }
}
