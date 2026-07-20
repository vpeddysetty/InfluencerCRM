package com.influencer.dao.controller;

import com.influencer.dao.model.ImportBatch;
import com.influencer.dao.repository.ImportBatchRepository;
import com.influencer.dao.service.ImportBatchHydrationService;
import com.influencer.dao.service.SpreadsheetDiscoveryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/import-batches")
public class ImportBatchController {
    private final ImportBatchRepository repository;
    private final ImportBatchHydrationService hydrationService;
    private final SpreadsheetDiscoveryService discoveryService;

    public ImportBatchController(
            ImportBatchRepository repository,
            ImportBatchHydrationService hydrationService,
            SpreadsheetDiscoveryService discoveryService) {
        this.repository = repository;
        this.hydrationService = hydrationService;
        this.discoveryService = discoveryService;
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

    @PostMapping(value = "/discover", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SpreadsheetDiscoveryService.DiscoverImportBatchResponse discover(
            @RequestParam UUID userId,
            @RequestPart("file") MultipartFile file) {
        SpreadsheetDiscoveryService.DiscoveredSpreadsheet discoveredSpreadsheet = discoveryService.discover(file);

        ImportBatch importBatch = new ImportBatch();
        importBatch.setUserId(userId);
        importBatch.setSourceFilename(discoveredSpreadsheet.getSourceFilename());
        importBatch.setRowCount(discoveredSpreadsheet.getRowCount());
        importBatch.setColumnMapping("{}");

        ImportBatch saved = repository.save(importBatch);

        return new SpreadsheetDiscoveryService.DiscoverImportBatchResponse(
                saved,
                discoveredSpreadsheet.getColumns());
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

    @PatchMapping("/{id}/column-mapping")
    public ImportBatch updateColumnMapping(@PathVariable UUID id, @RequestBody UpdateColumnMappingRequest request) {
        ImportBatch existing = repository.findById(id).orElseThrow(() -> new RuntimeException("ImportBatch not found"));
        existing.setColumnMapping(request.getColumnMapping());
        return repository.save(existing);
    }

    @PostMapping("/{id}/hydrate")
    public ImportBatchHydrationService.HydrateImportBatchResponse hydrate(
            @PathVariable UUID id,
            @RequestBody ImportBatchHydrationService.HydrateImportBatchRequest request) {
        return hydrationService.hydrate(id, request);
    }

    @PostMapping("/{id}/preview")
    public ImportBatchHydrationService.HydrateImportBatchResponse preview(
            @PathVariable UUID id,
            @RequestBody ImportBatchHydrationService.HydrateImportBatchRequest request) {
        return hydrationService.preview(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }

    public static class UpdateColumnMappingRequest {
        private String columnMapping;

        public String getColumnMapping() {
            return columnMapping;
        }

        public void setColumnMapping(String columnMapping) {
            this.columnMapping = columnMapping;
        }
    }
}
