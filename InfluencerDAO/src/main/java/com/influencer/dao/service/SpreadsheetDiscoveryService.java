package com.influencer.dao.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import com.influencer.dao.model.ImportBatch;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class SpreadsheetDiscoveryService {
    public DiscoveredSpreadsheet discover(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file cannot be empty");
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file must have a filename");
        }

        String lowerFilename = filename.toLowerCase();
        try {
            if (lowerFilename.endsWith(".csv")) {
                return discoverCsv(filename, file.getInputStream());
            }
            if (lowerFilename.endsWith(".xlsx")) {
                return discoverXlsx(filename, file.getInputStream());
            }
            if (lowerFilename.endsWith(".xls")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ".xls files are not supported; please convert to .xlsx");
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only CSV/XLSX/XLS files are supported");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to read spreadsheet file", exception);
        }
    }

    private DiscoveredSpreadsheet discoverCsv(String filename, InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No columns were found in the uploaded file");
            }

            List<String> columns = parseCsvLine(headerLine);
            int rowCount = 0;
            while (reader.readLine() != null) {
                rowCount++;
            }
            return new DiscoveredSpreadsheet(filename, columns, rowCount);
        }
    }

    private DiscoveredSpreadsheet discoverXlsx(String filename, InputStream inputStream) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No worksheets were found in the uploaded file");
            }

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No columns were found in the uploaded file");
            }

            DataFormatter formatter = new DataFormatter();
            List<String> columns = new ArrayList<>();
            for (Cell cell : headerRow) {
                String value = formatter.formatCellValue(cell).trim();
                if (!value.isEmpty()) {
                    columns.add(value);
                }
            }

            if (columns.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No columns were found in the uploaded file");
            }

            int rowCount = Math.max(0, sheet.getLastRowNum() - sheet.getFirstRowNum());
            return new DiscoveredSpreadsheet(filename, columns, rowCount);
        }
    }

    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;

        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    current.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
                continue;
            }
            if (character == ',' && !quoted) {
                addCsvValue(values, current);
                continue;
            }
            current.append(character);
        }

        addCsvValue(values, current);
        return values;
    }

    private void addCsvValue(List<String> values, StringBuilder current) {
        String value = current.toString().trim();
        if (!value.isEmpty()) {
            values.add(value);
        }
        current.setLength(0);
    }

    public static class DiscoveredSpreadsheet {
        private final String sourceFilename;
        private final List<String> columns;
        private final int rowCount;

        public DiscoveredSpreadsheet(String sourceFilename, List<String> columns, int rowCount) {
            this.sourceFilename = sourceFilename;
            this.columns = columns;
            this.rowCount = rowCount;
        }

        public String getSourceFilename() {
            return sourceFilename;
        }

        public List<String> getColumns() {
            return columns;
        }

        public int getRowCount() {
            return rowCount;
        }
    }

    public static class DiscoverImportBatchResponse {
        private final ImportBatch importBatch;
        private final List<String> columns;

        public DiscoverImportBatchResponse(ImportBatch importBatch, List<String> columns) {
            this.importBatch = importBatch;
            this.columns = columns;
        }

        public ImportBatch getImportBatch() {
            return importBatch;
        }

        public List<String> getColumns() {
            return columns;
        }
    }
}