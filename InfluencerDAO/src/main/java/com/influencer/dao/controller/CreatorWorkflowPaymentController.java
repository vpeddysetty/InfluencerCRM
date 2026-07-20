package com.influencer.dao.controller;

import com.influencer.dao.model.CreatorWorkflowPayment;
import com.influencer.dao.repository.CreatorWorkflowPaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/creator-workflow-payments")
public class CreatorWorkflowPaymentController {
    private final CreatorWorkflowPaymentRepository repository;

    public CreatorWorkflowPaymentController(CreatorWorkflowPaymentRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<CreatorWorkflowPayment> findAll(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) UUID campaignCreatorId) {
        if (userId != null && campaignCreatorId != null) {
            return repository.findByUserIdAndCampaignCreatorId(userId, campaignCreatorId);
        }
        if (userId != null) {
            return repository.findByUserId(userId);
        }
        if (campaignCreatorId != null) {
            return repository.findByCampaignCreatorId(campaignCreatorId);
        }
        return repository.findAll();
    }

    @GetMapping("/{id}")
    public CreatorWorkflowPayment findById(@PathVariable UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("CreatorWorkflowPayment not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreatorWorkflowPayment create(@RequestBody CreatorWorkflowPayment payment) {
        return repository.save(payment);
    }

    @PutMapping("/{id}")
    public CreatorWorkflowPayment update(@PathVariable UUID id, @RequestBody CreatorWorkflowPayment payment) {
        CreatorWorkflowPayment existing = repository.findById(id)
                .orElseThrow(() -> new RuntimeException("CreatorWorkflowPayment not found"));
        existing.setUserId(payment.getUserId());
        existing.setCampaignCreatorId(payment.getCampaignCreatorId());
        existing.setCurrency(payment.getCurrency());
        existing.setAmount(payment.getAmount());
        existing.setStatus(payment.getStatus());
        existing.setInvoiceReference(payment.getInvoiceReference());
        existing.setPaymentProvider(payment.getPaymentProvider());
        existing.setProviderTxnId(payment.getProviderTxnId());
        existing.setNotes(payment.getNotes());
        existing.setScheduledAt(payment.getScheduledAt());
        existing.setPaidAt(payment.getPaidAt());
        existing.setFailedAt(payment.getFailedAt());
        existing.setMetadata(payment.getMetadata());
        return repository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        repository.deleteById(id);
    }
}
