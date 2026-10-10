package fr.an.llms.huggingface.service;

import fr.an.llms.rest.dtos.HFModelCriteria;
import fr.an.llms.rest.dtos.HFModelDTO;
import fr.an.llms.rest.dtos.ModelStatisticsDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class HuggingFaceModelService {

    private final HuggingFaceModelRepository modelRepository;

    public HuggingFaceModelService(HuggingFaceModelRepository modelRepository) {
        this.modelRepository = modelRepository;
    }

    public HFModelDTO getModelById(String id) {
        return modelRepository.findById(id);
    }

    public List<HFModelDTO> queryModels(HFModelCriteria criteria, Integer limit) {
        List<HFModelDTO> result = new ArrayList<>();
        try {
            modelRepository.scanAll(criteria, limit, result::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    public ModelStatisticsDTO computeModelStatistics(HFModelCriteria criteria, Integer topN) {
        Map<String, Map<String, ModelStatisticsDTO.AuthorStatisticsDTO>> allAuthorStats = new HashMap<>();
        Map<String, Map<String, ModelStatisticsDTO.ModelStatisticsItemDTO>> allModelStats = new HashMap<>();

        try {
            modelRepository.scanAll(criteria, null, model -> {
                if (model.huggingFaceInfo != null) {
                    String month = extractMonth(model.huggingFaceInfo.createdAt);
                    String author = model.huggingFaceInfo.author != null ? model.huggingFaceInfo.author : "unknown";
                    String modelId = model.huggingFaceInfo.modelId != null ? model.huggingFaceInfo.modelId : model.id;
                    long likes = model.huggingFaceInfo.likes;
                    long downloads = model.huggingFaceInfo.downloads;

                    allAuthorStats
                        .computeIfAbsent(month, k -> new HashMap<>())
                        .compute(author, (k, existing) -> {
                            if (existing == null) {
                                return new ModelStatisticsDTO.AuthorStatisticsDTO(likes, downloads);
                            } else {
                                existing.sumLikes += likes;
                                existing.sumDownloads += downloads;
                                return existing;
                            }
                        });

                    allModelStats
                        .computeIfAbsent(month, k -> new HashMap<>())
                        .put(modelId, new ModelStatisticsDTO.ModelStatisticsItemDTO(likes, downloads, author));
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Map<String, Map<String, ModelStatisticsDTO.AuthorStatisticsDTO>> topNAuthorStats = allAuthorStats.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue().entrySet().stream()
                    .sorted(Map.Entry.<String, ModelStatisticsDTO.AuthorStatisticsDTO>comparingByValue(
                        Comparator.comparingLong(ModelStatisticsDTO.AuthorStatisticsDTO::getSumLikes).reversed()))
                    .limit(topN != null ? topN : Long.MAX_VALUE)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
            ));

        Map<String, Map<String, ModelStatisticsDTO.ModelStatisticsItemDTO>> topNModelStats = allModelStats.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> entry.getValue().entrySet().stream()
                    .sorted(Map.Entry.<String, ModelStatisticsDTO.ModelStatisticsItemDTO>comparingByValue(
                        Comparator.comparingLong(ModelStatisticsDTO.ModelStatisticsItemDTO::getLikes).reversed()))
                    .limit(topN != null ? topN : Long.MAX_VALUE)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
            ));

        return new ModelStatisticsDTO(topNAuthorStats, topNModelStats);
    }

    private String extractMonth(LocalDate date) {
        return date != null ? date.toString().substring(0, 7) : "unknown";
    }
}
