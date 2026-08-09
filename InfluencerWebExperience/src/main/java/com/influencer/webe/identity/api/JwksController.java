package com.influencer.webe.identity.api;

import com.influencer.webe.security.JwtService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Publishes the public keys that verify access tokens.
 *
 * <p>Standard JWKS discovery. Today the BFF is the only verifier, so this is not strictly needed —
 * but the moment a context service wants to verify a token itself rather than trusting the gateway,
 * it needs somewhere to fetch keys from. Publishing now means that service can be written without
 * also inventing a key-distribution mechanism, and without anyone being tempted to copy the private
 * key around.
 *
 * <p>It also makes rotation observable: during a rotation this returns both the new and the retiring
 * key, so an operator can confirm the overlap is in place before removing the old one.
 *
 * <p><strong>Public halves only.</strong> {@code publicJwkSet()} strips the private material, so
 * this endpoint cannot leak a signing key. It is unauthenticated by design — that is what
 * "public key" means, and every JWKS endpoint on the internet works this way.
 */
@RestController
public class JwksController {

    private final JwtService jwtService;

    public JwksController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        // toJSONObject(true) emits public parameters only. Passing false here would publish the
        // private key — the single most damaging one-character mistake available in this file.
        return jwtService.publicJwkSet().toJSONObject(true);
    }
}
