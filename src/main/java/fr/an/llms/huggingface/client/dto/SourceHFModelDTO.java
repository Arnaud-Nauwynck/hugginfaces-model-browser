package fr.an.llms.huggingface.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * DTO for the full model info from Hugging Face API (GET /api/models/{repoId}).
 *
 * <PRE>
 * see src/test/data/huggingface/client/model-fetch1-simplified.json
 * </PRE>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SourceHFModelDTO {

    public String _id;
    public String id;
    @JsonProperty("private")
    public boolean _private;
    @JsonProperty("pipeline_tag")
    public String pipelineTag;
    @JsonProperty("library_name")
    public String libraryName;
    public List<String> tags;
    public long downloads;
    public long likes;
    public String modelId;
    public String author;
    public String sha;
    public String lastModified;
    public String gated;
    public boolean disabled;
    public List<Map<String, Object>> widgetData;
    @JsonProperty("model-index")
    public JsonNode modelIndex;
    public Map<String, Object> config;
    public CardDataDTO cardData;
    public TransformersInfoDTO transformersInfo;
    public List<SiblingDTO> siblings;
    public List<String> spaces;
    public String createdAt;
    public SafetensorsDTO safetensors;
    public long usedStorage;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CardDataDTO {
        /** String or List<String> */
        public Object license;

        @JsonProperty("library_name")
        public String libraryName;

        @JsonProperty("pipeline_tag")
        public String pipelineTag;

        public List<String> tags;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransformersInfoDTO {
        public String auto_model;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SiblingDTO {
        public String rfilename;
        public String blobId;
        public long size;
        public LfsDTO lfs;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LfsDTO {
        public String sha256;
        public long size;
        public int pointerSize;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SafetensorsDTO {
        public Map<String, Long> parameters;
        public long total;
    }

}
