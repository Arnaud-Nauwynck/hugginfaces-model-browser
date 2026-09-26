package fr.an.llms.rest;

import fr.an.llms.huggingface.service.HuggingFaceSynchronizationStateDTO;
import fr.an.llms.huggingface.service.HuggingFaceSynchronizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/huggingface-sync", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "HuggingFaceSync")
@Slf4j
public class HuggingFaceSyncRestController extends AbstractRestController {

    private static final String BASE_URL = "/api/v1/hugginface-sync";

    private final HuggingFaceSynchronizer huggingFaceSynchronizer;

    public HuggingFaceSyncRestController(HuggingFaceSynchronizer hugginFaceSynchronizer) {
        super(BASE_URL);
        this.huggingFaceSynchronizer = hugginFaceSynchronizer;
    }

    @Operation(summary = "Get the last synchronization info")
    @GetMapping("/last-sync-state")
    public HuggingFaceSynchronizationStateDTO getLastSyncState() {
        return withLogDebug(log, "GET", "/last-sync-state", "", huggingFaceSynchronizer::getLastSyncState);
    }

    @Operation(summary = "Run the synchronization")
    @PostMapping("/run-sync-all")
    public void runSyncAll() {
        withLog(log, "POST", "/run-sync-all", "", huggingFaceSynchronizer::runFullScan);
    }

    @Operation(summary = "Run the increment synchronization")
    @PostMapping("/run-sync-incremental")
    public void runSyncIncremental() {
        withLog(log, "POST", "/run-sync-incremental", "", huggingFaceSynchronizer::runIncremental);
    }

}
