package it.studio.confronto;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scarica l'anagrafica del cassetto fiscale per un elenco di codici fiscali.
 *
 * Non conosce le pagine del portale: la prima volta REGISTRA il percorso che l'utente fa a mano
 * per il primo codice fiscale (clic e campi compilati) e poi lo RIPETE per tutti gli altri,
 * sostituendo il codice fiscale. Di ogni cliente salva il testo delle pagine attraversate.
 */
public class AdeDownloader {

    static final String FILE_PERCORSO = "ade-percorso.csv";
    static final String FILE_GREZZO = "ade-registrazione-grezza.csv";
    static final String FILE_DATI = "ade-dati.csv";
    static final String FILE_ERRORI = "ade-errori.csv";
    static final String FILE_XLSX = "ade.xlsx";
    static final String CARTELLA_TESTI = "ade-testi";
    private static final String SEGNAPOSTO = "{CF}";

    /** Registra nella pagina clic, campi compilati e INVIO, e li comunica al programma. */
    private static final String REGISTRATORE = """
            (() => {
              if (window.__registratoreAde) { return; }
              window.__registratoreAde = true;
              const CAMPI = ['INPUT', 'SELECT', 'TEXTAREA'];
              const PULSANTI = ['button', 'submit', 'checkbox', 'radio', 'image', 'reset'];
              const invia = (tipo, sel, val) => { try { window.registraAzione(tipo, sel, val || ''); } catch (e) {} };
              const percorso = el => {
                const parti = [];
                while (el && el.nodeType === 1 && el !== document.body) {
                  if (el.id) { parti.unshift('#' + CSS.escape(el.id)); break; }
                  let n = 1, s = el;
                  while ((s = s.previousElementSibling)) { if (s.tagName === el.tagName) n++; }
                  parti.unshift(el.tagName.toLowerCase() + ':nth-of-type(' + n + ')');
                  el = el.parentElement;
                }
                if (!parti.length || !parti[0].startsWith('#')) { parti.unshift('body'); }
                return parti.join(' > ');
              };
              const selCampo = el => el.id ? '#' + CSS.escape(el.id)
                  : el.name ? el.tagName.toLowerCase() + '[name=' + JSON.stringify(el.name) + ']' : percorso(el);
              const campoDiTesto = el => CAMPI.includes(el.tagName) && !PULSANTI.includes(el.type) && el.type !== 'password';
              let inSospeso = null;
              const scarica = () => { if (inSospeso) { invia(inSospeso.tipo, inSospeso.sel, inSospeso.val); inSospeso = null; } };

              document.addEventListener('input', e => {
                const el = e.target;
                if (el && campoDiTesto(el)) {
                  inSospeso = { tipo: el.tagName === 'SELECT' ? 'select' : 'fill', sel: selCampo(el), val: el.value };
                }
              }, true);
              document.addEventListener('change', e => {
                const el = e.target;
                if (el && campoDiTesto(el)) {
                  inSospeso = { tipo: el.tagName === 'SELECT' ? 'select' : 'fill', sel: selCampo(el), val: el.value };
                  scarica();
                }
              }, true);
              document.addEventListener('keydown', e => {
                if (e.key === 'Enter' && e.target && e.target.tagName === 'INPUT') {
                  scarica();
                  invia('enter', selCampo(e.target), '');
                }
              }, true);
              document.addEventListener('click', e => {
                if (!e.isTrusted && e.detail === 0) { return; }
                let el = e.target.closest('a, button, input, select, [role=button], [role=link], [role=tab], [onclick]') || e.target;
                if (campoDiTesto(el) || el.type === 'password') { return; }
                scarica();
                const testo = (el.innerText || '').trim().replace(/\\s+/g, ' ');
                let sel;
                if (el.id) { sel = '#' + CSS.escape(el.id); }
                else if (['A', 'BUTTON'].includes(el.tagName) && testo && testo.length <= 80) {
                  sel = el.tagName.toLowerCase() + ':text-is(' + JSON.stringify(testo) + ')';
                } else if (el.tagName === 'INPUT' && el.value) { sel = 'input[value=' + JSON.stringify(el.value) + ']'; }
                else { sel = percorso(el); }
                // se il clic avviene dentro la riga di un elenco, si annota la riga: serve quando il cliente
                // si sceglie da una lista invece di scriverne il codice fiscale
                const riga = el.closest('tr, li, [role=row], label');
                let contesto = '';
                if (riga) {
                  const stessi = Array.from(riga.querySelectorAll(el.tagName));
                  const indice = riga === el ? -1 : stessi.indexOf(el);
                  contesto = [riga.tagName.toLowerCase(), el.tagName.toLowerCase(), indice,
                      (riga.innerText || '').replace(/\s+/g, ' ').slice(0, 400)].join('|');
                }
                invia('click', sel, contesto);
              }, true);
            })()
            """;

    static final String FILE_APRI_CLIENTE = "ade-apri-cliente.txt";

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
                if (!parti.length || !parti[0].startsWith('#')) { parti.unshift('body'); }
                window.__clicAppreso = parti.join(' > ');
              }, true);
            }
            """;

    private boolean altreAttivitaSegnalato;
    private final Page page;
    private final Config cfg;
    private final Path dir;
    private final long pausaPassoMs;
    private final long pausaMs;
    private final List<String[]> registrati = new ArrayList<>();
    private boolean inRegistrazione;

    public AdeDownloader(Page page, Config cfg, Path dir) {
        this.page = page;
        this.cfg = cfg;
        this.dir = dir;
        this.pausaPassoMs = (long) (Double.parseDouble(cfg.opt("ade.pausa.passo.secondi", "1.5")) * 1000);
        this.pausaMs = (long) (Double.parseDouble(cfg.opt("pausa.secondi", "2")) * 1000);
        page.setDefaultTimeout(15000);
        page.context().onDialog(avviso -> {
            log("  Avviso del portale: \"" + avviso.message() + "\" -> OK");
            avviso.accept();
        });
        page.context().exposeBinding("registraAzione", (origine, argomenti) -> {
            if (inRegistrazione) {
                registrati.add(new String[]{String.valueOf(argomenti[0]), String.valueOf(argomenti[1]),
                        String.valueOf(argomenti[2])});
            }
            return null;
        });
    }

    public void esegui(List<String> codici) throws IOException {
        if (codici.isEmpty()) {
            log("Nessun codice fiscale da elaborare");
            return;
        }
        boolean registrato = cfg.opt("ade.percorso", "automatico").equalsIgnoreCase("registrato");
        List<List<String>> percorso = List.of();
        if (registrato) {
            percorso = Csv.leggi(dir.resolve(FILE_PERCORSO));
            if (percorso.isEmpty()) {
                percorso = registra(codici.get(0));
            } else {
                log("Uso il percorso gia' registrato in " + FILE_PERCORSO + " (" + (percorso.size() - 1) + " passi)");
            }
        } else {
            attendiUtente("Entra nel Cassetto fiscale (Servizi > Cassetto fiscale), fino alla pagina con la finestra\n"
                    + "\"Codice fiscale del cliente\" o con le \"Sezioni disponibili\". Poi premi INVIO.");
        }

        Set<String> fatti = new HashSet<>();
        for (List<String> r : Csv.leggi(dir.resolve(FILE_DATI))) {
            fatti.add(r.get(0));
        }
        // i codici gia' andati in errore non si ritentano, a meno che non sia chiesto in configurazione
        if (!cfg.opt("ade.ritenta.errori", "false").equalsIgnoreCase("true")) {
            int prima = fatti.size();
            for (List<String> r : Csv.leggi(dir.resolve(FILE_ERRORI))) {
                fatti.add(r.get(0));
            }
            if (fatti.size() > prima) {
                log("Salto " + (fatti.size() - prima) + " codici fiscali gia' andati in errore (ade.ritenta.errori=false)");
            }
        }
        int limite = Integer.parseInt(cfg.opt("ade.limite", "0"));
        int elaborati = 0;
        int erroriDiFila = 0;
        Files.createDirectories(dir.resolve(CARTELLA_TESTI));
        try {
            for (int i = 0; i < codici.size(); i++) {
                String cf = codici.get(i);
                if (fatti.contains(cf)) {
                    continue;
                }
                if (limite > 0 && elaborati >= limite) {
                    log("Raggiunto il limite di prova (ade.limite=" + limite + ")");
                    break;
                }
                elaborati++;
                try {
                    String testo = registrato ? riproduci(percorso, cf) : percorsoAutomatico(cf);
                    Files.writeString(dir.resolve(CARTELLA_TESTI).resolve(cf + ".txt"), testo, StandardCharsets.UTF_8);
                    if (!testo.toUpperCase().contains(cf.toUpperCase())) {
                        throw new IllegalArgumentException("le pagine raggiunte non riportano il codice fiscale");
                    }
                    Map<String, String> a = AdeParser.analizza(testo);
                    Csv.accoda(dir.resolve(FILE_DATI), List.of(cf));
                    fatti.add(cf);
                    erroriDiFila = 0;
                    log(String.format("[%d/%d] %s: %s | ATECO %s", i + 1, codici.size(), cf,
                            a.get(AdeParser.DENOMINAZIONE).isEmpty() ? "(denominazione non trovata)" : a.get(AdeParser.DENOMINAZIONE),
                            a.get(AdeParser.ATECO).isEmpty() ? "(non trovato)" : a.get(AdeParser.ATECO)));
                } catch (RuntimeException e) {
                    erroriDiFila++;
                    String msg = String.valueOf(e.getMessage()).lines().findFirst().orElse("");
                    log(String.format("[%d/%d] %s: ERRORE %s", i + 1, codici.size(), cf, msg));
                    Csv.accoda(dir.resolve(FILE_ERRORI), List.of(cf, msg));
                    if (erroriDiFila >= 5) {
                        attendiUtente("5 errori di fila: controlla il browser (sessione scaduta?), poi premi INVIO per continuare");
                        erroriDiFila = 0;
                    }
                }
                page.waitForTimeout(pausaMs);
            }
        } finally {
            scriviXlsx(dir);
        }
    }

    /**
     * Ricostruisce ade.xlsx rileggendo i testi salvati: se le regole di lettura cambiano,
     * non serve riscaricare nulla.
     */
    public static void scriviXlsx(Path dir) throws IOException {
        Set<String> fatti = new java.util.LinkedHashSet<>();
        for (List<String> r : Csv.leggi(dir.resolve(FILE_DATI))) {
            fatti.add(r.get(0));
        }
        // ultimo errore di ogni codice fiscale non completato: finisce nell'Excel con il motivo
        Map<String, String> errori = new LinkedHashMap<>();
        for (List<String> r : Csv.leggi(dir.resolve(FILE_ERRORI))) {
            if (!fatti.contains(r.get(0))) {
                errori.put(r.get(0), r.size() > 1 ? r.get(1) : "");
            }
        }
        if (fatti.isEmpty() && errori.isEmpty()) {
            return;
        }
        List<String> testata = new ArrayList<>(List.of("Codice fiscale"));
        testata.addAll(AdeParser.CAMPI);
        testata.add("Note");
        List<List<String>> righe = new ArrayList<>();
        for (String cf : fatti) {
            Path file = dir.resolve(CARTELLA_TESTI).resolve(cf + ".txt");
            Map<String, String> a = AdeParser.analizza(Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "");
            List<String> riga = new ArrayList<>(List.of(cf));
            riga.addAll(a.values());
            riga.add(a.get(AdeParser.DENOMINAZIONE).isEmpty() || a.get(AdeParser.ATECO).isEmpty()
                    ? "Dati non riconosciuti: vedi " + CARTELLA_TESTI + "/" + cf + ".txt" : "");
            righe.add(riga);
        }
        for (Map.Entry<String, String> e : errori.entrySet()) {
            List<String> riga = new ArrayList<>(List.of(e.getKey()));
            AdeParser.CAMPI.forEach(campo -> riga.add(""));
            riga.add("ERRORE: " + e.getValue());
            righe.add(riga);
        }
        ExcelIO.scriviTabella(dir.resolve(FILE_XLSX), testata, righe);
        log("Dati salvati in " + dir.resolve(FILE_XLSX) + " (" + fatti.size() + " letti, " + errori.size() + " in errore)");
    }

    // ------------------------------------------------------------------ percorso automatico

    /**
     * Percorso del cassetto fiscale delegato:
     * home del cassetto -> finestra "Codice fiscale del cliente" -> Vai -> Vai a Consultazioni
     * -> Dati anagrafici (+ pagina "Altre attività").
     */
    private String percorsoAutomatico(String cf) {
        String home = cfg.opt("ade.url.cassetto", "https://cassetto.agenziaentrate.gov.it/CassHomeWeb/home");
        String anagrafica = cfg.opt("ade.url.anagrafica",
                "https://cassetto.agenziaentrate.gov.it/cassfisc-web/CassettoFiscaleServlet?Ric=CONTR");
        page.navigate(home);
        caricamento();

        Locator campo = campoCliente(4000);
        if (campo == null) {
            apriSceltaCliente(home);
            campo = campoCliente(15000);
        }
        if (campo == null) {
            throw new IllegalArgumentException("campo \"Codice fiscale del cliente\" non trovato");
        }
        campo.fill(cf);
        elemento(":is(button, a):text-is('Vai'), input[value='Vai' i]").click();

        java.util.regex.Pattern scelto = java.util.regex.Pattern.compile(
                "(CLIENTE:?|DELEGATO DI)\\s*" + java.util.regex.Pattern.quote(cf.toUpperCase()));
        if (!attendi(() -> scelto.matcher(testoPagina().toUpperCase()).find(), 20000)) {
            String avviso = "";
            // prima il messaggio della finestra di scelta del cliente, poi gli altri avvisi della pagina
            for (String sel : List.of(".modal, [role='dialog']", "[role='alert'], .alert")) {
                for (Frame f : page.frames()) {
                    Locator a = f.locator(sel).locator("visible=true");
                    if (avviso.isEmpty() && a.count() > 0) {
                        avviso = a.first().innerText().replaceAll("\\s+", " ").trim();
                    }
                }
            }
            throw new IllegalArgumentException("cliente non selezionato"
                    + (avviso.isEmpty() ? "" : ": " + avviso.substring(0, Math.min(200, avviso.length()))));
        }

        // dati anagrafici: dal collegamento "Vai a Consultazioni", altrimenti direttamente dall'indirizzo
        try {
            elemento("a:has-text('Vai a Consultazioni'), button:has-text('Vai a Consultazioni')", 5000).click();
            caricamento();
        } catch (RuntimeException e) {
            page.navigate(anagrafica);
        }
        if (!attendi(() -> testoPagina().contains("Dati principali"), 15000)) {
            page.navigate(anagrafica);
            if (!attendi(() -> testoPagina().contains("Dati principali"), 15000)) {
                throw new IllegalArgumentException("pagina \"Dati anagrafici\" non raggiunta");
            }
        }
        page.waitForTimeout(pausaPassoMs);
        StringBuilder testo = new StringBuilder(testoPagina());

        // codici attivita' secondari: stanno nella pagina "Altre attività" del menu a sinistra
        if (!cfg.opt("ade.altre.attivita", "true").equalsIgnoreCase("false")) {
            try {
                elemento("a:text-matches('^\\\\s*Altre attivit\\\\S+\\\\s*$', 'i')", 4000).click();
                caricamento();
                page.waitForTimeout(pausaPassoMs);
                testo.append("\n").append(AdeParser.PAGINA_ALTRE_ATTIVITA).append("\n").append(testoPagina());
            } catch (RuntimeException e) {
                if (!altreAttivitaSegnalato) {
                    altreAttivitaSegnalato = true;
                    log("  Non riesco ad aprire la pagina \"Altre attività\": proseguo con i soli dati anagrafici");
                }
            }
        }
        return testo.toString();
    }

    /** Il campo in cui scrivere il codice fiscale del cliente, se visibile entro il tempo dato. */
    private Locator campoCliente(long ms) {
        long scadenza = System.currentTimeMillis() + ms;
        do {
            for (Frame f : page.frames()) {
                try {
                    Locator perEtichetta = f.getByLabel("Codice fiscale del cliente").locator("visible=true");
                    if (perEtichetta.count() > 0) {
                        return perEtichetta.first();
                    }
                    Locator dopoIlTesto = f.locator("xpath=(//*[contains(text(),'Codice fiscale del cliente')])[last()]"
                            + "/following::input[not(@type='hidden') and not(@type='button') and not(@type='submit')][1]")
                            .locator("visible=true");
                    if (dopoIlTesto.count() > 0) {
                        return dopoIlTesto.first();
                    }
                } catch (RuntimeException ignorata) {
                    // frame sparito nel frattempo
                }
            }
            page.waitForTimeout(300);
        } while (System.currentTimeMillis() < scadenza);
        return null;
    }

    /** Riapre la finestra di scelta del cliente quando non compare da sola (un cliente e' gia' selezionato). */
    private void apriSceltaCliente(String home) throws RuntimeException {
        Path appreso = dir.resolve(FILE_APRI_CLIENTE);
        List<String> selettori = new ArrayList<>();
        try {
            if (Files.exists(appreso)) {
                selettori.add(Files.readString(appreso, StandardCharsets.UTF_8).trim());
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (!cfg.opt("ade.sel.apriCliente").isEmpty()) {
            selettori.add(cfg.opt("ade.sel.apriCliente"));
        }
        selettori.add(":is(a, button):text-matches('cambia cliente|altro cliente|cassetto del cliente|cassetto del contribuente', 'i')");
        selettori.add(":is(a, button):text-matches('^\\\\s*cambia utenza\\\\s*$', 'i')");
        String sito = java.net.URI.create(home).getHost();
        for (String sel : selettori) {
            try {
                for (Frame f : page.frames()) {
                    Locator l = f.locator(sel).locator("visible=true");
                    if (l.count() == 0) {
                        continue;
                    }
                    l.first().click(new Locator.ClickOptions().setTimeout(3000));
                    if (campoCliente(4000) != null) {
                        return;
                    }
                    // il clic ha portato fuori dal cassetto (es. cambio utenza del portale): si torna indietro
                    if (!sito.equals(java.net.URI.create(page.url()).getHost())) {
                        page.navigate(home);
                        caricamento();
                    }
                }
            } catch (RuntimeException ignorata) {
                // candidato non adatto: si prova il successivo
            }
        }
        for (Frame f : page.frames()) {
            try {
                f.evaluate(REGISTRA_CLIC);
            } catch (RuntimeException ignorata) {
                // frame non accessibile
            }
        }
        attendiUtente("Non trovo come riaprire la finestra \"Codice fiscale del cliente\": aprila tu con un clic, senza\n"
                + "scrivere nulla, poi premi INVIO. Memorizzo dove clicchi, cosi' dalla prossima volta lo faccio da solo.");
        for (Frame f : page.frames()) {
            try {
                Object percorso = f.evaluate("() => window.__clicAppreso || null");
                if (percorso != null && !percorso.toString().isBlank()) {
                    Files.writeString(appreso, percorso.toString(), StandardCharsets.UTF_8);
                    log("Ho memorizzato come aprire la scelta del cliente (" + FILE_APRI_CLIENTE + ")");
                    break;
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            } catch (RuntimeException ignorata) {
                // frame non accessibile
            }
        }
    }

    private void caricamento() {
        try {
            page.waitForLoadState();
        } catch (RuntimeException ignorata) {
            // la pagina sta ancora caricando: le attese successive coprono il ritardo
        }
    }

    private boolean attendi(java.util.function.BooleanSupplier condizione, long ms) {
        long scadenza = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < scadenza) {
            if (condizione.getAsBoolean()) {
                return true;
            }
            page.waitForTimeout(300);
        }
        return condizione.getAsBoolean();
    }

    // ------------------------------------------------------------------ registrazione

    private List<List<String>> registra(String cfEsempio) throws IOException {
        attendiUtente("REGISTRAZIONE DEL PERCORSO\n"
                + "Porta il browser sulla pagina da cui parti per scegliere il cliente (delegante), senza ancora\n"
                + "inserire nulla. Poi premi INVIO.");
        String partenza = page.url();
        page.context().addInitScript(REGISTRATORE);
        for (Frame f : page.frames()) {
            try {
                f.evaluate(REGISTRATORE);
            } catch (RuntimeException ignorata) {
                // frame non accessibile
            }
        }
        registrati.clear();
        inRegistrazione = true;
        attendiUtente("Ora fai tu, con calma, tutto il percorso per il codice fiscale  " + cfEsempio + "\n"
                + "fino a vedere denominazione e codice ATECO (se stanno su pagine diverse, aprile tutte).\n"
                + "Scrivi o incolla proprio quel codice fiscale. Quando hai finito premi INVIO.");
        page.waitForTimeout(500);
        inRegistrazione = false;

        // la registrazione cosi' com'e' viene sempre salvata: se qualcosa non torna si puo' capire cosa e' stato fatto
        Files.deleteIfExists(dir.resolve(FILE_GREZZO));
        Csv.accoda(dir.resolve(FILE_GREZZO), List.of("start", partenza, ""));
        log("Azioni registrate: " + registrati.size());
        for (String[] p : registrati) {
            Csv.accoda(dir.resolve(FILE_GREZZO), List.of(p[0], p[1], p[2]));
            log("  " + p[0] + "  " + p[1] + (p[2].isEmpty() ? "" : "  [" + p[2] + "]"));
        }

        List<List<String>> percorso = new ArrayList<>();
        percorso.add(List.of("start", partenza, ""));
        String[] precedente = null;
        for (String[] p : registrati) {
            // lo stesso campo compilato due volte di fila: vale l'ultimo valore
            if (precedente != null && p[0].equals("fill") && precedente[0].equals("fill") && p[1].equals(precedente[1])) {
                percorso.remove(percorso.size() - 1);
            }
            precedente = p;
            if (p[0].equals("click")) {
                String[] riga = p[2].split("\\|", 4);
                boolean cfNelSelettore = p[1].toUpperCase().contains(cfEsempio.toUpperCase());
                if (!cfNelSelettore && riga.length == 4 && riga[3].toUpperCase().contains(cfEsempio.toUpperCase())) {
                    // clic dentro la riga dell'elenco che riporta il codice fiscale: si ripetera' sulla riga del cliente
                    percorso.add(List.of("clickriga", riga[0] + ":has-text(\"" + SEGNAPOSTO + "\")", riga[1] + "|" + riga[2]));
                } else {
                    percorso.add(List.of("click", conSegnaposto(p[1], cfEsempio), ""));
                }
            } else {
                percorso.add(List.of(p[0], conSegnaposto(p[1], cfEsempio), conSegnaposto(p[2], cfEsempio)));
            }
        }
        if (percorso.size() < 2) {
            throw new IllegalStateException("Non ho registrato nessuna azione: riprova, cliccando dentro la pagina");
        }
        boolean usaCf = percorso.stream().anyMatch(p -> p.get(1).contains(SEGNAPOSTO) || p.get(2).contains(SEGNAPOSTO));
        if (!usaCf) {
            throw new IllegalStateException("Nelle azioni registrate (elencate qui sopra e salvate in " + FILE_GREZZO
                    + ") non compare il codice fiscale " + cfEsempio + ": non so dove mettere quello degli altri clienti");
        }
        for (List<String> p : percorso) {
            Csv.accoda(dir.resolve(FILE_PERCORSO), p);
        }
        log("Percorso registrato: " + (percorso.size() - 1) + " passi, salvato in " + FILE_PERCORSO);
        for (List<String> p : percorso) {
            log("  " + p.get(0) + "  " + p.get(1) + (p.get(2).isEmpty() ? "" : "  = " + p.get(2)));
        }
        return percorso;
    }

    private static String conSegnaposto(String testo, String cf) {
        return testo.replaceAll("(?i)" + java.util.regex.Pattern.quote(cf), java.util.regex.Matcher.quoteReplacement(SEGNAPOSTO));
    }

    // ------------------------------------------------------------------ riproduzione

    /** Ripete il percorso per il codice fiscale e restituisce il testo di tutte le pagine attraversate. */
    private String riproduci(List<List<String>> percorso, String cf) {
        StringBuilder testo = new StringBuilder();
        for (List<String> passo : percorso) {
            String tipo = passo.get(0);
            String sel = passo.get(1).replace(SEGNAPOSTO, cf);
            String valore = passo.get(2).replace(SEGNAPOSTO, cf);
            switch (tipo) {
                case "start" -> page.navigate(sel);
                case "click" -> elemento(sel).click();
                case "clickriga" -> {
                    // valore = "tag|indice" dell'elemento da cliccare dentro la riga; indice -1 = la riga stessa
                    String[] dentro = valore.split("\\|");
                    Locator riga = elemento(sel);
                    int indice = Integer.parseInt(dentro[1]);
                    (indice < 0 ? riga : riga.locator(dentro[0]).nth(indice)).click();
                }
                case "fill" -> elemento(sel).fill(valore);
                case "select" -> elemento(sel).selectOption(valore);
                case "enter" -> elemento(sel).press("Enter");
                default -> throw new IllegalStateException("Passo sconosciuto nel percorso: " + tipo);
            }
            try {
                page.waitForLoadState();
            } catch (RuntimeException ignorata) {
                // la pagina sta ancora caricando: si prosegue dopo la pausa
            }
            page.waitForTimeout(pausaPassoMs);
            testo.append(testoPagina()).append("\n");
        }
        return testo.toString();
    }

    /** Primo elemento visibile che corrisponde al selettore, cercato in tutti i frame; attende fino a 15 secondi. */
    private Locator elemento(String selettore) {
        return elemento(selettore, 15000);
    }

    private Locator elemento(String selettore, long ms) {
        long scadenza = System.currentTimeMillis() + ms;
        while (true) {
            for (Frame f : page.frames()) {
                try {
                    Locator l = f.locator(selettore).locator("visible=true");
                    if (l.count() > 0) {
                        return l.first();
                    }
                } catch (RuntimeException ignorata) {
                    // frame sparito nel frattempo
                }
            }
            if (System.currentTimeMillis() > scadenza) {
                throw new IllegalArgumentException("Elemento non trovato: " + selettore);
            }
            page.waitForTimeout(300);
        }
    }

    private String testoPagina() {
        StringBuilder testo = new StringBuilder();
        for (Frame f : page.frames()) {
            try {
                testo.append(f.locator("body").innerText()).append('\n');
            } catch (RuntimeException ignorata) {
                // frame non leggibile
            }
        }
        return testo.toString();
    }

    // ------------------------------------------------------------------ utilita'

    /**
     * Mostra il messaggio e attende INVIO. Nel frattempo continua a elaborare gli eventi del browser,
     * altrimenti le azioni dell'utente non verrebbero registrate.
     */
    protected void attendiUtente(String messaggio) {
        System.out.println();
        System.out.println(messaggio);
        try {
            while (System.in.available() == 0) {
                page.waitForTimeout(200);
            }
            while (System.in.available() > 0) {
                System.in.read();
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    protected Page page() {
        return page;
    }

    private static void log(String messaggio) {
        System.out.println(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + messaggio);
    }
}
