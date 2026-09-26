package fr.an.llms.huggingface.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * État persisté du synchroniseur, sur disque en JSON.
 *
 * - fullScanNextUrl : URL de reprise (contient le cursor) si un full scan est
 *   en cours / a été interrompu. null + fullScanComplete=true => terminé.
 * - lastSyncThreshold : timestamp (ISO 8601) du modèle le plus récent déjà
 *   synchronisé. Sert de watermark pour l'incrémental suivant.
 * - incrementalNextUrl : reprise d'un run incrémental interrompu.
 * - newestSeenThisRun : max(lastModified) vu pendant le run en cours, promu
 *   en lastSyncThreshold seulement quand le run se termine proprement.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class HuggingFaceSynchronizationStateDTO {

    public String fullScanNextUrl;
    public boolean fullScanComplete = false;

    public String incrementalNextUrl;
    public String lastSyncThreshold;   // null => jamais synchronisé => full scan obligatoire
    public String newestSeenThisRun;

    public long totalModelsSynced = 0;
    public long lastRunErrors = 0;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static HuggingFaceSynchronizationStateDTO load(Path path) {
        try {
            if (Files.exists(path)) {
                return MAPPER.readValue(Files.readString(path), HuggingFaceSynchronizationStateDTO.class);
            }
        } catch (IOException e) {
            System.err.println("[state] lecture impossible (" + e.getMessage() + "), état neuf utilisé");
        }
        return new HuggingFaceSynchronizationStateDTO();
    }

    public void save(Path path) {
        try {
            // atomic write
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(this));
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[state] Failed saving: " + e.getMessage());
        }
    }
}
