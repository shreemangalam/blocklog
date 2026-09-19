package com.blocklog.api;

import com.blocklog.ingest.IngestionEngine;
import com.blocklog.metadata.BlockCatalog;
import com.blocklog.model.StatusResponse;
import com.blocklog.search.SearchEngine;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class StatusController {

    private final IngestionEngine ingestionEngine;
    private final BlockCatalog catalog;
    private final SearchEngine searchEngine;

    public StatusController(IngestionEngine ingestionEngine, BlockCatalog catalog, SearchEngine searchEngine) {
        this.ingestionEngine = ingestionEngine;
        this.catalog = catalog;
        this.searchEngine = searchEngine;
    }

    @GetMapping("/status")
    public StatusResponse status() {
        double pressurePct = Math.max(
                ingestionEngine.getBufferUsagePct(),
                ingestionEngine.getQueueUsagePct());
        return new StatusResponse(
                ingestionEngine.isPersistenceHealthy() ? "healthy" : "degraded",
                ingestionEngine.getAcceptedRecords(),
                ingestionEngine.getPersistedRecords(),
                ingestionEngine.getLostRecords(),
                ingestionEngine.getAcceptedBytes(),
                ingestionEngine.getPersistedBytes(),
                catalog.size(),
                catalog.unavailableCount(),
                searchEngine.getActiveQueries(),
                pressurePct,
                ingestionEngine.isPersistenceHealthy()
        );
    }
}
