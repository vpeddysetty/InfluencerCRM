package com.influencer.webe.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.client.AgentMappingClient;
import com.influencer.webe.client.DaoGatewayClient;
import com.influencer.webe.service.RequestUserResolver;
import com.influencer.webe.service.ResponseShapeService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/import-batches")
public class ImportBatchesController {
    private final DaoGatewayClient daoGatewayClient;
    private final AgentMappingClient agentMappingClient;
    private final RequestUserResolver requestUserResolver;
    private final ResponseShapeService responseShapeService;

    public ImportBatchesController(DaoGatewayClient daoGatewayClient,
                                   AgentMappingClient agentMappingClient,
                                   RequestUserResolver requestUserResolver,
                                   ResponseShapeService responseShapeService) {
        this.daoGatewayClient = daoGatewayClient;
        this.agentMappingClient = agentMappingClient;
        this.requestUserResolver = requestUserResolver;
        this.responseShapeService = responseShapeService;
    }

    @GetMapping
    public JsonNode list(@RequestHeader(value = "Authorization", required = false) String authorization,
                         @RequestParam(required = false) UUID userId,
                         @RequestParam(required = false) Integer page,
                         @RequestParam(required = false) Integer size) {
        UUID resolvedUserId = requestUserResolver.resolveUserId(authorization, userId);
        Map<String, String> query = new LinkedHashMap<>();
        query.put("userId", resolvedUserId.toString());
        return responseShapeService.importBatchesList(daoGatewayClient.get("/import-batches", query), page, size);
    }

    @GetMapping("/{id}")
    public JsonNode findById(@PathVariable UUID id) {
        return responseShapeService.importBatch(daoGatewayClient.get("/import-batches/" + id, null));
    }

    @GetMapping("/{id}/columns")
    public JsonNode columns(@PathVariable UUID id) {
        return daoGatewayClient.get("/import-batches/" + id + "/columns", null);
    }

    @PostMapping("/{id}/agent-column-mapping")
    public JsonNode generateAgentColumnMapping(@PathVariable UUID id) {
        JsonNode storedColumnsResult = daoGatewayClient.get("/import-batches/" + id + "/columns", null);
        ArrayNode columnsNode = storedColumnsResult != null && storedColumnsResult.has("columns") && storedColumnsResult.get("columns").isArray()
                ? (ArrayNode) storedColumnsResult.get("columns")
                : responseShapeService.objectMapper().createArrayNode();

        ObjectNode response = responseShapeService.objectMapper().createObjectNode();
        response.put("importBatchId", id.toString());
        if (storedColumnsResult != null && storedColumnsResult.hasNonNull("sourceFilename")) {
            response.set("sourceFilename", storedColumnsResult.get("sourceFilename"));
        }
        response.set("columns", columnsNode);
        response.set("mapping", agentMappingClient.mapColumns(responseShapeService.asStringList(columnsNode)));
        return response;
    }

    @PostMapping("/discover")
    public JsonNode discover(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestParam(required = false) UUID userId,
                             @RequestPart("file") MultipartFile file) throws IOException {
        UUID resolvedUserId = requestUserResolver.resolveUserId(authorization, userId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("userId", resolvedUserId.toString());
        return responseShapeService.importDiscoverResult(daoGatewayClient.postMultipart(
                "/import-batches/discover",
                fields,
                "file",
                file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename(),
                file.getBytes(),
            file.getContentType()));
    }

    @PostMapping("/discover-multi")
    public JsonNode discoverMulti(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestParam(required = false) UUID userId,
                                  @RequestPart("files") MultipartFile[] files) throws IOException {
        UUID resolvedUserId = requestUserResolver.resolveUserId(authorization, userId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("userId", resolvedUserId.toString());

        List<DaoGatewayClient.MultipartFilePart> fileParts = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                if (file == null || file.isEmpty()) {
                    continue;
                }
                fileParts.add(new DaoGatewayClient.MultipartFilePart(
                        "files",
                        file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename(),
                        file.getBytes(),
                        file.getContentType()));
            }
        }

        return responseShapeService.importDiscoverResult(daoGatewayClient.postMultipartFiles(
                "/import-batches/discover-multi",
                fields,
                fileParts));
    }

    @PostMapping("/{id}/preview")
    public JsonNode preview(@PathVariable UUID id, @RequestBody JsonNode payload) {
        return responseShapeService.importPreviewResult(daoGatewayClient.post("/import-batches/" + id + "/preview", payload));
    }

    @PatchMapping("/{id}/column-mapping")
    public JsonNode updateMapping(@PathVariable UUID id, @RequestBody JsonNode payload) {
        return responseShapeService.importBatch(daoGatewayClient.patch("/import-batches/" + id + "/column-mapping", payload));
    }

    @PostMapping("/{id}/hydrate")
    public JsonNode hydrate(@PathVariable UUID id, @RequestBody JsonNode payload) {
        return responseShapeService.importHydrateResult(daoGatewayClient.post("/import-batches/" + id + "/hydrate", payload));
    }
}
