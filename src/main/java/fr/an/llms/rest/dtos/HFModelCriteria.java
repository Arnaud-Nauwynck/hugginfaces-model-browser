package fr.an.llms.rest.dtos;

import fr.an.llms.huggingface.client.dto.SourceHFModelDTO;

import java.time.LocalDate;
import java.util.function.Predicate;

public class HFModelCriteria implements Predicate<HFModelDTO> {

    public final HFModelCriteriaDTO criteria;

    public HFModelCriteria(HFModelCriteriaDTO criteria) {
        this.criteria = criteria;
    }

    @Override
    public boolean test(HFModelDTO model) {
        if (criteria == null) return true;

        SourceHFModelDTO hfInfo = model.huggingFaceInfo;
        LocalDate createdAt = hfInfo.createdAt;
        if (createdAt != null) {
            if (criteria.createdDateFrom != null && createdAt.isBefore(criteria.createdDateFrom)) {
                return false;
            }
            if (criteria.createdDateTo != null && createdAt.isAfter(criteria.createdDateTo)) {
                return false;
            }
        } else {
            if (criteria.createdDateFrom != null || criteria.createdDateTo != null) {
                return false;
            }
        }

        String name = hfInfo != null ? hfInfo.modelId : null;
        if (criteria.nameEquals != null && !criteria.nameEquals.equals(name)) {
            return false;
        }
        if (criteria.nameContains != null && (name == null || !name.contains(criteria.nameContains))) {
            return false;
        }

        String author = hfInfo != null ? hfInfo.author : null;
        if (criteria.authorEquals != null && !criteria.authorEquals.equals(author)) {
            return false;
        }
        if (criteria.authorContains != null
                && (author == null || !author.contains(criteria.authorContains))) {
            return false;
        }


        return true;
    }
}
