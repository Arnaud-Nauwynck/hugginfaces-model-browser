package fr.an.llms.huggingface.client.dto;

import com.fasterxml.jackson.databind.JsonNode;

public class HFModelsPageDTO {
    public JsonNode items;
    public String nextUrl;

    public HFModelsPageDTO(JsonNode items, String nextUrl) {
        this.items = items;
        this.nextUrl = nextUrl;
    }
}
