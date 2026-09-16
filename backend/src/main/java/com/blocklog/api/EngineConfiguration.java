package com.blocklog.api;

import com.blocklog.ingest.IngestionEngine;
import com.blocklog.metadata.BlockCatalog;
import com.blocklog.model.EngineConfig;
import com.blocklog.observability.EngineMetrics;
import com.blocklog.search.SearchEngine;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(EngineConfig.class)
public class EngineConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EngineConfiguration.class);

    private IngestionEngine ingestionEngine;
    private BlockCatalog catalog;

    @Bean
    public Clock engineClock() {
        return Clock.systemUTC();
    }

    @Bean
    public BlockCatalog blockCatalog(EngineConfig config) {
        catalog = new BlockCatalog(config.maxBlocks(), config.mmapCacheSize());
        Path dataDir = Path.of(config.dataDir());
        catalog.discoverBlocks(dataDir);
        log.info("Catalog initialized: {} blocks, {} unavailable, mmap cache size {}",
                catalog.size(), catalog.unavailableCount(), config.mmapCacheSize());
        return catalog;
    }

    @Bean
    public IngestionEngine ingestionEngine(EngineConfig config, BlockCatalog catalog,
                                            EngineMetrics metrics, Clock clock) {
        ingestionEngine = new IngestionEngine(Path.of(config.dataDir()), config, catalog, metrics, clock);
        return ingestionEngine;
    }

    @Bean
    public SearchEngine searchEngine(EngineConfig config, BlockCatalog catalog, EngineMetrics metrics) {
        return new SearchEngine(catalog, config.effectiveScanPermits(), config.maxActiveQueries(), metrics);
    }

    @PreDestroy
    public void shutdown() {
        if (ingestionEngine != null) {
            log.info("Shutting down ingestion engine...");
            ingestionEngine.shutdown();
        }
        if (catalog != null) {
            log.info("Closing catalog mmap handles...");
            catalog.close();
        }
    }
}
