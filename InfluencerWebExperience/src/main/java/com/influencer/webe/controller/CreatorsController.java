package com.influencer.webe.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.influencer.webe.client.DaoGatewayClient;
import com.influencer.webe.service.RequestUserResolver;
import com.influencer.webe.service.ResponseShapeService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/creators")
public class CreatorsController {
    private final DaoGatewayClient daoGatewayClient;
    private final RequestUserResolver requestUserResolver;
    private final ResponseShapeService responseShapeService;

    public CreatorsController(DaoGatewayClient daoGatewayClient,
                              RequestUserResolver requestUserResolver,
                              ResponseShapeService responseShapeService) {
        this.daoGatewayClient = daoGatewayClient;
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
        return responseShapeService.creatorsList(daoGatewayClient.get("/creators", query), page, size);
    }

    @GetMapping("/{id}")
    public JsonNode findById(@PathVariable UUID id) {
        return responseShapeService.creator(daoGatewayClient.get("/creators/" + id, null));
    }

    @PostMapping
    public JsonNode create(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @RequestBody ObjectNode payload) {
        UUID resolvedUserId = requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId"));
        payload.put("userId", resolvedUserId.toString());
        return responseShapeService.creator(daoGatewayClient.post("/creators", payload));
    }

    @PutMapping("/{id}")
    public JsonNode update(@RequestHeader(value = "Authorization", required = false) String authorization,
                           @PathVariable UUID id,
                           @RequestBody ObjectNode payload) {
        UUID resolvedUserId = requestUserResolver.resolveUserId(authorization, getUuid(payload, "userId"));
        payload.put("userId", resolvedUserId.toString());
        return responseShapeService.creator(daoGatewayClient.put("/creators/" + id, payload));
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable UUID id) {
        daoGatewayClient.delete("/creators/" + id);
    }

    private UUID getUuid(ObjectNode payload, String fieldName) {
        if (payload == null || payload.get(fieldName) == null || payload.get(fieldName).asText().isBlank()) {
            return null;
        }
        return UUID.fromString(payload.get(fieldName).asText());
    }
}
