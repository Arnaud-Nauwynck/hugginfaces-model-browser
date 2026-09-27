package fr.an.llms.rest.dtos;

import java.util.function.Predicate;

public class HFModelCriteria implements Predicate<HFModelDTO> {

    private final HFModelCriteriaDTO criteria;

    public HFModelCriteria(HFModelCriteriaDTO criteria) {
        this.criteria = criteria;
    }

    @Override
    public boolean test(HFModelDTO model) {
        if (criteria == null) return true;
        String author = model.huggingFaceInfo != null ? model.huggingFaceInfo.author : null;
        if (criteria.authorEquals != null && !criteria.authorEquals.equals(author)) return false;
        if (criteria.authorContains != null
                && (author == null || !author.contains(criteria.authorContains))) return false;
        return true;
    }
}
