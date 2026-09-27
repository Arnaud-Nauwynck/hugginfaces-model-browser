package fr.an.llms.huggingface.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
        loadIndexOrRebuild();
    }

    // ================= PARTITIONING =================

    public static String partitionYearOf(String createdAt) {
        if (createdAt == null) return "unknown";
        try {
            return Instant.parse(createdAt).toString().substring(0, 4);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private int subShardFor(String id) {
        CRC32 crc = new CRC32();
        crc.update(id.getBytes(StandardCharsets.UTF_8));
        return (int) (crc.getValue() % subShardsPerYear);
    }

    private Path shardPath(String year, int shard) throws IOException {
        Path dir = root.resolve(year);
        Files.createDirectories(dir);
        Path p = dir.resolve(String.format("shard-%04d.ndjson", shard));
        if (!Files.exists(p)) Files.createFile(p);
        return p;
    }

    private Object lockFor(String year, int shard) {
        return partitionLocks.computeIfAbsent(year + "/" + shard, k -> new Object());
    }

    // ================= WRITE =================

    public void save(HFModelDTO item) throws IOException {
        String line = mapper.writeValueAsString(item); // minified, single line
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

    /** Distinct (year, shard) partitions currently present in the index. */
    public Set<String> partitions() {
        Set<String> partitions = new HashSet<>();
        for (IndexEntry e : index.values()) partitions.add(e.year + "/" + e.shard);
        return partitions;
    }

    /** Scans every partition, invoking the callback for each stored model matching criteria. */
    public void scanAll(HFModelCriteria criteria, Consumer<HFModelDTO> callback) throws IOException {
        for (String partition : partitions()) {
            String[] parts = partition.split("/", 2);
            scanByPartition(parts[0], Integer.parseInt(parts[1]), criteria, callback);
        }
    }

    /** Scans a single (year, shard) partition, invoking the callback for each stored model matching criteria. */
    public void scanByPartition(String year, int shard, HFModelCriteria criteria, Consumer<HFModelDTO> callback) throws IOException {
        Path path = shardPath(year, shard);
        for (Map.Entry<String, IndexEntry> e : index.entrySet()) {
            IndexEntry loc = e.getValue();
            if (!loc.year.equals(year) || loc.shard != shard) continue;

            try {
                String json = readAt(path, loc.offset, loc.length);
                HFModelDTO item = mapper.readValue(json, HFModelDTO.class);
                if (criteria == null || criteria.test(item)) {
                    callback.accept(item);
                }
            } catch (Exception ex) {
                log.error("[repo] failed to read " + e.getKey() + " at " + year + "/" + shard
                        + " offset=" + loc.offset + " length=" + loc.length, ex);
            }
        }
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

        for (String partition : partitions) {
            String[] parts = partition.split("/", 2);
            compactPartition(parts[0], Integer.parseInt(parts[1]));
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
                    HFModelDTO item = mapper.readValue(content, HFModelDTO.class);
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
                }
            }

            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            index.putAll(refreshed);
        }
    }

    public int size() { return index.size(); }
    public int getSubShardsPerYear() { return subShardsPerYear; }
}
