package it.studio.confronto;

import it.studio.confronto.Modello.RigaConfronto;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ExcelIO {

    private static final String[] INTESTAZIONI = {
            "Codice fiscale", "Matricola INPS", "Denominazione AdE", "Denominazione INPS", "Similarita'",
            "Esito denominazione", "ATECO AdE prevalente", "ATECO AdE secondari", "ATECO INPS", "Esito ATECO", "Note"
    };

    private ExcelIO() {
    }

    /**
     * Legge i codici fiscali dal primo foglio, saltando l'intestazione e i duplicati.
     * La colonna e' quella la cui intestazione parla di codice fiscale ("CF soggetto", "Codice fiscale"...);
     * se non ce n'e' una, la colonna A.
     */
    public static List<String> leggiCodiciFiscali(Path file) throws IOException {
        Set<String> codici = new LinkedHashSet<>();
        DataFormatter fmt = new DataFormatter();
        try (InputStream in = Files.newInputStream(file); Workbook wb = WorkbookFactory.create(in)) {
            Sheet foglio = wb.getSheetAt(0);
            int colonna = 0;
            Row testata = foglio.getRow(0);
            if (testata != null) {
                for (Cell c : testata) {
                    String t = fmt.formatCellValue(c).trim().toLowerCase();
                    if (t.matches("cf\\b.*|.*cod.*fisc.*")) {
                        colonna = c.getColumnIndex();
                        break;
                    }
                }
            }
            for (Row riga : foglio) {
                if (riga.getRowNum() == 0) {
                    continue;
                }
                String cf = fmt.formatCellValue(riga.getCell(colonna)).trim().toUpperCase();
                if (!cf.isEmpty()) {
                    // Excel toglie gli zeri iniziali alle partite IVA salvate come numero
                    codici.add(cf.matches("\\d{1,10}") ? "0".repeat(11 - cf.length()) + cf : cf);
                }
            }
        }
        return new ArrayList<>(codici);
    }

    /** Scrive una tabella con tutte le celle come testo (non si perdono gli zeri iniziali). */
    public static void scriviTabella(Path xlsx, List<String> testata, List<List<String>> righe) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet foglio = wb.createSheet("Dati");
            CellStyle grassetto = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            grassetto.setFont(font);
            Row prima = foglio.createRow(0);
            for (int i = 0; i < testata.size(); i++) {
                Cell c = prima.createCell(i);
                c.setCellValue(testata.get(i));
                c.setCellStyle(grassetto);
                foglio.setColumnWidth(i, 24 * 256);
            }
            int n = 1;
            for (List<String> campi : righe) {
                Row riga = foglio.createRow(n++);
                for (int i = 0; i < campi.size(); i++) {
                    riga.createCell(i).setCellValue(campi.get(i));
                }
            }
            foglio.createFreezePane(0, 1);
            foglio.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, n - 1, 0, testata.size() - 1));
            try (OutputStream out = Files.newOutputStream(xlsx)) {
                wb.write(out);
            }
        }
    }

    public static void scriviReport(Path file, List<RigaConfronto> righe) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet foglio = wb.createSheet("Confronto");
            CellStyle grassetto = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            grassetto.setFont(font);

            Row testata = foglio.createRow(0);
            for (int i = 0; i < INTESTAZIONI.length; i++) {
                Cell c = testata.createCell(i);
                c.setCellValue(INTESTAZIONI[i]);
                c.setCellStyle(grassetto);
            }

            int n = 1;
            for (RigaConfronto r : righe) {
                Row riga = foglio.createRow(n++);
                riga.createCell(0).setCellValue(r.codiceFiscale());
                riga.createCell(1).setCellValue(r.matricola());
                riga.createCell(2).setCellValue(r.denominazioneAde());
                riga.createCell(3).setCellValue(r.denominazioneInps());
                if (r.similarita() != null) {
                    riga.createCell(4).setCellValue(Math.round(r.similarita() * 100) / 100.0);
                }
                riga.createCell(5).setCellValue(r.esitoDenominazione().name());
                riga.createCell(6).setCellValue(r.atecoAde());
                riga.createCell(7).setCellValue(r.atecoSecondariAde());
                riga.createCell(8).setCellValue(r.atecoInps());
                riga.createCell(9).setCellValue(r.esitoAteco().name());
                riga.createCell(10).setCellValue(r.note() == null ? "" : r.note());
            }

            foglio.createFreezePane(0, 1);
            foglio.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, Math.max(0, n - 1), 0, INTESTAZIONI.length - 1));
            for (int i = 0; i < INTESTAZIONI.length; i++) {
                foglio.setColumnWidth(i, (i == 2 || i == 3 || i == 10 ? 45 : 22) * 256);
            }
            try (OutputStream out = Files.newOutputStream(file)) {
                wb.write(out);
            }
        }
    }
}
