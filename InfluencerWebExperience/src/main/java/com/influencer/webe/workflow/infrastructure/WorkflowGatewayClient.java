package com.influencer.webe.workflow.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.influencer.webe.config.WebExperienceProperties;
import com.influencer.webe.shared.infrastructure.DaoGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Routes Workflow calls to either the monolith DAO or the extracted Workflow service.
 *
 * <p>This is the feature flag the extraction runbook requires. Cutting over by redeploying is not a
 * cutover — it is an outage waiting for a bad afternoon. With the flag, reverting is a property
 * change and takes seconds.
 *
 * <p>Every method delegates to whichever client the flag selects, so the controller above it is
 * unchanged and all 13 Workflow endpoints move together. Once the extracted service has soaked, the
 * DAO branch and this class both go away.
 */
@Component
public class WorkflowGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(WorkflowGatewayClient.class);

    private final DaoGatewayClient daoGatewayClient;
    private final WorkflowServiceClient workflowServiceClient;
    private final WebExperienceProperties properties;

    public WorkflowGatewayClient(DaoGatewayClient daoGatewayClient,
                                 WorkflowServiceClient workflowServiceClient,
                                 WebExperienceProperties properties) {
        this.daoGatewayClient = daoGatewayClient;
        this.workflowServiceClient = workflowServiceClient;
        this.properties = properties;
    }

    /**
     * Logs the routing decision once at startup rather than per request — useful during a cutover,
     * unbearable in a hot path.
     */
    @jakarta.annotation.PostConstruct
    void logRouting() {
        log.info("Workflow requests routed to: {}",
                properties.isWorkflowServiceEnabled() ? "extracted Workflow service" : "monolith DAO");
    }

    private boolean useExtractedService() {
        return properties.isWorkflowServiceEnabled();
    }

    public JsonNode get(String path, Map<String, String> query) {
        return useExtractedService()
                ? workflowServiceClient.get(path, query)
                : daoGatewayClient.get(path, query);
    }

    public JsonNode post(String path, JsonNode payload) {
        return useExtractedService()
                ? workflowServiceClient.post(path, payload)
                : daoGatewayClient.post(path, payload);
    }

    public JsonNode put(String path, JsonNode payload) {
        return useExtractedService()
                ? workflowServiceClient.put(path, payload)
                : daoGatewayClient.put(path, payload);
    }

    public JsonNode patch(String path, JsonNode payload) {
        return useExtractedService()
                ? workflowServiceClient.patch(path, payload)
                : daoGatewayClient.patch(path, payload);
    }

    public void delete(String path) {
        if (useExtractedService()) {
            workflowServiceClient.delete(path);
        } else {
            daoGatewayClient.delete(path);
        }
    }
}
