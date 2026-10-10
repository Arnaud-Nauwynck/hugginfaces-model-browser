package fr.an.llms.huggingface.client.dto;

import tools.jackson.databind.JsonNode;

import java.util.List;

public record SourceHFModelsPageDTO(
    List<SourceHFModelDTO> items,
    String nextUrl) {

}
