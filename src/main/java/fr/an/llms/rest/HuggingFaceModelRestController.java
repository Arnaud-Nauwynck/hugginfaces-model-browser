package fr.an.llms.rest;

import fr.an.llms.huggingface.service.HuggingFaceModelService;
import fr.an.llms.huggingface.service.HuggingFaceSynchronizationStateDTO;
import fr.an.llms.huggingface.service.HuggingFaceSynchronizer;
import fr.an.llms.huggingface.service.HuggingFaceModelRepository;
import fr.an.llms.rest.dtos.HFModelCriteria;
import fr.an.llms.rest.dtos.HFModelDTO;
import fr.an.llms.rest.dtos.HFModelQueryDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(path = "/api/v1/models", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "HuggingFace Models")
@Slf4j
public class HuggingFaceModelRestController extends AbstractRestController {

    private static final String BASE_URL = "/api/v1/models";

    private final HuggingFaceModelService delegate;

    public HuggingFaceModelRestController(HuggingFaceModelService delegate) {
        super(BASE_URL);
        this.delegate = delegate;
    }

    @Operation(summary = "Query HuggingFace models by criteria")
    @PostMapping("/query")
    public List<HFModelDTO> queryModels(@RequestBody HFModelQueryDTO req) {
        HFModelCriteria criteria = new HFModelCriteria(req.criteria);
        return withLog(log, "GET", "/query", "" + req, () -> delegate.queryModels(criteria, req.limit));
    }

    @Operation(summary = "Get HuggingFace model by id")
    @GetMapping("/by-id/{id}")
    public HFModelDTO getModelById(@PathVariable("id") String id) {
        return withLog(log, "GET", "/{id}", id, () -> delegate.getModelById(id));
    }

}
