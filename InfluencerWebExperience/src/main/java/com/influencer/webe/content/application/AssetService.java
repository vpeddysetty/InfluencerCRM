package com.influencer.webe.content.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.shared.application.PlatformMetrics;
import com.influencer.webe.shared.application.ResponseShapeService;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Brand-scoped asset library (roadmap Phase B).
 *
 * <p>Metadata goes to {@code content.assets}; bytes go to {@link AssetStoragePort}. The two
 * are not transactional with each other, and the ordering below is deliberate — see
 * {@link #upload}.
 */
@Service
public class AssetService {

    /**
     * What may be uploaded.
     *
     * <p>An allow-list, and images only. The asset library exists to put pictures on landing
     * pages; permitting arbitrary types would turn it into a file host that serves attacker
     * content from the platform's own origin. SVG is deliberately excluded — it is an XML
     * document that can carry script, so an "image" upload would reintroduce exactly the XSS
     * hole the page renderer was hardened against.
     */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/avif");

    /** 10 MB. Large enough for a hero image, small enough to bound memory per request. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    private final DaoGatewayClient dao;
    private final ResponseShapeService shape;
    private final AssetStoragePort storage;
    /** Phase H: a rejection rate climbing usually means a real format we should accept. */
    private final PlatformMetrics metrics;

    public AssetService(DaoGatewayClient dao, ResponseShapeService shape, AssetStoragePort storage,
                        PlatformMetrics metrics) {
        this.dao = dao;
        this.shape = shape;
        this.storage = storage;
        this.metrics = metrics;
    }

    /**
     * Validate, store the bytes, then record the metadata.
     *
     * <p><b>Bytes first, row second.</b> The two stores cannot be committed together, so one
     * of the two failure modes has to be chosen. Writing bytes first means a failure leaves an
     * orphaned object — invisible, costing storage, cleanable by a sweep. Writing the row
     * first would mean a failure leaves a row pointing at nothing, which renders as a broken
     * image on a live page. The invisible failure is the better one.
     */
    public JsonNode upload(UUID brandId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file uploaded");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "Asset exceeds the " + (MAX_BYTES / (1024 * 1024)) + "MB limit");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the uploaded file");
        }

        // Sniff the actual content rather than trusting the declared type. A client controls
        // its Content-Type header completely, so believing it would let an HTML document be
        // stored and later served as an "image".
        String declared = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        String sniffed = sniffImageType(bytes);
        if (sniffed == null) {
            metrics.assetUpload("rejected");
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Only PNG, JPEG, GIF, WebP and AVIF images can be uploaded");
        }
        if (!declared.isBlank() && !ALLOWED_TYPES.contains(declared)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported content type: " + declared);
        }

        String fileName = sanitizeFileName(file.getOriginalFilename());
        String key = storage.put(brandId.toString(), fileName, sniffed, bytes);

        ObjectNode body = shape.objectMapper().createObjectNode();
        body.put("brandId", brandId.toString());
        body.put("storageKey", key);
        body.put("fileName", fileName);
        body.put("contentType", sniffed);
        body.put("sizeBytes", bytes.length);
        int[] dims = probeDimensions(bytes);
        if (dims != null) {
            body.put("width", dims[0]);
            body.put("height", dims[1]);
        }

        JsonNode saved;
        try {
            saved = dao.post("/assets", body);
        } catch (RuntimeException e) {
            // The row failed, so the object is unreferenced. Remove it rather than leaving
            // litter that nothing will ever point at.
            storage.delete(key);
            throw e;
        }
        metrics.assetUpload("accepted");
        return withUrl(saved);
    }

    /** This brand's assets, newest first. */
    public JsonNode list(UUID brandId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("brandId", brandId.toString());
        JsonNode assets = dao.get("/assets", q);
        ArrayNode out = shape.objectMapper().createArrayNode();
        if (assets != null && assets.isArray()) {
            for (JsonNode asset : assets) {
                out.add(withUrl(asset));
            }
        }
        return out;
    }

    /**
     * Delete an asset.
     *
     * <p>Re-reads the row and checks the brand before touching anything: the id comes from the
     * caller, so without this check any brand could delete any other brand's asset by id.
     */
    public void delete(UUID brandId, UUID assetId) {
        JsonNode asset = dao.get("/assets/" + assetId, null);
        if (asset == null || !asset.hasNonNull("brandId")
                || !asset.get("brandId").asText().equals(brandId.toString())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found");
        }
        // Row first here, deliberately the opposite order to upload: removing the reference is
        // what the user asked for, and an orphaned object is harmless. Deleting the object
        // first would risk a row that renders as a broken image if the row delete then failed.
        dao.delete("/assets/" + assetId);
        if (asset.hasNonNull("storageKey")) {
            storage.delete(asset.get("storageKey").asText());
        }
    }

    /** Bytes for the local adapter's serving path; brand-checked. */
    public byte[] readBytes(UUID brandId, String storageKey) {
        if (storageKey == null || !storageKey.startsWith(brandId.toString() + "/")) {
            // Keys are prefixed with the owning brand, so this alone prevents one tenant
            // reading another's object by guessing a key.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found");
        }
        byte[] bytes = storage.get(storageKey);
        if (bytes == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found");
        }
        return bytes;
    }

    private JsonNode withUrl(JsonNode asset) {
        ObjectNode out = shape.objectMapper().createObjectNode();
        for (String field : new String[]{"id", "brandId", "storageKey", "fileName",
                                         "contentType", "sizeBytes", "width", "height", "createdAt"}) {
            if (asset.hasNonNull(field)) {
                out.set(field, asset.get(field));
            }
        }
        if (asset.hasNonNull("storageKey")) {
            out.put("url", storage.urlFor(asset.get("storageKey").asText()));
        }
        return out;
    }

    /**
     * Identify an image from its magic bytes.
     *
     * @return the detected MIME type, or null when the content is not a supported image
     */
    private String sniffImageType(byte[] b) {
        if (b == null || b.length < 12) {
            return null;
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A) {
            return "image/png";
        }
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // GIF87a / GIF89a
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') {
            return "image/gif";
        }
        // RIFF....WEBP
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        // ISO-BMFF 'ftyp' with an AVIF brand
        if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p'
                && b[8] == 'a' && b[9] == 'v' && b[10] == 'i' && (b[11] == 'f' || b[11] == 's')) {
            return "image/avif";
        }
        return null;
    }

    /** Width/height when decodable, else null. Best-effort — the picker degrades without it. */
    private int[] probeDimensions(byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(in);
            return image == null ? null : new int[]{image.getWidth(), image.getHeight()};
        } catch (Exception e) {
            // WebP/AVIF have no ImageIO reader by default; that is expected, not an error.
            return null;
        }
    }

    /**
     * Reduce a client-supplied name to something safe to store and display.
     *
     * <p>Only ever used for display and for its extension — the storage key is generated —
     * but it is still rendered back to users, so path separators and control characters go.
     */
    private String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "asset";
        }
        String base = original.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[\\p{Cntrl}]", "").trim();
        if (base.length() > 120) {
            base = base.substring(0, 120);
        }
        return base.isBlank() ? "asset" : base;
    }
}
