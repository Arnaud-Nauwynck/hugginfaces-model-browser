package fr.an.llms.rest.dtos;

public class HFModelQueryDTO {

    public HFModelCriteriaDTO criteria;
    public Integer limit = 5000;

    public String toString() {
        return "{criteria=" + criteria + ", limit=" + limit + "}";
    }
}
