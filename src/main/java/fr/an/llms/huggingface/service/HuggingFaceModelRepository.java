package fr.an.llms.huggingface.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import fr.an.llms.huggingface.client.dto.SourceHFModelDTO;
import fr.an.llms.rest.dtos.HFModelCriteriaDTO;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import fr.an.llms.configuration.HuggingFaceProperties;
import fr.an.llms.rest.dtos.HFModelCriteria;
import fr.an.llms.rest.dtos.HFModelDTO;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * Repository for Hugging Face LLM model infos.
 *
 * Physical partitioning: data/models/{year}/shard-XXXX.ndjson
 *   - year = year of "createdAt" (immutable -> an id never migrates
 *     partition across updates, unlike lastModified)
 *   - within a year, sub-partitioning by hash(id) to avoid a recent
 *     year (much higher volume) producing a giant file
 *
 * Primary index (id -> year/shard/offset/length + cached secondary
 * values) persisted in index.json.
 *
 * Compaction re-reads the JSON of each live record to rewrite the
 * shard; since this JSON is already at hand at that point, it takes
 * the opportunity to RECOMPUTE the secondary values (author,
 * pipeline_tag, library_name) rather than trusting the cache -
 * this fixes any drift (e.g. cache never updated due to a bug,
 * line rewritten by an earlier version of the code, etc.).
 */
@Component
@Slf4j
public class HuggingFaceModelRepository {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IndexEntry {
        public String year;
        public int shard;
        public long offset;
        public int length;
        // Cached secondary values (avoids re-reading+parsing the JSON
        // just to remove an old secondary index association)
        public String author;
        public String pipelineTag;
        public String libraryName;
        public String lastModified;

        public IndexEntry() {}
        public IndexEntry(String year, int shard, long offset, int length,
                           String author, String pipelineTag, String libraryName, String lastModified) {
            this.year = year; this.shard = shard; this.offset = offset; this.length = length;
            this.author = author; this.pipelineTag = pipelineTag; this.libraryName = libraryName;
            this.lastModified = lastModified;
        }
    }

    private final Path root;
    private final Path indexPath;
    private final int subShardsPerYear;
    private final ObjectMapper mapper = new ObjectMapper();

    private final ConcurrentHashMap<String, IndexEntry> index = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> partitionLocks = new ConcurrentHashMap<>();

    public HuggingFaceModelRepository(HuggingFaceProperties huggingFaceProps) throws IOException {
        this.root = Paths.get(huggingFaceProps.getBaseDataDir());
        this.subShardsPerYear = huggingFaceProps.getSubShardsPerYear();
        this.indexPath = root.resolve("index.json");
        Files.createDirectories(root);
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "HF-Repository-Initializer");
            t.setDaemon(true);
            return t;
        });
        executor.submit(() -> {
            try {
                loadIndexOrRebuild();
                log.info("[repo] initialization complete");
            } catch (IOException e) {
                log.error("[repo] failed to initialize index", e);
            }
        });
        executor.shutdown();
    }

    // ================= PARTITIONING =================

    public static String partitionYearOf(LocalDate createdAt) {
        if (createdAt == null) {
            return "unknown";
        }
        return "" + createdAt.getYear();
    }

    private int subShardFor(String id) {
        CRC32 crc = new CRC32();
        crc.update(id.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % subShardsPerYear);
    }

    private Path shardPath(String year, int shard) {
        Path dir = root.resolve(year);
        try {
            Path p = dir.resolve(String.format("shard-%04d.ndjson", shard));
            if (!Files.exists(p)) {
                Files.createDirectories(dir);
                Files.createFile(p);
            }
            return p;
        } catch(IOException ex) {
            throw new RuntimeException("Failed to create partition file", ex);
        }
    }

    private Object lockFor(String year, int shard) {
        return partitionLocks.computeIfAbsent(year + "/" + shard, k -> new Object());
    }

    // ================= WRITE =================

    public void save(HFModelDTO item) {
        String line;
        try {
            line = mapper.writeValueAsString(item);
        } catch (JacksonException e) {
            throw new RuntimeException(e);
        }
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);

        val modelHFInfo = item.huggingFaceInfo;
        String year = partitionYearOf(modelHFInfo.createdAt);
        int shard = subShardFor(modelHFInfo.id);
        String author = modelHFInfo.author;
        String pipelineTag = modelHFInfo.pipelineTag;
        String libraryName = modelHFInfo.libraryName;
        String lastModified = modelHFInfo.lastModified;

        synchronized (lockFor(year, shard)) {
            Path path = shardPath(year, shard);
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                long offset = ch.size();
                ch.write(ByteBuffer.wrap(bytes));

                index.put(modelHFInfo.id,
                        new IndexEntry(year, shard, offset, bytes.length - 1, author, pipelineTag, libraryName, lastModified));
            } catch(IOException ex) {
                throw new RuntimeException("Failed to write", ex);
            }
        }
    }

    public void delete(String repoId) {
        index.remove(repoId);
    }

    public boolean exists(String repoId) {
        return index.containsKey(repoId);
    }

    // ================= READ =================

    public HFModelDTO findByPartitionAndId(String yearPartition, String repoId) {
        return findById(repoId); // currently not used, but could be optimized to avoid reading
    }


    public HFModelDTO findById(String repoId) {
        IndexEntry e = index.get(repoId);
        if (e == null) {
            return null;
        }
        try {
            String json = readAt(shardPath(e.year, e.shard), e.offset, e.length);
            return mapper.readValue(json, HFModelDTO.class);
        } catch(Exception ex) {
            log.error("[repo] failed to read " + repoId + " at " + e.year + "/" + e.shard + " offset=" + e.offset + " length=" + e.length, ex);
            return null;
        }
    }

    private String readAt(Path path, long offset, int length) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            raf.seek(offset);
            byte[] buf = new byte[length];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    // ================= SCAN =================

    /** Scans every partition, invoking the callback for each stored model matching criteria, until limit matches are found. */
    public void scanAll(HFModelCriteria criteria, Integer limit, Consumer<HFModelDTO> callback) throws IOException {
        int remaining = limit != null ? limit : Integer.MAX_VALUE;
        Set<String> prunedYears = filterYearPartitions(criteria);
        for (String year : prunedYears) {
            for (int shard = 0; shard < subShardsPerYear; shard++) {
                if (remaining <= 0) return;
                remaining -= scanByPartition(year, shard, criteria, remaining, callback);
            }
        }
    }

    /** Prunes year partitions based on createdDateFrom/To criteria to skip unnecessary I/O. */
    private Set<String> filterYearPartitions(HFModelCriteria criteria) {
        Set<String> allYears = yearPartitions();
        if (criteria == null) return allYears;

        HFModelCriteriaDTO critDTO = criteria.criteria;
        if (critDTO == null || (critDTO.createdDateFrom == null && critDTO.createdDateTo == null)) {
            return allYears;
        }

        Set<String> filtered = new HashSet<>();
        for (String yearStr : allYears) {
            try {
                int year = Integer.parseInt(yearStr);
                // Skip partitions entirely before createdDateFrom year
                if (critDTO.createdDateFrom != null && year < critDTO.createdDateFrom.getYear()) {
                    continue;
                }
                // Skip partitions entirely after createdDateTo year
                if (critDTO.createdDateTo != null && year > critDTO.createdDateTo.getYear()) {
                    continue;
                }
                filtered.add(yearStr);
            } catch (NumberFormatException e) {
                // Non-numeric year partition (e.g., "unknown"), keep it for safety
                filtered.add(yearStr);
            }
        }
        return filtered;
    }

    /** Distinct year partitions currently present in the index. */
    private Set<String> yearPartitions() {
        Set<String> years = new HashSet<>();
        for (IndexEntry e : index.values()) years.add(e.year);
        return years;
    }

    /**
     * Full sequential scan of a single (year, shard) partition file, invoking the callback
     * for each stored model matching criteria, until limit matches are found.
     * Returns the number of matches found (and passed to the callback).
     */
    public int scanByPartition(String year, int shard, HFModelCriteria criteria, Integer limit, Consumer<HFModelDTO> callback) throws IOException {
        int remaining = limit != null ? limit : Integer.MAX_VALUE;
        int matched = 0;
        Path shardFile = shardPath(year, shard);
        byte[] all = Files.readAllBytes(shardFile);
        int lineStart = 0;
        for (int i = 0; i < all.length && remaining > 0; i++) {
            if (all[i] == '\n') {
                int len = i - lineStart;
                if (len > 0) {
                    String jsonLine = new String(all, lineStart, len, StandardCharsets.UTF_8);
                    try {
                        HFModelDTO item = mapper.readValue(jsonLine, HFModelDTO.class);
                        if (criteria == null || criteria.test(item)) {
                            callback.accept(item);
                            matched++;
                            remaining--;
                        }
                    } catch (Exception ex) {
                        log.warn("[repo] failed to parse line in " + year + "/" + shard
                                + " at offset=" + lineStart + " length=" + len
                                + ", jsonLine: \n" + jsonLine
                                + "\n... ignore, no rethrow! ex:" + ex.getMessage()
                        );
                    }
                }
                lineStart = i + 1;
            }
        }
        return matched;
    }

    // ================= PRIMARY INDEX: PERSISTENCE / RECOVERY =================

    public void saveIndex() {
        try {
            Path tmp = indexPath.resolveSibling(indexPath.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(index));
            Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("[repo] failed to save index: " + e.getMessage());
        }
    }

    private void loadIndexOrRebuild() throws IOException {
        if (Files.exists(indexPath)) {
            try {
                Map<String, IndexEntry> loaded = mapper.readValue(Files.readString(indexPath),
                        mapper.getTypeFactory().constructMapType(HashMap.class, String.class, IndexEntry.class));
                index.putAll(loaded);
                log.info("[repo] index loaded: " + index.size() + " entries");
                return;
            } catch (IOException e) {
                log.error("[repo] index unreadable (" + e.getMessage() + "), rebuilding from partitions");
            }
        }
        rebuildIndexFromPartitions();
    }

    /**
     * Safety net if index.json is lost/corrupted: scans
     * data/models/{year}/shard-*.ndjson. The last occurrence of an id
     * in a file (append-only) is always the current version.
     */
    public void rebuildIndexFromPartitions() throws IOException {
        index.clear();
        if (!Files.exists(root)) { saveIndex(); return; }

        try (var years = Files.list(root)) {
            for (Path yearDir : years.filter(Files::isDirectory).toList()) {
                String year = yearDir.getFileName().toString();
                try (var shards = Files.list(yearDir)) {
                    for (Path shardFile : shards.filter(p -> p.toString().endsWith(".ndjson")).toList()) {
                        int shardNum = parseShardNumber(shardFile);
                        scanShardIntoIndex(year, shardNum, shardFile);
                    }
                }
            }
        }
        log.info("[repo] index rebuilt from partitions: " + index.size() + " entries");
        saveIndex();
    }

    private int parseShardNumber(Path shardFile) {
        String name = shardFile.getFileName().toString(); // shard-0007.ndjson
        return Integer.parseInt(name.substring(6, 10));
    }

    private void scanShardIntoIndex(String year, int shard, Path shardFile) throws IOException {
        byte[] all = Files.readAllBytes(shardFile);
        int lineStart = 0;
        for (int i = 0; i < all.length; i++) {
            if (all[i] == '\n') {
                int len = i - lineStart;
                if (len > 0) {
                    try {
                        String jsonLine = new String(all, lineStart, len, StandardCharsets.UTF_8);
                        HFModelDTO item = mapper.readValue(jsonLine, HFModelDTO.class);
                        String id = item.id;
                        if (id != null) {
                            val hfInfo = item.huggingFaceInfo;
                            index.put(id, new IndexEntry(year, shard, lineStart, len,
                                    hfInfo.author,
                                    hfInfo.pipelineTag,
                                    hfInfo.libraryName,
                                    hfInfo.lastModified));
                        }
                    } catch (Exception ignored) {
                        // corrupted line (append interrupted mid-flight) -> ignored
                    }
                }
                lineStart = i + 1;
            }
        }
    }

    // ================= COMPACTION =================

    /**
     * Rewrites each partition (year/shard) keeping only the current
     * version of each id, and RECOMPUTES the secondary fields from
     * the re-read JSON (not from the index cache) -> fixes any drift.
     */
    public void compactAll() throws IOException {
        Set<String> partitions = new HashSet<>();
        for (IndexEntry e : index.values()) partitions.add(e.year + "/" + e.shard);

        int partitionCount = partitions.size();
        log.info("[repo] compactAll: " + partitionCount + " partitions");
        int partitionNum = 0;
        for (String partition : partitions) {
            log.info("compact [" + partitionNum + "/" + partitionCount + "] partition " + partition);
            String[] parts = partition.split("/", 2);
            compactPartition(parts[0], Integer.parseInt(parts[1]));
            partitionNum++;
        }
        saveIndex();
    }

    private void compactPartition(String year, int shard) throws IOException {
        synchronized (lockFor(year, shard)) {
            Path path = shardPath(year, shard);
            Path tmp = path.resolveSibling(path.getFileName() + ".compact.tmp");
            Map<String, IndexEntry> refreshed = new HashMap<>();

            try (FileChannel out = FileChannel.open(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                long writeOffset = 0;
                for (Map.Entry<String, IndexEntry> e : index.entrySet()) {
                    IndexEntry loc = e.getValue();
                    if (!loc.year.equals(year) || loc.shard != shard) continue;
                    String id = e.getKey();

                    String content = readAt(path, loc.offset, loc.length);

                    try {
                        HFModelDTO item;
                        try {
                            item = mapper.readValue(content, HFModelDTO.class);
                        } catch(Exception ex) {
                            // backward-compatibility: some old records may have been stored with a SourceHFModelDTO instead of HFModelDTO
                            val source = mapper.readValue(content, SourceHFModelDTO.class);
                            item = new HFModelDTO();
                            item.id = source.id;
                            item.huggingFaceInfo = source;
                            content = mapper.writeValueAsString(item);
                        }

                        val hfInfo = item.huggingFaceInfo;
                        String author = hfInfo.author;
                        String pipelineTag = hfInfo.pipelineTag;
                        String libraryName = hfInfo.libraryName;
                        String lastModified = hfInfo.lastModified;

                        byte[] bytes = (content + "\n").getBytes(StandardCharsets.UTF_8);
                        out.write(ByteBuffer.wrap(bytes));

                        IndexEntry newEntry = new IndexEntry(year, shard, writeOffset, bytes.length - 1,
                                author, pipelineTag, libraryName, lastModified);
                        refreshed.put(id, newEntry);
                        writeOffset += bytes.length;
                    } catch(Exception ex) {
                        log.warn("[repo] failed to parse line in " + year + "/" + shard
                                + " at offset=" + loc.offset + " length=" + loc.length
                                + ", jsonLine: \n" + content
                                + "\n... ignore, no rethrow! ex:" + ex.getMessage()
                        );
                    }
                }
            }

            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            index.putAll(refreshed);
        }
    }

    public int size() { return index.size(); }
    public int getSubShardsPerYear() { return subShardsPerYear; }
}
