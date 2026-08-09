package com.influencer.webe.content.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.content.application.AssetService;
import com.influencer.webe.shared.application.RequestUserResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Brand asset library (roadmap Phase B).
 *
 * <p>Uploads are brand-authenticated; the serving path is not, because an image referenced
 * by a public landing page must load for anonymous visitors. That is safe because storage
 * keys are random UUIDs under a brand prefix — unguessable, and the only way to learn one is
 * to be shown a page that already references it.
 */
@RestController
public class AssetsController {
    private final AssetService assetService;
    private final RequestUserResolver requestUserResolver;

    public AssetsController(AssetService assetService, RequestUserResolver requestUserResolver) {
        this.assetService = assetService;
        this.requestUserResolver = requestUserResolver;
    }

    @GetMapping("/api/assets")
    public JsonNode list(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestParam(required = false) UUID brandId) {
        return assetService.list(requestUserResolver.resolveBrandId(authorization, brandId));
    }

    @PostMapping(value = "/api/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JsonNode upload(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestPart("file") MultipartFile file,
                           @RequestParam(required = false) UUID brandId) {
        return assetService.upload(requestUserResolver.resolveBrandId(authorization, brandId), file);
    }

    @DeleteMapping("/api/assets/{id}")
    public ResponseEntity<Void> delete(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable UUID id,
                                       @RequestParam(required = false) UUID brandId) {
        assetService.delete(requestUserResolver.resolveBrandId(authorization, brandId), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Serve asset bytes (local filesystem adapter only).
     *
     * <p>Public by necessity: a landing page's images have to load for anonymous visitors.
     * The brand segment is part of the key, and {@code AssetService.readBytes} checks that the
     * rest of the key sits under it, so this cannot be walked into another tenant's prefix.
     *
     * <p>An S3/CDN deployment serves bytes directly from the bucket and never reaches this.
     */
    @GetMapping("/assets/{brandId}/{name}")
    public ResponseEntity<byte[]> serve(@PathVariable UUID brandId, @PathVariable String name) {
        byte[] bytes = assetService.readBytes(brandId, brandId + "/" + name);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, guessType(name))
                // Immutable: the key is a random UUID and bytes are never rewritten under it,
                // so a long cache is safe and keeps repeat page loads off the origin.
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                // Defence in depth: even though only sniffed image types are stored, telling
                // the browser not to re-interpret the type closes the content-sniffing gap.
                .header("X-Content-Type-Options", "nosniff")
                .body(bytes);
    }

    private String guessType(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".avif")) return "image/avif";
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
}
