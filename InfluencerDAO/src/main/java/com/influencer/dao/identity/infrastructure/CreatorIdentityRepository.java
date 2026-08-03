package com.influencer.dao.identity.infrastructure;

import com.influencer.dao.identity.domain.CreatorIdentity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CreatorIdentityRepository extends JpaRepository<CreatorIdentity, UUID> {
    Optional<CreatorIdentity> findByEmailIgnoreCase(String email);
}
