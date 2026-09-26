package fr.an.llms.rest.dtos;

import fr.an.llms.huggingface.client.dto.SourceHFModelDTO;
import jakarta.annotation.sql.DataSourceDefinitions;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor @AllArgsConstructor
public class HFModelDTO {

    public String id;

    /** last timestamp (epoch millis) of last fetch from Hugging-Face */
    public long huggingFaceLastFetchTimestampMs;

    /** source info from Hugging-Face API (GET /api/models/{repoId}) */
    public SourceHFModelDTO huggingFaceInfo;

    public Map<String,Object> extra;

}
