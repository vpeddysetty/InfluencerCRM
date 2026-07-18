package com.influencer.dao.controller;

import com.influencer.dao.model.MappingExample;
import com.influencer.dao.repository.MappingExampleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/mapping-examples")
public class MappingExampleController {
    private final MappingExampleRepository repository;

    public MappingExampleController(MappingExampleRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<MappingExample> findAll() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public MappingExample findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("MappingExample not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MappingExample create(@RequestBody MappingExample mappingExample) {
        return repository.save(mappingExample);
    }

    @PutMapping("/{id}")
    public MappingExample update(@PathVariable UUID id, @RequestBody MappingExample mappingExample) {
        MappingExample existing = repository.findById(id).orElseThrow(() -> new RuntimeException("MappingExample not found"));
        existing.setUserId(mappingExample.getUserId());
        existing.setTemplateName(mappingExample.getTemplateName());
        existing.setSourceSignature(mappingExample.getSourceSignature());
        existing.setSourceTabNames(mappingExample.getSourceTabNames());
        existing.setSourceColumns(mappingExample.getSourceColumns());
        existing.setSampleValuesJson(mappingExample.getSampleValuesJson());
        existing.setMappingsJson(mappingExample.getMappingsJson());
        existing.setQualityScore(mappingExample.getQualityScore());
        existing.setUsageCount(mappingExample.getUsageCount());
        existing.setIsActive(mappingExample.getIsActive());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
