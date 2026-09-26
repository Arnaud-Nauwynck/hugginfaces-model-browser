package fr.an.llms.huggingface.client.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public class HFDatedModelsPageDTO {
    public List<HFDatedModelDTO> items;
    public String nextUrl;

    public HFDatedModelsPageDTO(List<HFDatedModelDTO> items, String nextUrl) {
        this.items = items;
        this.nextUrl = nextUrl;
    }
}
