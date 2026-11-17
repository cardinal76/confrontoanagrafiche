package it.studio.confronto;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scaricatore per la NUOVA interfaccia del Cassetto Previdenziale del Contribuente.
 *
 * Percorso: Ricerca Posizione -> filtro per codice fiscale -> codice fiscale -> posizione contributiva
 * -> scheda "Posizione Contributiva" (+ voce di menu "Dati sintetici" per l'inquadramento)
 * -> Deseleziona Posizione -> Conferma.
 */
public class InpsNuovoDownloader extends InpsBase {

    private static final String FILTRO = "input[placeholder*='codice fiscale o denominazione' i]";
    private static final String TESTATE = "th, [role='columnheader']";
    private static final String CELLE = "td, [role='cell'], [role='gridcell']";
    private static final String DESELEZIONA = "Deseleziona Posizione";
    private static final Pattern VOCE_SINTETICI = Pattern.compile("^\\s*Dati sintetici\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INFO_PAGINE = Pattern.compile("(\\d+)\\s*-\\s*(\\d+)\\s+di\\s+(\\d+)\\s+elementi");
    private static final Set<String> ETICHETTE_SCHEDA = Set.of("posizione contributiva", "gestione", "indirizzo",
            "sede", "stato", "soggetto contribuente", "e-mail");

    private String cfInVista;
    private boolean menuSinteticiAssenteSegnalato;

    public InpsNuovoDownloader(Page page, Config cfg, Path dir, Scanner tastiera) {
        super(page, cfg, dir, tastiera);
    }

    // ------------------------------------------------------------------ elenco dei soggetti

    @Override
    protected Map<String, String> soggettiDalPortale() {
        apriRicerca();
        mostraElencoSoggetti();
        massimoPerPagina();

        Map<String, String> trovati = new LinkedHashMap<>();
        for (int pagina = 0; pagina < 2000; pagina++) {
            for (List<String> celle : righe()) {
                if (celle.size() >= 2 && !celle.get(0).isBlank()) {
                    trovati.putIfAbsent(celle.get(0), celle.get(1));
                }
            }
            int[] info = infoPagine();
            if (info == null || info[1] >= info[2]) {
                break;
            }
            if (!paginaSuccessiva()) {
                log("ATTENZIONE: non riesco a passare alla pagina successiva dell'elenco dei soggetti");
                diagnostica("pagina-successiva");
                break;
            }
        }
        int[] info = infoPagine();
        if (info != null && info[2] != trovati.size()) {
            throw new IllegalStateException("Il portale dichiara " + info[2] + " soggetti ma ne ho letti "
                    + trovati.size() + ": elenco incompleto, vedi la cartella diagnostica");
        }
        return trovati;
    }

    /** {primo, ultimo, totale} letti da "1 - 20 di 563 elementi"; null se la scritta non c'e'. */
    private int[] infoPagine() {
        for (Locator l : trova(f -> f.getByText(INFO_PAGINE))) {
            Matcher m = INFO_PAGINE.matcher(l.innerText());
            if (m.find()) {
                return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
            }
        }
        return null;
    }

    private boolean paginaSuccessiva() {
        int[] prima = infoPagine();
        List<String> selettori = new ArrayList<>(elenco(cfg.opt("inps.sel.paginaSuccessiva")));
        selettori.addAll(List.of("[aria-label*='successiv' i]", "[title*='successiv' i]", "[aria-label*='next' i]",
                "[title*='next' i]", "[aria-label*='avanti' i]"));
        List<Locator> candidati = new ArrayList<>();
        for (String sel : selettori) {
            candidati.addAll(trova(f -> f.locator(sel)));
        }
        // ripiego: i due pulsanti a freccia che seguono la scritta "1 - 20 di 563 elementi" (il secondo e' "avanti")
        for (Locator info : trova(f -> f.getByText(INFO_PAGINE))) {
            String clic = "xpath=following::*[self::button or self::a or @role='button']";
            candidati.add(info.locator(clic + "[2]"));
            candidati.add(info.locator(clic + "[1]"));
        }
        for (Locator candidato : candidati) {
            try {
                candidato.first().click(new Locator.ClickOptions().setTimeout(2000));
                if (attendiSenzaErrore(() -> {
                    int[] dopo = infoPagine();
                    return dopo != null && prima != null && dopo[0] > prima[0];
                }, 4000)) {
                    return true;
                }
            } catch (RuntimeException ignorata) {
                // candidato non cliccabile: si prova il successivo
            }
        }
        return false;
    }

    /** Sceglie il valore piu' alto di "Elementi per pagina", se e' un menu a tendina standard. */
    private void massimoPerPagina() {
        try {
            for (Locator scelta : trova(f -> f.locator("select"))) {
                List<String> valori = scelta.locator("option").all().stream()
                        .map(o -> o.getAttribute("value")).filter(v -> v != null && v.matches("\\d+")).toList();
                if (!valori.isEmpty()) {
                    scelta.selectOption(valori.stream().max((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b))).get());
                    page.waitForTimeout(800);
                    return;
                }
            }
        } catch (RuntimeException e) {
            log("  Non riesco a cambiare il numero di elementi per pagina: proseguo a pagine");
        }
    }

    // ------------------------------------------------------------------ posizioni di un soggetto

    @Override
    protected List<Map<String, String>> posizioni(String cf) {
        if (!(cf.equals(cfInVista) && vistaPosizioni())) {
            apriRicerca();
            mostraElencoSoggetti();
            filtro().fill(cf);
            attendi(() -> !celle(cf).isEmpty(), 15000, "codice fiscale " + cf + " nell'elenco");
            celle(cf).get(0).click();
            attendi(this::vistaPosizioni, 15000, "elenco delle posizioni di " + cf);
            page.waitForTimeout(500);
            cfInVista = cf;
        }

        List<String> testate = testate();
        int iPos = InpsDownloader.colonna(testate, "posizione", 2);
        int iGest = InpsDownloader.colonna(testate, "gestione", 1);
        int iDescr = InpsDownloader.colonna(testate, "descrizione", 3);
        int iSede = InpsDownloader.colonna(testate, "sede", 4);
        int iStato = InpsDownloader.colonna(testate, "stato", 5);

        List<Map<String, String>> posizioni = new ArrayList<>();
        for (List<String> celle : righe()) {
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
        int[] info = infoPagine();
        if (info != null && info[2] > posizioni.size()) {
            log("  ATTENZIONE: " + cf + " ha " + info[2] + " posizioni ma ne vedo " + posizioni.size()
                    + " nella prima pagina");
        }
        return posizioni;
    }

    private List<String> testate() {
        return trova(f -> f.locator(TESTATE)).stream().map(l -> l.innerText().trim()).toList();
    }

    private boolean vistaPosizioni() {
        return testate().stream().anyMatch(t -> t.toLowerCase().contains("posizione contributiva"));
    }

    private boolean vistaSoggetti() {
        return !vistaPosizioni() && testate().stream().anyMatch(t -> t.toLowerCase().contains("denominazione soggetto"));
    }

    /** Righe della tabella visibile, come elenchi di celle. */
    private List<List<String>> righe() {
        List<List<String>> righe = new ArrayList<>();
        for (Locator riga : trova(f -> f.locator("tbody tr"))) {
            righe.add(riga.locator("td").allInnerTexts().stream().map(String::trim).toList());
        }
        if (righe.isEmpty()) {
            for (Locator riga : trova(f -> f.locator("[role='row']"))) {
                List<String> celle = riga.locator("[role='cell'], [role='gridcell']").allInnerTexts()
                        .stream().map(String::trim).toList();
                if (!celle.isEmpty()) {
                    righe.add(celle);
                }
            }
        }
        return righe;
    }

    private List<Locator> celle(String testo) {
        return trova(f -> f.locator(CELLE).getByText(testo, new Locator.GetByTextOptions().setExact(true)));
    }

    private Locator filtro() {
        List<Locator> campi = trova(f -> f.locator(FILTRO));
        if (campi.isEmpty()) {
            throw new IllegalArgumentException("Campo \"Filtra per codice fiscale o denominazione\" non trovato");
        }
        return campi.get(0);
    }

    // ------------------------------------------------------------------ una posizione

    @Override
    protected Map<String, String> leggiPosizione(String cf, String posizione) {
        posizioni(cf);
        celle(posizione).get(0).click();
        cfInVista = null;
        attendi(() -> visibile(DESELEZIONA), 20000, "scheda della posizione " + posizione);

        Map<String, String> dati = new LinkedHashMap<>();
        attendi(() -> {
            Map<String, String> scheda = schedaPosizione(testoPagina());
            boolean giusta = scheda.getOrDefault(DatiSinteticiParser.POSIZIONE, "").replaceAll("\\D", "")
                    .equals(posizione.replaceAll("\\D", ""));
            if (giusta) {
                dati.putAll(scheda);
            }
            return giusta;
        }, attesaMs, "dati della posizione " + posizione);

        datiSintetici(posizione).forEach((campo, valore) -> {
            if (!valore.isEmpty()) {
                dati.put(campo, valore);
            }
        });
        deseleziona();
        return dati;
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

    /**
     * Legge la scheda "Posizione Contributiva" (etichetta su una riga, valore sulla successiva)
     * e la riporta sui nomi di campo usati nel file di uscita.
     */
    static Map<String, String> schedaPosizione(String testo) {
        List<String> righe = Arrays.stream(testo.split("\\R")).map(String::trim).filter(r -> !r.isEmpty()).toList();
        int inizio = 0;
        for (int i = 0; i < righe.size(); i++) {
            if (righe.get(i).toLowerCase().contains("deseleziona posizione")) {
                inizio = i + 1;
                break;
            }
        }
        Map<String, String> letti = new LinkedHashMap<>();
        for (int i = inizio; i + 1 < righe.size(); i++) {
            String etichetta = righe.get(i).toLowerCase();
            String valore = righe.get(i + 1);
            if (!ETICHETTE_SCHEDA.contains(etichetta) || ETICHETTE_SCHEDA.contains(valore.toLowerCase())) {
                continue;
            }
            letti.putIfAbsent(etichetta, valore);
            if (etichetta.equals("soggetto contribuente") && i + 2 < righe.size()
                    && !ETICHETTE_SCHEDA.contains(righe.get(i + 2).toLowerCase())) {
                letti.putIfAbsent("denominazione", righe.get(i + 2));
            }
        }
        Map<String, String> dati = new LinkedHashMap<>();
        copia(letti, "posizione contributiva", dati, DatiSinteticiParser.POSIZIONE);
        copia(letti, "soggetto contribuente", dati, "Codice Fiscale");
        copia(letti, "denominazione", dati, "Denominazione");
        copia(letti, "gestione", dati, "Gestione");
        copia(letti, "stato", dati, "Stato Azienda");
        copia(letti, "sede", dati, "Sede Competenza");
        copia(letti, "indirizzo", dati, "Indirizzo");
        copia(letti, "e-mail", dati, "Indirizzo PEC");
        return dati;
    }

    private static void copia(Map<String, String> da, String chiave, Map<String, String> a, String campo) {
        if (da.containsKey(chiave)) {
            a.put(campo, da.get(chiave));
        }
    }

    /**
     * Cerca la voce di menu "Dati sintetici" e legge l'inquadramento (CSC, ATECO).
     * Se la finestra non da' i dati la richiude e riprova. Mappa vuota se non ci riesce.
     */
    private Map<String, String> datiSintetici(String posizione) {
        for (int tentativo = 1; tentativo <= tentativi; tentativo++) {
            List<Locator> voci = trova(f -> f.getByText(VOCE_SINTETICI));
            if (voci.isEmpty()) {
                if (!menuSinteticiAssenteSegnalato) {
                    menuSinteticiAssenteSegnalato = true;
                    log("  ATTENZIONE: non trovo la voce di menu \"Dati sintetici\": salvo solo i dati della scheda");
                    diagnostica("menu-dati-sintetici");
                }
                return Map.of();
            }
            try {
                if (voci.size() == 1) {
                    // un solo risultato: puo' essere il gruppo da espandere oppure gia' la voce
                    voci.get(0).click();
                    page.waitForTimeout(800);
                    voci = trova(f -> f.getByText(VOCE_SINTETICI));
                }
                if (voci.size() > 1) {
                    voci.get(voci.size() - 1).click();
                }
            } catch (RuntimeException e) {
                log("  " + posizione + ": non riesco ad aprire \"Dati sintetici\"");
            }

            long scadenza = System.currentTimeMillis() + attesaMs;
            while (System.currentTimeMillis() < scadenza) {
                if (errorePortale()) {
                    log("  " + posizione + ": la finestra mostra un messaggio di errore del portale");
                    break;
                }
                Map<String, String> dati = datiSinteticiOvunque(posizione, false);
                if (haInquadramento(dati)) {
                    chiudiSchedeAggiuntive();
                    return dati;
                }
                page.waitForTimeout(400);
            }
            log("  " + posizione + ": inquadramento non caricato, tentativo " + tentativo + " di " + tentativi);
            if (tentativo == 1) {
                diagnostica("dati-sintetici-" + posizione);
            }
            chiudiSchedeAggiuntive();
            page.waitForTimeout(pausaSelezioneMs * tentativo);
        }
        return Map.of();
    }

    /** I dati sintetici possono aprirsi in una nuova scheda del browser: va chiusa prima di proseguire. */
    private void chiudiSchedeAggiuntive() {
        for (Page altra : page.context().pages()) {
            if (altra != page) {
                altra.close();
            }
        }
    }

    private void deseleziona() {
        if (!clic(DESELEZIONA)) {
            return;
        }
        attendi(() -> !trova(f -> f.getByText("Conferma", new Frame.GetByTextOptions().setExact(true))).isEmpty(),
                10000, "richiesta di conferma della deselezione");
        clic("Conferma");
        attendi(() -> !visibile(DESELEZIONA), 15000, "deselezione della posizione");
    }

    // ------------------------------------------------------------------ navigazione

    private boolean ricercaAperta() {
        return !trova(f -> f.locator(FILTRO)).isEmpty();
    }

    private void apriRicerca() {
        if (ricercaAperta()) {
            return;
        }
        for (int giro = 0; giro < 2; giro++) {
            try {
                if (visibile(DESELEZIONA)) {
                    deseleziona();
                }
                if (clic("Ricerca Posizione") && attendiSenzaErrore(this::ricercaAperta, 10000)) {
                    return;
                }
                if (clic("Home del servizio")) {
                    page.waitForTimeout(1500);
                    if (clic("Ricerca Posizione") && attendiSenzaErrore(this::ricercaAperta, 10000)) {
                        return;
                    }
                }
            } catch (RuntimeException ignorata) {
                // si riprova dopo aver ricaricato la pagina
            }
            page.reload();
            page.waitForTimeout(2500);
        }
        diagnostica("apertura-ricerca");
        chiedi("Non riesco ad aprire la Ricerca Posizione: aprila tu nel browser, poi premi INVIO");
        attendi(this::ricercaAperta, 15000, "Ricerca Posizione");
    }

    /** Se la ricerca mostra le posizioni di un soggetto, torna all'elenco di tutti i soggetti. */
    private void mostraElencoSoggetti() {
        cfInVista = null;
        if (attendiSenzaErrore(this::vistaSoggetti, 3000)) {
            filtro().fill("");
            return;
        }
        filtro().fill("");
        if (attendiSenzaErrore(this::vistaSoggetti, 2000)) {
            return;
        }
        for (String testo : List.of("Torna indietro", "Indietro", "Torna all'elenco")) {
            if (clic(testo) && attendiSenzaErrore(this::vistaSoggetti, 2000)) {
                return;
            }
        }
        // ultima risorsa: ricaricare la pagina e riaprire la ricerca
        page.reload();
        page.waitForTimeout(2500);
        apriRicerca();
        attendi(this::vistaSoggetti, 15000, "elenco dei soggetti");
    }

    /** Clicca il primo elemento visibile con esattamente quel testo; false se non c'e'. */
    private boolean clic(String testo) {
        List<Locator> trovati = trova(f -> f.getByText(testo, new Frame.GetByTextOptions().setExact(true)));
        if (trovati.isEmpty()) {
            return false;
        }
        trovati.get(0).click();
        return true;
    }

    @Override
    protected void ripristina() {
        cfInVista = null;
        try {
            chiudiSchedeAggiuntive();
            page.reload();
            page.waitForTimeout(2500);
        } catch (RuntimeException ignorata) {
            // si verifica comunque la sessione qui sotto
        }
        if (!ricercaAperta() && !visibile("Ricerca Posizione") && !visibile("Home del servizio")
                && !visibile(DESELEZIONA)) {
            chiedi("Il cassetto previdenziale non risulta aperto (sessione scaduta?): rientra, poi premi INVIO");
        }
    }
}
