package fr.an.llms.huggingface.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.an.llms.configuration.HuggingFaceProperties;
import fr.an.llms.huggingface.client.dto.HFDatedModelDTO;
import fr.an.llms.huggingface.client.dto.HFDatedModelsPageDTO;
import fr.an.llms.huggingface.client.dto.SourceHFModelDTO;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class HuggingFaceApiClient {

    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");

    public final String baseApiModelsUrl;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();


    private final String token;

    private AtomicInteger rateLimitRemain = new AtomicInteger(10);
    private AtomicLong rateLimitRestTime = new AtomicLong(0);

    public HuggingFaceApiClient(HuggingFaceProperties props) {
        this.baseApiModelsUrl = props.getBaseApiModelsUrl();
        this.token = props.getToken();
    }

    private HttpRequest.Builder authed(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30));
        if (token != null && !token.isBlank()) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    public HFDatedModelsPageDTO fetchDatedModelsPage(String url) throws IOException, InterruptedException {
        HttpResponse<String> resp = sendWithRetry(url);
        String respBody = resp.body();
        List<HFDatedModelDTO> items = mapper.readerForListOf(HFDatedModelDTO.class).readValue(respBody);
        String next = extractHttpHeaderNextLink(resp).orElse(null);
        return new HFDatedModelsPageDTO(items, next);
    }

    public SourceHFModelDTO fetchModelInfo(String repoId) throws IOException, InterruptedException {
        String path = repoId; //.replace("/", "%2F");
        String url = baseApiModelsUrl + "/" + path + "?blobs=true&files_metadata=true";
        try {
            HttpResponse<String> resp = sendWithRetry(url);
            String bodyText = resp.body();

            resp.headers().firstValue("ratelimit").ifPresent(v -> {
                // parse '"api";r=791;t=164'
                String[] split = v.split(";");
                if (split.length == 3 && split[0].equals("\"api\"")) {
                    if (split[1].startsWith("r=")) {
                        String remainValueText = split[1].substring(2);
                        try {
                            rateLimitRemain.set(Integer.parseInt(remainValueText));
                        } catch (NumberFormatException e) {
                            log.warn("Invalid ratelimit header value: " + remainValueText);
                        }
                    }
                    if (split[2].startsWith("t=")) {
                        String timeValueText = split[2].substring(2);
                        try {
                            long waitSeconds = Long.parseLong(timeValueText);
                            rateLimitRestTime.set(System.currentTimeMillis() + 100 + waitSeconds * 1000);
                        } catch (NumberFormatException e) {
                            log.warn("Invalid ratelimit header value: " + timeValueText);
                        }
                    }
                }
            });

            return mapper.readValue(bodyText, SourceHFModelDTO.class);
        } catch (NotFoundException e) {
            return null;
        }
    }

    private static class NotFoundException extends IOException {
    }

    private HttpResponse<String> sendWithRetry(String url) throws IOException, InterruptedException {
        int maxAttempts = 5;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (rateLimitRemain.get() <= 5) {
                long now = System.currentTimeMillis();
                long waitMs = rateLimitRestTime.get() - now;
                if (waitMs > 0) {
                    log.warn("[http] rateLimitRemain=" + rateLimitRemain.get() + " -> wait " + waitMs + "ms");
                    Thread.sleep(waitMs);
                }
            }

            HttpResponse<String> resp = client.send(
                    authed(url).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            int status = resp.statusCode();
            if (status == 200) return resp;
            if (status == 404) throw new NotFoundException();

            if (status == 429 || status >= 500) {
                long waitMs = retryAfterMillis(resp).orElse(2000L * (attempt + 1));
                log.error("[http] " + status + " on " + url + " -> pause " + waitMs + "ms (attempt " + (attempt + 1) + ")");
                Thread.sleep(waitMs);
                continue;
            }
            throw new IOException("HTTP " + status + " on " + url);
        }
        throw new IOException("Failed after " + maxAttempts + " attempts: " + url);
    }

    private static Optional<Long> retryAfterMillis(HttpResponse<String> resp) {
        return resp.headers().firstValue("Retry-After").map(v -> {
            try {
                return Long.parseLong(v.trim()) * 1000L;
            } catch (NumberFormatException e) {
                return 3000L;
            }
        });
    }

    private static Optional<String> extractHttpHeaderNextLink(HttpResponse<String> resp) {
        return resp.headers().firstValue("Link").flatMap(linkHeader -> {
            Matcher m = NEXT_LINK.matcher(linkHeader);
            return m.find() ? Optional.of(m.group(1)) : Optional.empty();
        });
    }
}
