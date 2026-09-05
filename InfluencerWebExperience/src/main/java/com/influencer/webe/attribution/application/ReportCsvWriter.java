package com.influencer.webe.attribution.application;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Turns a report into a CSV a client can be sent (roadmap PR-65).
 *
 * <p><b>Server-side, and deliberately not in the browser.</b> The shell already has a client-side
 * {@code api/csv.js} used for exporting a table someone is looking at, and that stays. This is a
 * different thing: what counts as revenue, how commission nets against a discount (`OP-21`), and
 * what a date window includes (`OP-39`) are decisions this context owns. A browser export can only
 * serialise the rows a page happened to fetch; this exports what the domain says is true, once,
 * rather than once per micro-frontend.
 *
 * <p><b>CSV injection is neutralised.</b> A field opening with {@code = + - @} is interpreted as a
 * formula by Excel and Sheets, so {@code =HYPERLINK(...)} in a creator's name becomes code running
 * on the client's machine — from a file our customer sent them, carrying our customer's name. A
 * leading tab neutralises it and displays identically. The same reasoning is recorded in
 * {@code api/csv.js}; both exist because both paths exist, and neither is the other's fallback.
 *
 * <p><b>CRLF and a UTF-8 BOM.</b> RFC 4180 specifies CRLF, and Excel on Windows is the dominant
 * consumer and the least forgiving. Without the BOM Excel reads UTF-8 as the local codepage and a
 * creator name carrying an accent arrives as mojibake in a document an agency forwards to a client.
 */
@Service
public class ReportCsvWriter {

    private static final String CRLF = "\r\n";

    /** Excel needs this to read the file as UTF-8 rather than the local codepage. */
    private static final String BOM = "﻿";

    /** One column: the header a reader sees, and the JSON field it comes from. */
    public record Column(String header, String field) {
    }

    /**
     * A header row plus one row per element, in the order given.
     *
     * <p>Order is the caller's, not this class's: a portfolio's rows arrive in the same order the
     * brand switcher lists them, and re-sorting here would make the file disagree with the screen
     * it was exported from.
     */
    public String toCsv(List<Column> columns, JsonNode rows) {
        StringBuilder out = new StringBuilder(BOM);

        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(escape(columns.get(i).header()));
        }
        out.append(CRLF);

        if (rows != null && rows.isArray()) {
            for (JsonNode row : rows) {
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    out.append(escape(valueOf(row, columns.get(i).field())));
                }
                out.append(CRLF);
            }
        }
        return out.toString();
    }

    /**
     * A missing field is an EMPTY cell, not a zero.
     *
     * <p>The same rule the portfolio applies on screen: a client whose figures could not be read
     * has no revenue, and writing 0 into that cell would state that they sold nothing. A blank cell
     * in a spreadsheet an agency forwards to its client is honest; a zero is a claim.
     */
    private String valueOf(JsonNode row, String field) {
        if (row == null || !row.hasNonNull(field)) {
            return "";
        }
        return row.get(field).asText();
    }

    private String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String text = value;
        // Formula injection. Checked BEFORE quoting, because quoting alone does not stop Excel
        // interpreting the content once it strips the quotes.
        if (text.startsWith("=") || text.startsWith("+") || text.startsWith("-")
                || text.startsWith("@") || text.startsWith("\t") || text.startsWith("\r")) {
            text = "\t" + text;
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    /** {@code portfolio-2026-09-05.csv} — dated, so a client sent several can tell them apart. */
    public String filename(String prefix, java.time.LocalDate on) {
        return prefix + "-" + on + ".csv";
    }
}
