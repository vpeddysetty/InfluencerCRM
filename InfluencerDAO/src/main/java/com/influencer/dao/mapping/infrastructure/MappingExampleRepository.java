package com.influencer.dao.mapping.infrastructure;

import com.influencer.dao.mapping.domain.MappingExample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MappingExampleRepository extends JpaRepository<MappingExample, UUID> {
}
