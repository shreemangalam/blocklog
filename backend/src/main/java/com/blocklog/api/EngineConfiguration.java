package com.blocklog.api;

import com.blocklog.ingest.IngestionEngine;
import com.blocklog.metadata.BlockCatalog;
import com.blocklog.model.EngineConfig;
import com.blocklog.search.SearchEngine;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
@EnableConfigurationProperties(EngineConfig.class)
public class EngineConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EngineConfiguration.class);

    private IngestionEngine ingestionEngine;

    @Bean
    public BlockCatalog blockCatalog(EngineConfig config) {
        BlockCatalog catalog = new BlockCatalog(config.maxBlocks());
        Path dataDir = Path.of(config.dataDir());
        catalog.discoverBlocks(dataDir);
        log.info("Catalog initialized: {} blocks, {} unavailable", catalog.size(), catalog.unavailableCount());
        return catalog;
    }

    @Bean
    public IngestionEngine ingestionEngine(EngineConfig config, BlockCatalog catalog) {
        ingestionEngine = new IngestionEngine(Path.of(config.dataDir()), config, catalog);
        return ingestionEngine;
    }

    @Bean
    public SearchEngine searchEngine(EngineConfig config, BlockCatalog catalog) {
        return new SearchEngine(catalog, config.effectiveScanPermits(), config.maxActiveQueries());
    }

    @PreDestroy
    public void shutdown() {
        if (ingestionEngine != null) {
            log.info("Shutting down ingestion engine...");
            ingestionEngine.shutdown();
        }
    }
}
