package com.influencer.webe.client;

import com.influencer.webe.config.WebExperienceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.net.http.HttpClient;

/**
 * Builds the HTTP client used for BFF → DAO calls, with real certificate verification.
 *
 * <p>Both DAO clients previously installed a trust-all {@code X509TrustManager}, which disabled
 * certificate verification entirely and left every service-to-service call open to interception.
 * Verification is now on by default. The DAO's self-signed development certificate is handled the
 * correct way — by trusting it explicitly via {@code web-experience.dao-trust-store} — rather than
 * by trusting everything.
 */
@Component
public class DaoHttpClientFactory {
    private static final Logger log = LoggerFactory.getLogger(DaoHttpClientFactory.class);
    private static final String CLASSPATH_PREFIX = "classpath:";

    private final WebExperienceProperties properties;

    public DaoHttpClientFactory(WebExperienceProperties properties) {
        this.properties = properties;
    }

    public HttpClient create() {
        return HttpClient.newBuilder()
                .sslContext(buildSslContext())
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private SSLContext buildSslContext() {
        if (!properties.isDaoTlsVerificationEnabled()) {
            log.error("TLS verification for DAO calls is DISABLED "
                    + "(web-experience.dao-tls-verification-enabled=false). "
                    + "This accepts any certificate and must never be used outside local development.");
            return insecureSslContext();
        }

        String trustStorePath = properties.getDaoTrustStore();
        if (trustStorePath == null || trustStorePath.isBlank()) {
            // Default JVM trust store — correct for a DAO presenting a CA-issued certificate.
            return defaultSslContext();
        }

        return trustStoreSslContext(trustStorePath, properties.getDaoTrustStorePassword());
    }

    private SSLContext defaultSslContext() {
        try {
            return SSLContext.getDefault();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load the default SSL context", exception);
        }
    }

    private SSLContext trustStoreSslContext(String trustStorePath, String password) {
        try (InputStream input = openTrustStore(trustStorePath)) {
            KeyStore trustStore = KeyStore.getInstance(trustStorePath.endsWith(".p12") ? "PKCS12" : "JKS");
            trustStore.load(input, password == null ? null : password.toCharArray());

            TrustManagerFactory factory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trustStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, factory.getTrustManagers(), new SecureRandom());
            log.info("DAO TLS verification enabled using trust store {}", trustStorePath);
            return sslContext;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to load DAO trust store " + trustStorePath, exception);
        }
    }

    /** Accepts either a {@code classpath:} resource or a filesystem path. */
    private InputStream openTrustStore(String trustStorePath) throws IOException {
        if (trustStorePath.startsWith(CLASSPATH_PREFIX)) {
            String resource = trustStorePath.substring(CLASSPATH_PREFIX.length());
            InputStream input = getClass().getClassLoader().getResourceAsStream(resource);
            if (input == null) {
                throw new IllegalStateException(
                        "web-experience.dao-trust-store references a classpath resource that does not exist: " + resource);
            }
            return input;
        }

        Path path = Path.of(trustStorePath);
        if (!Files.exists(path)) {
            throw new IllegalStateException(
                    "web-experience.dao-trust-store points at a file that does not exist: " + trustStorePath);
        }
        return Files.newInputStream(path);
    }

    /**
     * Escape hatch for local development against a self-signed DAO certificate when no trust store
     * has been prepared. Gated behind an explicit opt-in property and logged loudly at ERROR.
     */
    private SSLContext insecureSslContext() {
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
            return sslContext;
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create SSL context", exception);
        }
    }
}
