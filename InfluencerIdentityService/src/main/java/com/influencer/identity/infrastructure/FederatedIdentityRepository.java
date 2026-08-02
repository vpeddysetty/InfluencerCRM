package com.influencer.identity.infrastructure;

import com.influencer.identity.domain.FederatedIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FederatedIdentityRepository extends JpaRepository<FederatedIdentity, UUID> {

    /** The federated login path: an assertion resolves to at most one local user. */
    Optional<FederatedIdentity> findByProviderAndSubject(String provider, String subject);

    /** Backs the account-settings screen and the "must keep one credential" unlink rule. */
    List<FederatedIdentity> findByUserId(UUID userId);

    long countByUserId(UUID userId);

    /** Every identity from a compromised provider, for bulk revocation. */
    List<FederatedIdentity> findByProvider(String provider);
}
