package fr.an.llms.rest.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor @AllArgsConstructor
public class ModelStatisticsDTO {

    public Map<String, Map<String, AuthorStatisticsDTO>> byMonthThenAuthor;
    public Map<String, Map<String, ModelStatisticsItemDTO>> byMonthThenModel;

    @Data
    @NoArgsConstructor @AllArgsConstructor
    public static class AuthorStatisticsDTO {
        public long sumLikes;
        public long sumDownloads;
    }

    @Data
    @NoArgsConstructor @AllArgsConstructor
    public static class ModelStatisticsItemDTO {
        public long likes;
        public long downloads;
        public String author;
    }

}
