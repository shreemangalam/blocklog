package com.blocklog.api;

import com.blocklog.ingest.IngestionEngine;
import com.blocklog.metadata.BlockCatalog;
import com.blocklog.model.StatusResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class StatusController {

    private final IngestionEngine ingestionEngine;
    private final BlockCatalog catalog;

    public StatusController(IngestionEngine ingestionEngine, BlockCatalog catalog) {
        this.ingestionEngine = ingestionEngine;
        this.catalog = catalog;
    }

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse(
                ingestionEngine.isPersistenceHealthy() ? "healthy" : "degraded",
                ingestionEngine.getAcceptedRecords(),
                ingestionEngine.getPersistedRecords(),
                ingestionEngine.getAcceptedBytes(),
                ingestionEngine.getPersistedBytes(),
                catalog.size(),
                catalog.unavailableCount(),
                0,
                ingestionEngine.getBufferUsagePct(),
                ingestionEngine.isPersistenceHealthy()
        );
    }
}
