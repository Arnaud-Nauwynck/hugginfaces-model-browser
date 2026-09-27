package fr.an.llms.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "hugging-face")
@Data
public class HuggingFaceProperties {

    String baseApiModelsUrl = "https://huggingface.co/api/models";
    String token;

    String baseDataDir;
    int subShardsPerYear = 10;


    private int synchronizationConcurrency = 2;
    private long delayMsPerRequest = 100;
}
