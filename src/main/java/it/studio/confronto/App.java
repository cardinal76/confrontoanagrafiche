package it.studio.confronto;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;

import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

/**
 * Uso: App [comando] [cartella di lavoro]
 * Comandi: inps = scarica i dati dal Cassetto Previdenziale Contribuente;
 *          ade  = scarica l'anagrafica dal cassetto fiscale dell'Agenzia delle Entrate.
 */
public class App {

    public static void main(String[] args) throws Exception {
        String comando = args.length > 0 ? args[0] : "inps";
        Path base = Path.of(args.length > 1 ? args[1] : ".").toAbsolutePath().normalize();
        Config cfg = new Config(base.resolve("config.properties"));

        if (!comando.equals("inps") && !comando.equals("ade")) {
            System.out.println("Comando '" + comando + "' sconosciuto: usa 'inps' oppure 'ade'.");
            return;
        }

        // per l'Agenzia delle Entrate l'elenco dei clienti si legge prima di aprire il browser
        List<String> codici = List.of();
        if (comando.equals("ade")) {
            Path elenco = base.resolve(cfg.opt("ade.input.file", "inps.xlsx"));
            codici = ExcelIO.leggiCodiciFiscali(elenco);
            System.out.println("Codici fiscali letti da " + elenco + ": " + codici.size());
        }

        Scanner tastiera = new Scanner(System.in);
        try (Playwright pw = Playwright.create()) {
            BrowserType.LaunchPersistentContextOptions opzioni =
                    new BrowserType.LaunchPersistentContextOptions().setHeadless(false).setViewportSize(null)
                            .setArgs(List.of("--start-maximized"));
            try (BrowserContext ctx = apriBrowser(pw, base.resolve("profilo-browser"), opzioni, cfg.opt("browser.channel"))) {
                Page page = ctx.pages().isEmpty() ? ctx.newPage() : ctx.pages().get(0);
                if (comando.equals("ade")) {
                    page.navigate(cfg.opt("ade.url.login", "https://www.agenziaentrate.gov.it/"));
                    System.out.println();
                    System.out.println("Accedi all'Agenzia delle Entrate con SPID e scegli l'utenza di lavoro.");
                    System.out.println("Se il cassetto fiscale si apre in un'altra scheda, chiudi le altre.");
                    new AdeDownloader(page, cfg, base).esegui(codici);
                    return;
                }
                page.navigate(cfg.opt("inps.url.login", "https://www.inps.it/"));

                System.out.println();
                System.out.println("Accedi con SPID ed entra nel Cassetto Previdenziale del Contribuente,");
                System.out.println("fino a vedere il menu del cassetto. Poi premi INVIO qui.");
                tastiera.nextLine();

                Page cassetto = schedaDelCassetto(ctx, page);
                boolean nuovo = !cfg.opt("inps.flusso", "vecchio").equalsIgnoreCase("vecchio");
                System.out.println("Interfaccia del cassetto: " + (nuovo ? "nuova" : "vecchia") + " (inps.flusso)");
                InpsBase scaricatore = nuovo
                        ? new InpsNuovoDownloader(cassetto, cfg, base, tastiera)
                        : new InpsDownloader(cassetto, cfg, base, tastiera);
                scaricatore.esegui();
            }
        }
    }

    /** Usa il browser installato indicato in configurazione; se non c'e' ripiega sul Chromium di Playwright. */
    private static BrowserContext apriBrowser(Playwright pw, Path profilo,
                                              BrowserType.LaunchPersistentContextOptions opzioni, String canale) {
        if (!canale.isEmpty()) {
            try {
                return pw.chromium().launchPersistentContext(profilo, opzioni.setChannel(canale));
            } catch (PlaywrightException e) {
                System.out.println("Browser '" + canale + "' non disponibile: uso il Chromium di Playwright.");
                opzioni.setChannel((String) null);
            }
        }
        return pw.chromium().launchPersistentContext(profilo, opzioni);
    }

    /** Il cassetto puo' aprirsi in una nuova scheda: si sceglie quella che contiene il suo menu. */
    private static Page schedaDelCassetto(BrowserContext ctx, Page predefinita) {
        for (Page p : ctx.pages()) {
            for (Frame f : p.frames()) {
                try {
                    if (f.getByText("Dati di dettaglio").count() > 0 || f.getByText("Ricerca Posizione").count() > 0
                            || f.getByText("Deseleziona Posizione").count() > 0) {
                        p.bringToFront();
                        return p;
                    }
                } catch (RuntimeException ignorata) {
                    // scheda o frame non leggibile
                }
            }
        }
        return ctx.pages().isEmpty() ? predefinita : ctx.pages().get(ctx.pages().size() - 1);
    }
}
