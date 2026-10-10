package fr.an.llms.rest.dtos;

import java.time.LocalDate;

public class HFModelCriteriaDTO {

    public LocalDate createdDateFrom;
    public LocalDate createdDateTo;

    public String nameContains;
    public String nameEquals;

    public String authorContains;
    public String authorEquals;

    // TODO add more criteria fields as needed

    public String toString() {
        return "{" +
                ((createdDateFrom != null)? "createdDateFrom=" + createdDateFrom : "" ) +
                ((createdDateTo != null)? ", createdDateTo=" + createdDateTo : "" ) +
                ((nameContains != null)? ", nameContains='" + nameContains + '\'' : "" ) +
                ((nameEquals != null)? ", nameEquals='" + nameEquals + '\'' : "" ) +
                ((authorContains != null)? ", authorContains='" + authorContains + '\'' : "" ) +
                ((authorEquals != null)? ", authorEquals='" + authorEquals + '\'' : "" ) +
                '}';
    }
}
