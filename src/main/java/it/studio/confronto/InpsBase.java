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
import java.util.function.Function;

/**
 * Parte comune agli scaricatori del Cassetto Previdenziale: ciclo sui soggetti, filtri,
 * salvataggio progressivo e ripresa. Le sottoclassi sanno come muoversi nell'interfaccia.
 */
public abstract class InpsBase {

    static final String FILE_SOGGETTI = "inps-soggetti.csv";
    static final String FILE_DATI = "inps-dati.csv";
    static final String FILE_COMPLETATI = "inps-completati.txt";
    static final String FILE_SCARTATE = "inps-scartate.csv";
    static final String FILE_ERRORI = "inps-errori.csv";
    static final String FILE_XLSX = "inps.xlsx";
    static final String NOTA_SENZA_INQUADRAMENTO = "Inquadramento non disponibile";

    private static final int MAX_DIAGNOSTICHE = 10;

    /** Pulsanti la cui scritta e' esattamente "OK" (maiuscole o minuscole). */
    private static final String PULSANTE_OK = ":is(button, a, [role='button']):text-matches('^\\s*ok\\s*$', 'i'), "
            + "input:is([type='button'], [type='submit'])[value='ok' i]";

    protected final Page page;
    protected final Config cfg;
    protected final Path dir;
    protected final long pausaMs;
    protected final long attesaMs;
    protected final long pausaSelezioneMs;
    protected final int tentativi;

    private final Scanner tastiera;
    private final List<String> gestioniAmmesse;
    private final List<String> statiEsclusi;
    private int diagnostiche;

    protected InpsBase(Page page, Config cfg, Path dir, Scanner tastiera) {
        this.page = page;
        this.cfg = cfg;
        this.dir = dir;
        this.tastiera = tastiera;
        this.pausaMs = secondi("pausa.secondi", "2");
        this.attesaMs = secondi("inps.attesa.secondi", "20");
        this.pausaSelezioneMs = secondi("inps.pausa.selezione.secondi", "2");
        this.tentativi = Integer.parseInt(cfg.opt("inps.tentativi", "4"));
        this.gestioniAmmesse = elenco(cfg.opt("inps.gestioni", "Aziende DM"));
        this.statiEsclusi = elenco(cfg.opt("inps.stati.esclusi", "Cessata"));
        page.setDefaultTimeout(15000);

        // Avvisi del portale: basta confermare con OK.
        // a) finestre di avviso del browser (alert/confirm)
        page.context().onDialog(avviso -> {
            log("  Avviso del portale: \"" + avviso.message() + "\" -> OK");
            avviso.accept();
        });
        // b) riquadri di avviso dentro la pagina: Playwright li chiude prima di ogni clic che ne verrebbe bloccato
        page.addLocatorHandler(page.locator(PULSANTE_OK).first(), ok -> {
            log("  Avviso del portale -> OK");
            ok.click();
        });
    }

    /** Conferma con OK gli avvisi presenti in qualsiasi frame (quelli negli iframe sfuggono al gestore automatico). */
    protected void chiudiAvvisi() {
        for (Locator ok : trova(f -> f.locator(PULSANTE_OK))) {
            try {
                ok.click(new Locator.ClickOptions().setTimeout(2000));
                log("  Avviso del portale -> OK");
            } catch (RuntimeException ignorata) {
                // l'avviso si e' gia' chiuso
            }
        }
    }

    private long secondi(String chiave, String predefinito) {
        return (long) (Double.parseDouble(cfg.opt(chiave, predefinito)) * 1000);
    }

    // ------------------------------------------------------------------ da implementare

    /** Codice fiscale -> denominazione di tutti i soggetti in delega, letti dal portale. */
    protected abstract Map<String, String> soggettiDalPortale();

    /** Posizioni del soggetto, ognuna con le chiavi: posizione, gestione, descrizione, sede, stato. */
    protected abstract List<Map<String, String>> posizioni(String cf);

    /** Campi di {@link DatiSinteticiParser#CAMPI} letti per la posizione. */
    protected abstract Map<String, String> leggiPosizione(String cf, String posizione);

    /** Riporta la pagina a uno stato pulito dopo un errore. */
    protected abstract void ripristina();

    // ------------------------------------------------------------------ ciclo principale

    public void esegui() throws IOException {
        List<List<String>> soggetti = soggetti();
        Set<String> completati = new HashSet<>(Files.exists(dir.resolve(FILE_COMPLETATI))
                ? Files.readAllLines(dir.resolve(FILE_COMPLETATI), StandardCharsets.UTF_8) : List.of());
        Set<String> fatte = new HashSet<>();
        for (List<String> r : Csv.leggi(dir.resolve(FILE_DATI))) {
            // le posizioni salvate senza inquadramento vengono ritentate
            if (r.get(r.size() - 1).isEmpty()) {
                fatte.add(r.get(0) + "|" + r.get(2));
            }
        }
        Set<String> scartate = new HashSet<>();
        for (List<String> r : Csv.leggi(dir.resolve(FILE_SCARTATE))) {
            scartate.add(r.get(0) + "|" + r.get(2));
        }

        int limite = Integer.parseInt(cfg.opt("inps.limite", "0"));
        int elaborati = 0;
        int erroriDiFila = 0;
        try {
            // secondo giro automatico sui soli soggetti rimasti indietro per un errore o senza inquadramento
            for (int giro = 1; giro <= 2; giro++) {
            int rimasti = 0;
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
                    String esito = elaboraSoggetto(cf, soggetti.get(i).get(1), fatte, scartate);
                    if (esito.isEmpty()) {
                        Files.writeString(dir.resolve(FILE_COMPLETATI), cf + System.lineSeparator(),
                                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        completati.add(cf);
                    } else {
                        rimasti++;
                    }
                    log(String.format("[%d/%d] %s: fatto%s", i + 1, soggetti.size(), cf,
                            esito.isEmpty() ? "" : " (" + esito + ")"));
                    erroriDiFila = 0;
                } catch (RuntimeException e) {
                    rimasti++;
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
            if (rimasti == 0 || (limite > 0 && elaborati >= limite)) {
                break;
            }
            if (giro == 1) {
                log("Secondo giro sui " + rimasti + " soggetti rimasti indietro");
            }
            }
        } finally {
            scriviXlsx();
        }
    }

    private List<List<String>> soggetti() throws IOException {
        Path file = dir.resolve(FILE_SOGGETTI);
        if (!Files.exists(file)) {
            Map<String, String> letti = soggettiDalPortale();
            for (Map.Entry<String, String> s : letti.entrySet()) {
                Csv.accoda(file, List.of(s.getKey(), s.getValue()));
            }
            log("Soggetti letti dall'elenco: " + letti.size());
        } else {
            log("Uso l'elenco dei soggetti gia' salvato in " + FILE_SOGGETTI);
        }
        return Csv.leggi(file);
    }

    /** Restituisce una nota vuota se tutte le posizioni utili del soggetto sono complete. */
    private String elaboraSoggetto(String cf, String denominazione, Set<String> fatte, Set<String> scartate)
            throws IOException {
        int senzaInquadramento = 0;
        int lette = 0;
        for (Map<String, String> p : posizioni(cf)) {
            String posizione = p.get("posizione");
            String chiave = cf + "|" + posizione;
            if (fatte.contains(chiave)) {
                continue;
            }
            String motivo = motivoScarto(p);
            if (motivo != null) {
                if (scartate.add(chiave)) {
                    Csv.accoda(dir.resolve(FILE_SCARTATE), List.of(cf, denominazione, posizione,
                            p.get("gestione"), p.get("stato"), motivo));
                }
                continue;
            }

            Map<String, String> dati = leggiPosizione(cf, posizione);
            List<String> riga = new ArrayList<>(List.of(cf, denominazione, posizione,
                    p.get("gestione"), p.get("descrizione"), p.get("sede"), p.get("stato")));
            for (String campo : DatiSinteticiParser.CAMPI) {
                riga.add(dati.getOrDefault(campo, ""));
            }
            boolean completa = haInquadramento(dati);
            riga.add(completa ? "" : NOTA_SENZA_INQUADRAMENTO);
            Csv.accoda(dir.resolve(FILE_DATI), riga);
            lette++;
            if (completa) {
                fatte.add(chiave);
            } else {
                senzaInquadramento++;
            }
            page.waitForTimeout(pausaMs);
        }
        return senzaInquadramento == 0 ? ""
                : senzaInquadramento + " di " + lette + " posizioni senza inquadramento: verranno ritentate";
    }

    protected static boolean haInquadramento(Map<String, String> dati) {
        return !dati.getOrDefault(DatiSinteticiParser.CSC, "").isEmpty()
                || !dati.getOrDefault(DatiSinteticiParser.ATECO, "").isEmpty();
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

    /** Scrive l'Excel finale tenendo, per ogni posizione, solo l'ultima lettura. */
    private void scriviXlsx() throws IOException {
        if (!Files.exists(dir.resolve(FILE_DATI))) {
            return;
        }
        Map<String, List<String>> ultime = new LinkedHashMap<>();
        for (List<String> r : Csv.leggi(dir.resolve(FILE_DATI))) {
            ultime.put(r.get(0) + "|" + r.get(2), r);
        }
        List<String> testata = new ArrayList<>(List.of("CF soggetto", "Denominazione soggetto", "Posizione",
                "Gestione (elenco)", "Descrizione azienda (elenco)", "Sede (elenco)", "Stato (elenco)"));
        testata.addAll(DatiSinteticiParser.CAMPI);
        testata.add("Note");
        ExcelIO.scriviTabella(dir.resolve(FILE_XLSX), testata, new ArrayList<>(ultime.values()));
        log("Dati salvati in " + dir.resolve(FILE_XLSX) + " (" + ultime.size() + " posizioni)");
    }

    // ------------------------------------------------------------------ utilita' per le sottoclassi

    /** I frame di tutte le schede aperte: l'applicazione puo' stare in un iframe o in una nuova scheda. */
    protected List<Frame> tuttiIFrame() {
        List<Frame> frame = new ArrayList<>();
        for (Page p : page.context().pages()) {
            frame.addAll(p.frames());
        }
        return frame;
    }

    /** Elementi visibili che corrispondono al localizzatore, cercati in tutti i frame della scheda principale. */
    protected List<Locator> trova(Function<Frame, Locator> localizzatore) {
        List<Locator> trovati = new ArrayList<>();
        for (Frame f : page.frames()) {
            try {
                trovati.addAll(visibili(localizzatore.apply(f)).all());
            } catch (RuntimeException ignorata) {
                // frame sparito nel frattempo
            }
        }
        return trovati;
    }

    protected boolean visibile(String testo) {
        return !trova(f -> f.getByText(testo)).isEmpty();
    }

    protected static Locator visibili(Locator l) {
        return l.locator("visible=true");
    }

    /** Dati sintetici presenti in una qualsiasi finestra o scheda, solo se riferiti alla posizione attesa. */
    protected Map<String, String> datiSinteticiOvunque(String posizione, boolean posizioneObbligatoria) {
        for (Frame f : tuttiIFrame()) {
            try {
                Map<String, String> dati = DatiSinteticiParser.analizza(f.locator("body").innerText());
                String letta = dati.getOrDefault(DatiSinteticiParser.POSIZIONE, "").replaceAll("\\D", "");
                boolean stessa = letta.equals(posizione.replaceAll("\\D", ""));
                if (!dati.isEmpty() && (stessa || (!posizioneObbligatoria && letta.isEmpty()))) {
                    return dati;
                }
            } catch (RuntimeException ignorata) {
                // frame non leggibile o in caricamento: si riprova al giro successivo
            }
        }
        return Map.of();
    }

    /** Una finestra mostra un errore del portale (es. "500 - Ticket non valido") invece dei dati. */
    protected boolean errorePortale() {
        for (Frame f : tuttiIFrame()) {
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

    protected void attendi(BooleanSupplier condizione, long ms, String cosa) {
        if (!attendiSenzaErrore(condizione, ms)) {
            throw new IllegalArgumentException("Attesa scaduta: " + cosa);
        }
    }

    protected boolean attendiSenzaErrore(BooleanSupplier condizione, long ms) {
        long scadenza = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < scadenza) {
            if (condizione.getAsBoolean()) {
                return true;
            }
            chiudiAvvisi();
            page.waitForTimeout(250);
        }
        return condizione.getAsBoolean();
    }

    /** Salva HTML e schermata dello stato attuale, per capire cosa non ha funzionato. Contiene dati dei clienti. */
    protected void diagnostica(String nome) {
        if (diagnostiche >= MAX_DIAGNOSTICHE) {
            return;
        }
        diagnostiche++;
        try {
            Path cartella = Files.createDirectories(dir.resolve("diagnostica"));
            String base = LocalTime.now().format(DateTimeFormatter.ofPattern("HHmmss")) + "-" + nome;
            page.screenshot(new Page.ScreenshotOptions().setPath(cartella.resolve(base + ".png")));
            int n = 0;
            for (Frame f : tuttiIFrame()) {
                Files.writeString(cartella.resolve(base + "-frame" + (n++) + ".html"), f.content(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            log("  Diagnostica non salvata: " + e.getMessage());
        }
    }

    protected void chiedi(String messaggio) {
        System.out.println();
        System.out.println(messaggio);
        tastiera.nextLine();
    }

    protected static List<String> elenco(String valori) {
        return Arrays.stream(valori.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    protected static void log(String messaggio) {
        System.out.println(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + messaggio);
    }
}
