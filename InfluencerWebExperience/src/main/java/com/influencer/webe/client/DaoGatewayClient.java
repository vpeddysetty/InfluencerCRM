package com.influencer.webe.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.influencer.webe.config.WebExperienceProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DaoGatewayClient {
    private final WebExperienceProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DaoGatewayClient(WebExperienceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = buildHttpClient();
    }

    public JsonNode get(String path, Map<String, String> query) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(buildUri(path, query))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        return send(request, "GET", path);
    }

    public JsonNode post(String path, JsonNode payload) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(buildUri(path, null))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(payload)))
                .build();
        return send(request, "POST", path);
    }

    public JsonNode put(String path, JsonNode payload) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(buildUri(path, null))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(writeJson(payload)))
                .build();
        return send(request, "PUT", path);
    }

    public JsonNode patch(String path, JsonNode payload) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(buildUri(path, null))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(writeJson(payload)))
                .build();
        return send(request, "PATCH", path);
    }

    public void delete(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(buildUri(path, null))
                .timeout(Duration.ofSeconds(20))
                .DELETE()
                .build();
        sendAllowNoContent(request, "DELETE", path);
    }

    public JsonNode postMultipart(String path, Map<String, String> fields, String fileFieldName, String fileName, byte[] bytes, String contentType) {
        String boundary = "----WebExperienceBoundary" + System.currentTimeMillis();
        List<MultipartFilePart> files = List.of(new MultipartFilePart(fileFieldName, fileName, bytes, contentType));
        byte[] body = MultipartBodyBuilder.build(boundary, fields, files);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(buildUri(path, null))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return send(request, "POST", path);
    }

    public JsonNode postMultipartFiles(String path, Map<String, String> fields, List<MultipartFilePart> files) {
        String boundary = "----WebExperienceBoundary" + System.currentTimeMillis();
        byte[] body = MultipartBodyBuilder.build(boundary, fields, files);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(buildUri(path, null))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return send(request, "POST", path);
    }

    public static class MultipartFilePart {
        private final String fieldName;
        private final String fileName;
        private final byte[] bytes;
        private final String contentType;

        public MultipartFilePart(String fieldName, String fileName, byte[] bytes, String contentType) {
            this.fieldName = fieldName;
            this.fileName = fileName;
            this.bytes = bytes;
            this.contentType = contentType;
        }

        public String getFieldName() {
            return fieldName;
        }

        public String getFileName() {
            return fileName;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public String getContentType() {
            return contentType;
        }
    }

    private JsonNode send(HttpRequest request, String method, String path) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw toGatewayException(method, path, response.statusCode(), response.body());
            }
            if (response.body() == null || response.body().isBlank()) {
                return objectMapper.nullNode();
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to call DAO endpoint", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO call interrupted", exception);
        }
    }

    private void sendAllowNoContent(HttpRequest request, String method, String path) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw toGatewayException(method, path, response.statusCode(), response.body());
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Unable to call DAO endpoint", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DAO call interrupted", exception);
        }
    }

    private URI buildUri(String path, Map<String, String> query) {
        String base = properties.getDaoBaseUrl();
        StringBuilder builder = new StringBuilder(base).append(path);
        if (query != null && !query.isEmpty()) {
            Map<String, String> nonBlank = new LinkedHashMap<>();
            for (Map.Entry<String, String> entry : query.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    nonBlank.put(entry.getKey(), entry.getValue());
                }
            }
            if (!nonBlank.isEmpty()) {
                builder.append("?");
                boolean first = true;
                for (Map.Entry<String, String> entry : nonBlank.entrySet()) {
                    if (!first) {
                        builder.append("&");
                    }
                    first = false;
                    builder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                    builder.append("=");
                    builder.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
                }
            }
        }
        return URI.create(builder.toString());
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to serialize request", exception);
        }
    }

    private ResponseStatusException toGatewayException(String method, String path, int daoStatusCode, String body) {
        HttpStatus mappedStatus = mapStatus(daoStatusCode);
        String normalizedBody = body == null ? "" : body;
        String reason = "DAO " + method + " " + path + " failed with status " + daoStatusCode + ": " + normalizedBody;

        if (daoStatusCode == 409 && path.contains("/influencer-campaign-codes")) {
            reason = "Campaign code already exists for this campaign/user context";
        }
        return new ResponseStatusException(mappedStatus, reason);
    }

    private HttpStatus mapStatus(int daoStatusCode) {
        if (daoStatusCode == 400) {
            return HttpStatus.BAD_REQUEST;
        }
        if (daoStatusCode == 401) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (daoStatusCode == 403) {
            return HttpStatus.FORBIDDEN;
        }
        if (daoStatusCode == 404) {
            return HttpStatus.NOT_FOUND;
        }
        if (daoStatusCode == 409) {
            return HttpStatus.CONFLICT;
        }
        return HttpStatus.BAD_GATEWAY;
    }

    private HttpClient buildHttpClient() {
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new SecureRandom());

            return HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create HTTP client", exception);
        }
    }

    private static class MultipartBodyBuilder {
        static byte[] build(String boundary, Map<String, String> fields, List<MultipartFilePart> files) {
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();

                if (fields != null) {
                    for (Map.Entry<String, String> entry : fields.entrySet()) {
                        output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                        output.write(("Content-Disposition: form-data; name=\"" + entry.getKey() + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                        output.write("\r\n".getBytes(StandardCharsets.UTF_8));
                    }
                }

                List<MultipartFilePart> safeFiles = files == null ? new ArrayList<>() : files;
                for (MultipartFilePart file : safeFiles) {
                    if (file == null || file.getBytes() == null) {
                        continue;
                    }
                    output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write(("Content-Disposition: form-data; name=\"" + file.getFieldName() + "\"; filename=\"" + file.getFileName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
                    String resolvedContentType = (file.getContentType() == null || file.getContentType().isBlank())
                            ? "application/octet-stream"
                            : file.getContentType();
                    output.write(("Content-Type: " + resolvedContentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write(file.getBytes());
                    output.write("\r\n".getBytes(StandardCharsets.UTF_8));
                }

                output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                return output.toByteArray();
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to build multipart body", exception);
            }
        }
    }
}
