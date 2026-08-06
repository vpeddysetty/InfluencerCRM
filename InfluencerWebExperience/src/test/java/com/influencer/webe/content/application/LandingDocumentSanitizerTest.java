package com.influencer.webe.content.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public landing page is an anonymous, unauthenticated surface that serves
 * brand-authored HTML as markup. These tests pin the filtering behaviour that replaced
 * the old escape-everything guarantee.
 */
class LandingDocumentSanitizerTest {

    private final LandingDocumentSanitizer sanitizer = new LandingDocumentSanitizer();

    // ---- what must survive ---------------------------------------------

    @Test
    @DisplayName("keeps the tags a landing page is actually built from")
    void keepsLayoutAndContentTags() {
        String html = "<section class=\"hero\"><h1>Title</h1><h2>Sub</h2><p>Body</p>"
                + "<ul><li>One</li></ul><a href=\"https://example.com\">Shop</a>"
                + "<img src=\"https://cdn.example.com/a.png\" alt=\"a\"></section>";
        String out = sanitizer.sanitizeHtml(html);

        assertThat(out).contains("<section", "<h1>Title</h1>", "<h2>Sub</h2>", "<p>Body</p>",
                "<li>One</li>", "https://example.com", "https://cdn.example.com/a.png");
    }

    @Test
    @DisplayName("keeps inline style and class — the builder expresses layout with them")
    void keepsStyleAndClassAttributes() {
        String out = sanitizer.sanitizeHtml(
                "<div class=\"row\" style=\"display:flex;gap:12px\">x</div>");

        assertThat(out).contains("class=\"row\"").contains("display:flex");
    }

    @Test
    @DisplayName("keeps data-gjs attributes so a rendered page stays round-trippable")
    void keepsBuilderDataAttributes() {
        String out = sanitizer.sanitizeHtml("<div data-gjs-type=\"text\" data-block=\"hero\">x</div>");

        assertThat(out).contains("data-gjs-type").contains("data-block");
    }

    // ---- what must not ---------------------------------------------------

    @Test
    @DisplayName("drops script tags")
    void dropsScript() {
        String out = sanitizer.sanitizeHtml("<p>ok</p><script>alert(1)</script>");

        assertThat(out).contains("<p>ok</p>").doesNotContain("<script").doesNotContain("alert(1)");
    }

    @Test
    @DisplayName("drops inline event handlers")
    void dropsEventHandlers() {
        String out = sanitizer.sanitizeHtml("<div onclick=\"steal()\" onerror=\"x()\">hi</div>");

        assertThat(out).doesNotContain("onclick").doesNotContain("onerror").doesNotContain("steal()");
    }

    @Test
    @DisplayName("drops javascript: URLs on links")
    void dropsJavascriptHref() {
        String out = sanitizer.sanitizeHtml("<a href=\"javascript:alert(1)\">click</a>");

        assertThat(out).doesNotContain("javascript:");
    }

    @Test
    @DisplayName("drops iframe, object and embed")
    void dropsEmbeddedContent() {
        String out = sanitizer.sanitizeHtml(
                "<iframe src=\"https://evil.test\"></iframe><object data=\"x\"></object><embed src=\"y\">");

        assertThat(out).doesNotContain("<iframe").doesNotContain("<object").doesNotContain("<embed");
    }

    @Test
    @DisplayName("drops forms — a landing page must not post credentials anywhere")
    void dropsForms() {
        String out = sanitizer.sanitizeHtml(
                "<form action=\"https://evil.test\"><input name=\"password\"></form>");

        assertThat(out).doesNotContain("<form").doesNotContain("<input");
    }

    @Test
    @DisplayName("survives the img/onerror payload that defeats naive tag stripping")
    void dropsImgOnError() {
        String out = sanitizer.sanitizeHtml("<img src=x onerror=alert(1)>");

        assertThat(out).doesNotContain("onerror").doesNotContain("alert(1)");
    }

    // ---- CSS -------------------------------------------------------------

    @Test
    @DisplayName("keeps ordinary CSS")
    void keepsPlainCss() {
        String css = ".hero{padding:40px;background:#eef2ff}@media (max-width:640px){.hero{padding:16px}}";

        assertThat(sanitizer.sanitizeCss(css)).isEqualTo(css);
    }

    @Test
    @DisplayName("drops the whole stylesheet when it contains something executable")
    void dropsDangerousCss() {
        // Dropping wholesale rather than excising: partial repair of CSS invites bypasses,
        // and an unstyled page is a visible, safe failure.
        assertThat(sanitizer.sanitizeCss(".a{width:expression(alert(1))}")).isEmpty();
        assertThat(sanitizer.sanitizeCss(".a{background:url(javascript:alert(1))}")).isEmpty();
        assertThat(sanitizer.sanitizeCss("@import url('https://evil.test/x.css');")).isEmpty();
        assertThat(sanitizer.sanitizeCss(".a{behavior:url(#default#time2)}")).isEmpty();
        assertThat(sanitizer.sanitizeCss(".a{-moz-binding:url(https://evil.test/x.xml#e)}")).isEmpty();
    }

    @Test
    @DisplayName("neutralizes a literal </style> so it cannot close the block early")
    void neutralizesStyleClose() {
        String out = sanitizer.sanitizeCss(".a{color:red}</style><script>alert(1)</script>");

        assertThat(out).doesNotContain("</style>");
    }

    // ---- the render-path branch -----------------------------------------

    @Test
    @DisplayName("hasRenderableHtml drives the builder-vs-legacy choice")
    void hasRenderableHtmlDetectsRealContent() {
        assertThat(sanitizer.hasRenderableHtml(null)).isFalse();
        assertThat(sanitizer.hasRenderableHtml("")).isFalse();
        // Nothing but disallowed markup must fall back to the legacy renderer rather than
        // serving a blank page.
        assertThat(sanitizer.hasRenderableHtml("<script>alert(1)</script>")).isFalse();
        assertThat(sanitizer.hasRenderableHtml("<p>real</p>")).isTrue();
    }
}
