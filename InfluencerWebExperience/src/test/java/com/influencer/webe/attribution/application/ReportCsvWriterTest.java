package com.influencer.webe.attribution.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report a client actually receives (roadmap PR-65).
 *
 * <p>The behaviour that matters most here is not the formatting. It is that a creator's NAME is
 * attacker-influenced text — anyone can call themselves {@code =HYPERLINK(...)} — and it ends up in
 * a spreadsheet an agency forwards to its client, carrying the agency's name. Excel and Sheets
 * execute a field opening with {@code = + - @}, so the export is a delivery mechanism unless it is
 * neutralised.
 */
class ReportCsvWriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ReportCsvWriter writer = new ReportCsvWriter();

    private final List<ReportCsvWriter.Column> columns = List.of(
            new ReportCsvWriter.Column("Client", "brandName"),
            new ReportCsvWriter.Column("Revenue", "revenue"));

    private ArrayNode rows(String... brandThenRevenue) {
        ArrayNode rows = MAPPER.createArrayNode();
        for (int i = 0; i < brandThenRevenue.length; i += 2) {
            rows.addObject()
                    .put("brandName", brandThenRevenue[i])
                    .put("revenue", brandThenRevenue[i + 1]);
        }
        return rows;
    }

    @Test
    @DisplayName("a formula is neutralised, not executed by the client's spreadsheet")
    void formulaInjectionIsNeutralised() {
        String csv = writer.toCsv(columns, rows("=HYPERLINK(\"http://evil\",\"click\")", "10"));

        // The tab makes Excel treat it as text. It still DISPLAYS the same, which is what makes
        // this safe rather than lossy -- the reader sees what the creator typed.
        assertTrue(csv.contains("\t=HYPERLINK"), "a leading = must be prefixed with a tab");
        assertFalse(csv.contains(",=HYPERLINK"), "it must never reach a cell unprefixed");
    }

    @Test
    @DisplayName("every dangerous lead character is covered, not just equals")
    void allFormulaLeadsAreCovered() {
        for (String lead : List.of("=", "+", "-", "@")) {
            String csv = writer.toCsv(columns, rows(lead + "cmd", "1"));
            assertTrue(csv.contains("\t" + lead + "cmd"),
                    "a field opening with " + lead + " must be prefixed");
        }
    }

    @Test
    @DisplayName("a comma or quote in a name does not shift every later column")
    void delimitersAreQuoted() {
        // The silent version of this bug: one creator named "Smith, Jane" and every figure after it
        // lands in the wrong column, in a document nobody re-checks against the screen.
        String csv = writer.toCsv(columns, rows("Smith, Jane", "10"));

        assertTrue(csv.contains("\"Smith, Jane\""));
        // The comma inside the quotes must not split the row: parsed properly this is two cells,
        // the client name and the figure, not three with the revenue shifted into a phantom column.
        assertEquals("\"Smith, Jane\",10", csv.split("\r\n")[1]);
    }

    @Test
    @DisplayName("an embedded quote is doubled, per RFC 4180")
    void quotesAreDoubled() {
        String csv = writer.toCsv(columns, rows("The \"Real\" Deal", "10"));

        assertTrue(csv.contains("\"The \"\"Real\"\" Deal\""));
    }

    @Test
    @DisplayName("a missing figure is an EMPTY cell, never a zero")
    void missingIsBlankNotZero() {
        // The same rule the portfolio applies on screen. A client whose figures could not be read
        // did not sell nothing, and a 0 in a spreadsheet forwarded to that client is a false claim
        // nobody will re-check against the source.
        ArrayNode rows = MAPPER.createArrayNode();
        rows.addObject().put("brandName", "Unavailable Co");   // no revenue field at all

        String csv = writer.toCsv(columns, rows);

        assertTrue(csv.endsWith("Unavailable Co,\r\n"), "the revenue cell must be empty, got: " + csv);
    }

    @Test
    @DisplayName("rows keep the order they arrived in")
    void orderIsPreserved() {
        // The file has to agree with the screen it was exported from; re-sorting here would make
        // an agency's PDF and its CSV disagree about which client is first.
        String csv = writer.toCsv(columns, rows("Zeta", "1", "Alpha", "2"));
        String[] lines = csv.split("\r\n");

        assertTrue(lines[1].startsWith("Zeta"));
        assertTrue(lines[2].startsWith("Alpha"));
    }

    @Test
    @DisplayName("the file opens with a BOM and uses CRLF, because Excel is the consumer")
    void excelCompatibility() {
        String csv = writer.toCsv(columns, rows("Aurora", "10"));

        assertTrue(csv.startsWith("﻿"), "without the BOM an accented name arrives as mojibake");
        assertTrue(csv.contains("\r\n"), "RFC 4180 specifies CRLF");
    }

    @Test
    @DisplayName("no rows still produces a header, so the file is readable rather than empty")
    void emptyReportStillHasAHeader() {
        String csv = writer.toCsv(columns, MAPPER.createArrayNode());

        assertTrue(csv.contains("Client,Revenue"));
    }

    @Test
    @DisplayName("the filename carries the date, so a client sent several can tell them apart")
    void filenameIsDated() {
        assertEquals("portfolio-2026-09-05.csv",
                writer.filename("portfolio", LocalDate.of(2026, 9, 5)));
    }
}
