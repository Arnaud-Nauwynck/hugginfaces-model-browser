package fr.an.llms.huggingface.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fr.an.llms.configuration.HuggingFaceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
 * values) persisted in index.json. Secondary indexes (by author,
 * pipeline_tag, library_name) are derived in memory at load time from
 * the primary index -> never persisted separately, so never
 * out of sync with the actual store between two startups.
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
public class LlmModelRepository {

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

        public IndexEntry() {}
        public IndexEntry(String year, int shard, long offset, int length,
                           String author, String pipelineTag, String libraryName) {
            this.year = year; this.shard = shard; this.offset = offset; this.length = length;
            this.author = author; this.pipelineTag = pipelineTag; this.libraryName = libraryName;
        }
    }

    private final Path root;
    private final Path indexPath;
    private final int subShardsPerYear;
    private final ObjectMapper mapper = new ObjectMapper();

    private final ConcurrentHashMap<String, IndexEntry> index = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> partitionLocks = new ConcurrentHashMap<>();

    // Index secondaires : valeur -> ensemble d'ids. Dérivés, jamais persistés directement.
    private final ConcurrentHashMap<String, Set<String>> byAuthor = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> byPipelineTag = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> byLibraryName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> byYear = new ConcurrentHashMap<>();

    public LlmModelRepository(HuggingFaceProperties huggingFaceProps) throws IOException {
        this.root = Paths.get(huggingFaceProps.getBaseDataDir());
        this.subShardsPerYear = huggingFaceProps.getSubShardsPerYear();
        this.indexPath = root.resolve("index.json");
        Files.createDirectories(root);
        loadIndexOrRebuild();
    }

    // ================= PARTITIONING =================

    private String yearOf(JsonNode node) {
        String createdAt = node.path("createdAt").asText(null);
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

    public void write(String repoId, String rawJson) throws IOException {
        JsonNode node = mapper.readTree(rawJson);
        String line = mapper.writeValueAsString(node); // minified, single line
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);

        String year = yearOf(node);
        int shard = subShardFor(repoId);
        String author = node.path("author").asText(null);
        String pipelineTag = node.path("pipeline_tag").asText(null);
        String libraryName = node.path("library_name").asText(null);

        synchronized (lockFor(year, shard)) {
            Path path = shardPath(year, shard);
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                long offset = ch.size();
                ch.write(ByteBuffer.wrap(bytes));

                IndexEntry old = index.put(repoId,
                        new IndexEntry(year, shard, offset, bytes.length - 1, author, pipelineTag, libraryName));
                updateSecondaryIndexes(repoId, old, author, pipelineTag, libraryName, year);
            }
        }
    }

    public void delete(String repoId) {
        IndexEntry old = index.remove(repoId);
        if (old != null) {
            removeFromSecondaryIndexes(repoId, old);
        }
    }

    public boolean exists(String repoId) {
        return index.containsKey(repoId);
    }

    // ================= READ =================

    public String read(String repoId) throws IOException {
        IndexEntry e = index.get(repoId);
        if (e == null) return null;
        return readAt(shardPath(e.year, e.shard), e.offset, e.length);
    }

    private String readAt(Path path, long offset, int length) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            raf.seek(offset);
            byte[] buf = new byte[length];
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    // ================= SECONDARY INDEXES =================

    private void addTo(ConcurrentHashMap<String, Set<String>> idx, String key, String id) {
        if (key == null) return;
        idx.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(id);
    }

    private void removeFrom(ConcurrentHashMap<String, Set<String>> idx, String key, String id) {
        if (key == null) return;
        Set<String> s = idx.get(key);
        if (s != null) s.remove(id);
    }

    private void updateSecondaryIndexes(String id, IndexEntry old, String author, String pipelineTag,
                                         String libraryName, String year) {
        if (old != null) {
            removeFrom(byAuthor, old.author, id);
            removeFrom(byPipelineTag, old.pipelineTag, id);
            removeFrom(byLibraryName, old.libraryName, id);
            removeFrom(byYear, old.year, id);
        }
        addTo(byAuthor, author, id);
        addTo(byPipelineTag, pipelineTag, id);
        addTo(byLibraryName, libraryName, id);
        addTo(byYear, year, id);
    }

    private void removeFromSecondaryIndexes(String id, IndexEntry old) {
        removeFrom(byAuthor, old.author, id);
        removeFrom(byPipelineTag, old.pipelineTag, id);
        removeFrom(byLibraryName, old.libraryName, id);
        removeFrom(byYear, old.year, id);
    }

    public Set<String> findByAuthor(String author) { return byAuthor.getOrDefault(author, Set.of()); }
    public Set<String> findByPipelineTag(String tag) { return byPipelineTag.getOrDefault(tag, Set.of()); }
    public Set<String> findByLibraryName(String lib) { return byLibraryName.getOrDefault(lib, Set.of()); }
    public Set<String> findByYear(String year) { return byYear.getOrDefault(year, Set.of()); }

    // ================= PRIMARY INDEX: PERSISTENCE / RECOVERY =================

    public void saveIndex() {
        try {
            Path tmp = indexPath.resolveSibling(indexPath.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writeValueAsString(index));
            Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("[repo] failed to save index: " + e.getMessage());
        }
    }

    private void loadIndexOrRebuild() throws IOException {
        if (Files.exists(indexPath)) {
            try {
                Map<String, IndexEntry> loaded = mapper.readValue(Files.readString(indexPath),
                        mapper.getTypeFactory().constructMapType(HashMap.class, String.class, IndexEntry.class));
                index.putAll(loaded);
                rebuildSecondaryIndexesFromPrimary();
                System.out.println("[repo] index loaded: " + index.size() + " entries");
                return;
            } catch (IOException e) {
                System.err.println("[repo] index unreadable (" + e.getMessage() + "), rebuilding from partitions");
            }
        }
        rebuildIndexFromPartitions();
    }

    /** Rebuilds the in-memory secondary indexes from the primary index (already loaded). */
    private void rebuildSecondaryIndexesFromPrimary() {
        byAuthor.clear(); byPipelineTag.clear(); byLibraryName.clear(); byYear.clear();
        for (Map.Entry<String, IndexEntry> e : index.entrySet()) {
            IndexEntry v = e.getValue();
            addTo(byAuthor, v.author, e.getKey());
            addTo(byPipelineTag, v.pipelineTag, e.getKey());
            addTo(byLibraryName, v.libraryName, e.getKey());
            addTo(byYear, v.year, e.getKey());
        }
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
        rebuildSecondaryIndexesFromPrimary();
        System.out.println("[repo] index rebuilt from partitions: " + index.size() + " entries");
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
                        JsonNode node = mapper.readTree(new String(all, lineStart, len, StandardCharsets.UTF_8));
                        String id = node.path("id").asText(null);
                        if (id != null) {
                            index.put(id, new IndexEntry(year, shard, lineStart, len,
                                    node.path("author").asText(null),
                                    node.path("pipeline_tag").asText(null),
                                    node.path("library_name").asText(null)));
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
                    JsonNode node = mapper.readTree(content); // re-read -> source of truth for secondary fields
                    String author = node.path("author").asText(null);
                    String pipelineTag = node.path("pipeline_tag").asText(null);
                    String libraryName = node.path("library_name").asText(null);

                    byte[] bytes = (content + "\n").getBytes(StandardCharsets.UTF_8);
                    out.write(ByteBuffer.wrap(bytes));

                    IndexEntry newEntry = new IndexEntry(year, shard, writeOffset, bytes.length - 1,
                            author, pipelineTag, libraryName);
                    refreshed.put(id, newEntry);
                    updateSecondaryIndexes(id, loc, author, pipelineTag, libraryName, year); // fixes any drift
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
