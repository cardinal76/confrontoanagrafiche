package it.studio.confronto;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Prova registrazione e riproduzione del percorso AdE su pagine finte (src/test/resources/mock-ade*.html).
 * Argomenti: cartella di lavoro, percorso dell'eseguibile di Chromium, "elenco" per scegliere il cliente da una lista, "automatico" per il percorso gia' noto.
 */
public class AdeMockRun {

    public static void main(String[] args) throws Exception {
        boolean daElenco = args.length > 2 && args[2].equals("elenco");
        boolean automatico = args.length > 2 && args[2].equals("automatico");
        Path lavoro = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(lavoro);
        Path config = lavoro.resolve("config.properties");
        Path risorse = Path.of("src/test/resources").toAbsolutePath();
        Files.writeString(config, "pausa.secondi=0\nade.pausa.passo.secondi=0.6\nade.limite=0\n"
                + (automatico
                ? "ade.percorso=automatico\nade.url.cassetto=" + risorse.resolve("mock-ade2-home.html").toUri()
                + "\nade.url.anagrafica=" + risorse.resolve("mock-ade2-anagrafica.html").toUri() + "\n"
                : "ade.percorso=registrato\n"));
        List<String> codici = List.of("01000007919", "01000015838", "RSSMRA80A01H501U", "01000031676");
        ExcelIO.scriviTabella(lavoro.resolve("inps.xlsx"), List.of("CF soggetto", "Denominazione soggetto"),
                List.of(List.of(codici.get(0), "A"), List.of(codici.get(1), "B"), List.of(codici.get(1), "B bis"),
                        List.of(codici.get(2), "C"), List.of(codici.get(3), "D")));
        List<String> letti = ExcelIO.leggiCodiciFiscali(lavoro.resolve("inps.xlsx"));
        if (!letti.equals(codici)) {
            throw new IllegalStateException("Codici fiscali letti male: " + letti);
        }
        Path mock = Path.of("src/test/resources/mock-ade.html").toAbsolutePath();

        try (Playwright pw = Playwright.create()) {
            BrowserType.LaunchPersistentContextOptions opzioni = new BrowserType.LaunchPersistentContextOptions()
                    .setHeadless(true).setExecutablePath(Path.of(args[1]));
            try (BrowserContext ctx = pw.chromium().launchPersistentContext(lavoro.resolve("profilo"), opzioni)) {
                Page page = ctx.pages().isEmpty() ? ctx.newPage() : ctx.pages().get(0);
                page.navigate(mock.toUri().toString());
                new AdeDownloader(page, new Config(config), lavoro) {
                    private int richieste;

                    /** Al posto dell'utente: alla seconda richiesta esegue il percorso con veri eventi di tastiera e mouse. */
                    @Override
                    protected void attendiUtente(String messaggio) {
                        System.out.println("[utente finto] " + messaggio.lines().findFirst().orElse(""));
                        if (++richieste == 2 && !automatico) {
                            if (daElenco) {
                                page().locator("tr", new Page.LocatorOptions().setHasText(codici.get(0)))
                                        .getByText("Seleziona").click();
                            } else {
                                page().locator("[name=cfDelegante]").pressSequentially(codici.get(0));
                                page().locator("button").click();
                            }
                            page().locator("#voceAnagrafica").click();
                            page().waitForTimeout(600);
                        }
                    }
                }.esegui(letti);
            }
        }
    }
}
