package fr.an.llms.huggingface.client.dto;

/**
 * DTO for model with expanded "createdAt" and "lastModified" dates information from Hugging Face API.
 *
 * <PRE>
 * {
 *   "_id": "6aacc6b34a8e10ba66ac6d0f",
 *   "id": "convaiinnovations/laya",
 *   "lastModified": "2026-09-24T05:39:22.000Z",
 *   "trendingScore": 3198,
 *   "createdAt": "2026-09-18T05:05:55.000Z"
 * }
 * </PRE>
 */
public class HFDatedModelDTO {

    public String _id;
    public String id;
    public String createdAt;
    public String lastModified;
    public Integer trendingScore;

}
