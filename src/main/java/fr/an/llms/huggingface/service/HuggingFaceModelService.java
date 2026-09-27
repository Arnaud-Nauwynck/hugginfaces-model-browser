package fr.an.llms.huggingface.service;

import fr.an.llms.rest.dtos.HFModelCriteria;
import fr.an.llms.rest.dtos.HFModelDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

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
        int effectiveLimit = limit != null ? limit : Integer.MAX_VALUE;
        List<HFModelDTO> result = new ArrayList<>();
        try {
            modelRepository.scanAll(criteria, item -> {
                if (result.size() < effectiveLimit) {
                    result.add(item);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }
}
