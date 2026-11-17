package it.studio.confronto;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

/**
 * Prova lo scaricatore INPS su una pagina finta (src/test/resources/mock-inps.html).
 * Argomenti: cartella di lavoro, percorso dell'eseguibile di Chromium.
 */
public class InpsMockRun {

    public static void main(String[] args) throws Exception {
        Path lavoro = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(lavoro);
        Path config = lavoro.resolve("config.properties");
        if (!Files.exists(config)) {
            Files.writeString(config, "pausa.secondi=0\ninps.attesa.secondi=2\ninps.limite=0\n");
        }
        Path mock = Path.of("src/test/resources/mock-inps.html").toAbsolutePath();

        try (Playwright pw = Playwright.create()) {
            BrowserType.LaunchPersistentContextOptions opzioni = new BrowserType.LaunchPersistentContextOptions()
                    .setHeadless(true).setExecutablePath(Path.of(args[1]));
            try (BrowserContext ctx = pw.chromium().launchPersistentContext(lavoro.resolve("profilo"), opzioni)) {
                Page page = ctx.pages().isEmpty() ? ctx.newPage() : ctx.pages().get(0);
                page.navigate(mock.toUri().toString());
                // tastiera vuota: se lo scaricatore chiede un intervento manuale la prova fallisce
                Scanner tastiera = new Scanner(new ByteArrayInputStream(new byte[0]));
                new InpsDownloader(page, new Config(config), lavoro, tastiera).esegui();
            }
        }
    }
}
