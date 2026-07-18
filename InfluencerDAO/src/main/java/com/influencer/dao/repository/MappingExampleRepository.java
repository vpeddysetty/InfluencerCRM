package com.influencer.dao.repository;

import com.influencer.dao.model.MappingExample;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MappingExampleRepository extends JpaRepository<MappingExample, UUID> {
}
