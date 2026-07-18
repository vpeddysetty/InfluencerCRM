package com.influencer.dao.controller;

import com.influencer.dao.model.ImportBatch;
import com.influencer.dao.repository.ImportBatchRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/import-batches")
public class ImportBatchController {
    private final ImportBatchRepository repository;

    public ImportBatchController(ImportBatchRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<ImportBatch> findAll() {
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public ImportBatch findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("ImportBatch not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ImportBatch create(@RequestBody ImportBatch importBatch) {
        return repository.save(importBatch);
    }

    @PutMapping("/{id}")
    public ImportBatch update(@PathVariable UUID id, @RequestBody ImportBatch importBatch) {
        ImportBatch existing = repository.findById(id).orElseThrow(() -> new RuntimeException("ImportBatch not found"));
        existing.setUserId(importBatch.getUserId());
        existing.setSourceFilename(importBatch.getSourceFilename());
        existing.setColumnMapping(importBatch.getColumnMapping());
        existing.setRowCount(importBatch.getRowCount());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
