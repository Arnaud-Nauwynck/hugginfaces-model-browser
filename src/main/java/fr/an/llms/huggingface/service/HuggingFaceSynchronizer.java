package fr.an.llms.huggingface.service;

import com.fasterxml.jackson.databind.JsonNode;
import fr.an.llms.configuration.HuggingFaceProperties;
import fr.an.llms.huggingface.client.HuggingFaceApiClient;
import fr.an.llms.huggingface.client.dto.HFModelsPageDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reliable synchronizer for Hugging Face model infos.
 *
 * FULL mode   : walks all of /api/models, fetches the model_info of each
 *               repo, writes to disk. Resumable: the next page URL
 *               (including the cursor) is saved after each page
 *               successfully processed. A crash loses at most one page
 *               (re-executed, no side effect since the write is idempotent).
 *
 * INCR mode   : walks /api/models sorted by lastModified descending and
 *               stops as soon as a model is older than the watermark
 *               of the last successful sync. Only re-downloads
 *               models that are new or modified since then.
 *
 * Per-model errors: logged, the run continues (no global abort
 * for a single failing repo).
 */
@Component
@Slf4j
public class HuggingFaceSynchronizer {

    private final HuggingFaceApiClient huggingFaceClient;
    private final LlmModelRepository store;
    private final Path statePath;
    private final int concurrency;
    private final long delayMsPerRequest;

    private final AtomicLong processed = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    public HuggingFaceSynchronizer(HuggingFaceApiClient huggingFaceClient, LlmModelRepository store,
                                   HuggingFaceProperties huggingFaceProps) {
        this.huggingFaceClient = huggingFaceClient;
        this.store = store;
        this.statePath = Path.of(huggingFaceProps.getBaseDataDir()).resolve("sync-state.json");
        this.concurrency = huggingFaceProps.getSynchronizationConcurrency();
        this.delayMsPerRequest = huggingFaceProps.getDelayMsPerRequest();
    }

    // ================= FULL SCAN =================

    public HuggingFaceSynchronizationStateDTO getLastSyncState() {
        return HuggingFaceSynchronizationStateDTO.load(statePath);
    }

    public void runFullScan() throws Exception {
        HuggingFaceSynchronizationStateDTO state = HuggingFaceSynchronizationStateDTO.load(statePath);

        if (state.fullScanComplete && state.fullScanNextUrl == null) {
            log.info("[full] already marked complete. Restart from the beginning anyway? "
                    + "Use --force-full to start over from scratch, otherwise we resume if a page is still in progress.");
        }

        String url = state.fullScanNextUrl != null
                ? state.fullScanNextUrl
                : huggingFaceClient.baseApiModelsUrl + "?limit=1000&full=true";

        log.info("[full] starting/resuming at: " + url);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);

        try {
            while (url != null) {
                HFModelsPageDTO page = huggingFaceClient.fetchPage(url);
                processPageConcurrently(page.items, pool);

                // Cursor advanced only after the WHOLE page has been processed successfully
                state.fullScanNextUrl = page.nextUrl;
                state.fullScanComplete = (page.nextUrl == null);
                state.totalModelsSynced = processed.get();
                state.lastRunErrors = errors.get();
                state.save(statePath);
                store.saveIndex(); // same checkpoint granularity as the state: after each successful page

                log.info("[full] page processed, total=" + processed.get()
                        + " errors=" + errors.get() + " next=" + (page.nextUrl != null));
                url = page.nextUrl;
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.MINUTES);
        }

        // The full scan becomes the new incremental watermark
        if (state.fullScanComplete) {
            state.lastSyncThreshold = Instant.now().toString();
            state.save(statePath);
            store.compactAll(); // the full scan may have rewritten already-present ids -> purge duplicates
        }

        log.info("[full] done. total=" + processed.get()
                + " errors=" + errors.get() + " models_in_store=" + store.size());
    }

    // ================= INCREMENTAL =================

    public void runIncremental() throws Exception {
        HuggingFaceSynchronizationStateDTO state = HuggingFaceSynchronizationStateDTO.load(statePath);

        if (state.lastSyncThreshold == null) {
            log.info("[incr] no watermark found -> run a full scan first.");
            return;
        }

        Instant threshold = Instant.parse(state.lastSyncThreshold);
        Instant newestSeen = state.newestSeenThisRun != null
                ? Instant.parse(state.newestSeenThisRun)
                : threshold;

        String url = state.incrementalNextUrl != null
                ? state.incrementalNextUrl
                : huggingFaceClient.baseApiModelsUrl + "?sort=lastModified&direction=-1&limit=1000&full=true";

        log.info("[incr] watermark=" + threshold + " starting/resuming at: " + url);
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        boolean reachedThreshold = false;

        try {
            while (url != null && !reachedThreshold) {
                HFModelsPageDTO page = huggingFaceClient.fetchPage(url);

                List<String> toFetch = new ArrayList<>();
                for (JsonNode m : page.items) {
                    String lastModifiedStr = m.path("lastModified").asText(null);
                    if (lastModifiedStr == null) continue;

                    Instant lastModified = safeParseInstant(lastModifiedStr);
                    if (lastModified == null) continue;

                    if (!lastModified.isAfter(threshold)) {
                        // Sorted desc -> everything else is even older, we can stop right here
                        reachedThreshold = true;
                        break;
                    }
                    if (lastModified.isAfter(newestSeen)) {
                        newestSeen = lastModified;
                    }
                    toFetch.add(m.path("id").asText());
                }

                fetchAndStoreConcurrently(toFetch, pool);

                state.incrementalNextUrl = reachedThreshold ? null : page.nextUrl;
                state.newestSeenThisRun = newestSeen.toString();
                state.totalModelsSynced = processed.get();
                state.lastRunErrors = errors.get();
                state.save(statePath);
                store.saveIndex();

                log.info("[incr] page processed, +" + toFetch.size()
                        + " models, total_run=" + processed.get() + " errors=" + errors.get());

                url = reachedThreshold ? null : page.nextUrl;
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.MINUTES);
        }

        // Complete run (not interrupted) -> the watermark advances, ready for the next run
        state.lastSyncThreshold = newestSeen.toString();
        state.incrementalNextUrl = null;
        state.newestSeenThisRun = null;
        state.save(statePath);

        log.info("[incr] done. new watermark=" + state.lastSyncThreshold
                + " models_updated=" + processed.get() + " errors=" + errors.get());
    }

    private Instant safeParseInstant(String s) {
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    // ================= SHARED FETCH =================

    private void processPageConcurrently(JsonNode items, ExecutorService pool) throws InterruptedException {
        List<String> ids = new ArrayList<>();
        for (JsonNode m : items) ids.add(m.path("id").asText());
        fetchAndStoreConcurrently(ids, pool);
    }

    private void fetchAndStoreConcurrently(List<String> ids, ExecutorService pool) throws InterruptedException {
        List<Future<?>> futures = new ArrayList<>();
        for (String repoId : ids) {
            futures.add(pool.submit(() -> {
                try {
                    Thread.sleep(delayMsPerRequest); // per-worker throttle
                    String json = huggingFaceClient.fetchModelInfo(repoId);
                    if (json == null) {
                        store.delete(repoId); // 404 -> deleted/renamed on the hub side (tombstone, purged at next compaction)
                        log.error("[warn] " + repoId + ": not found, removed from local store");
                    } else {
                        store.write(repoId, json);
                    }
                    processed.incrementAndGet();
                } catch (Exception e) {
                    errors.incrementAndGet();
                    log.error("[error] " + repoId + ": " + e);
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                errors.incrementAndGet();
            }
        }
    }

}
