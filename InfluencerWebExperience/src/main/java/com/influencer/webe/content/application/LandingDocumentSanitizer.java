package com.influencer.webe.content.application;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Sanitizes the HTML and CSS a visual builder produces, before it is served on the
 * public page (roadmap Phase A).
 *
 * <p><b>Why this class exists.</b> The typed-block renderer was safe by construction:
 * it emitted a fixed set of tags and escaped every dynamic value, so stored data could
 * never become markup. A visual builder inverts that — its output <i>is</i> markup, and
 * escaping it would render tags as literal text. The safety property therefore has to be
 * re-established by filtering against an allow-list on the way out.
 *
 * <p><b>Sanitize on output, not on input.</b> Sanitizing on save would leave the database
 * as the trust boundary, so anything written by another path (an import, a migration, a
 * direct DAO call, a future co-editor) would be served unfiltered. Filtering at render
 * means the guarantee holds regardless of how the row got there. It costs a parse per
 * request, which is cheap next to the DAO round-trips the same request already makes.
 */
@Component
public class LandingDocumentSanitizer {

    /**
     * Tags and attributes a landing page legitimately needs.
     *
     * <p>Built from {@code Safelist.basicWithImages()} — which already excludes script,
     * iframe, object, embed, form and every event-handler attribute — plus the layout
     * and semantic tags a page builder emits. {@code style} and {@code class} are allowed
     * on structural elements because that is how a visual builder expresses layout; the
     * CSS itself is filtered separately by {@link #sanitizeCss(String)}.
     */
    private static final Safelist SAFELIST = Safelist.basicWithImages()
            // `div` and headings are NOT in jsoup's `basic` list — it targets user comments,
            // where letting a commenter emit layout containers or an <h1> would wreck the
            // host page. A landing page IS the document, so these are its primary structure.
            // Omitting `div` in particular would flatten almost every builder page, since a
            // visual builder nests divs for layout; that was caught by the tests below.
            .addTags("div", "h1", "h2", "h3", "h4", "h5", "h6",
                     "section", "header", "footer", "main", "article", "aside", "nav",
                     "figure", "figcaption", "hr", "span", "table", "thead", "tbody",
                     "tr", "td", "th", "button", "label")
            .addAttributes(":all", "class", "id", "style", "title")
            .addAttributes("img", "src", "alt", "width", "height", "loading")
            .addAttributes("a", "href", "target", "rel")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("th", "colspan", "rowspan", "scope")
            // Data attributes are how the builder tags a block for later editing. They are
            // inert in the browser, and preserving them keeps a rendered page round-trippable.
            .addAttributes(":all", "data-gjs-type", "data-block", "data-block-id")
            // Video, for a brand's own uploaded footage (roadmap PR-35 media blocks). Added
            // narrowly and deliberately WITHOUT iframe: an iframe would let a page embed any
            // third-party origin — an ad network, a tracker, an attacker's page — inside a
            // document served under the brand's name. A <video> element plays bytes from a URL
            // and executes nothing, which is the whole difference.
            //
            // `controls` is the only interaction attribute allowed. `autoplay` is deliberately
            // absent: a landing page that starts playing sound on open is a page visitors close.
            .addTags("video", "source")
            .addAttributes("video", "src", "poster", "width", "height", "controls",
                           "muted", "playsinline", "preload")
            .addAttributes("source", "src", "type")
            .addProtocols("video", "src", "http", "https")
            .addProtocols("video", "poster", "http", "https")
            .addProtocols("source", "src", "http", "https")
            .addProtocols("img", "src", "http", "https", "data")
            .addProtocols("a", "href", "http", "https", "mailto", "tel");

    /**
     * CSS constructs that can execute script or fetch a remote resource.
     *
     * <p>An allow-list would be safer in principle, but CSS has no small grammar to allow
     * and a visual builder emits arbitrary declarations. These are the constructs that turn
     * a stylesheet into a script or a network call:
     * <ul>
     *   <li>{@code expression()} — IE-era CSS-as-JS; still worth blocking for old clients.</li>
     *   <li>{@code javascript:} / {@code vbscript:} — script URLs inside url().</li>
     *   <li>{@code @import} — pulls in a stylesheet we have not filtered.</li>
     *   <li>{@code behavior} / {@code -moz-binding} — bind script to an element.</li>
     * </ul>
     * {@code </style>} is neutralized separately in {@link #sanitizeCss(String)}, since a
     * literal closing tag inside the block would end the element and let raw markup follow.
     */
    private static final Pattern DANGEROUS_CSS = Pattern.compile(
            "(?i)(expression\\s*\\(|javascript\\s*:|vbscript\\s*:|@import|behavior\\s*:|-moz-binding)");

    /**
     * Filter builder HTML to the allow-list.
     *
     * @param html raw HTML from the stored document; may be null
     * @return sanitized HTML, never null
     */
    public String sanitizeHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        // prettyPrint(false): the builder's output is whitespace-sensitive once inline-block
        // and white-space rules are in play, and reformatting it changes rendering.
        Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false);
        return Jsoup.clean(html, "", SAFELIST, settings);
    }

    /**
     * Filter builder CSS.
     *
     * <p>Returns an empty string when anything dangerous is present rather than trying to
     * excise it. Partial repair of a stylesheet invites bypasses through nesting and
     * encoding; dropping the block degrades the page to unstyled, which is visible,
     * recoverable, and safe. The page still renders.
     *
     * @param css raw CSS from the stored document; may be null
     * @return sanitized CSS, never null
     */
    public String sanitizeCss(String css) {
        if (css == null || css.isBlank()) {
            return "";
        }
        if (DANGEROUS_CSS.matcher(css).find()) {
            return "";
        }
        // A literal "</style>" (in any casing) would close the element and let whatever
        // follows be parsed as markup. Breaking the sequence keeps it inside the block.
        return css.replaceAll("(?i)</\\s*style", "<\\\\/style");
    }

    /**
     * True when a stored document has something to render.
     *
     * <p>Used by the renderer to decide between the builder path and the legacy typed-block
     * path. A document whose HTML sanitizes away to nothing is treated as absent, so a page
     * containing only disallowed markup falls back rather than serving a blank page.
     */
    public boolean hasRenderableHtml(String html) {
        return !sanitizeHtml(html).isBlank();
    }

    /** Lowercase, trimmed, null-safe — for comparing stage/status values. */
    static String norm(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
