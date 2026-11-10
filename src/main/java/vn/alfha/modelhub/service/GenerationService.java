package vn.alfha.modelhub.service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import vn.alfha.modelhub.dto.*;
import vn.alfha.modelhub.provider.ModelProvider;
import vn.alfha.modelhub.provider.OllamaProvider;
import vn.alfha.modelhub.provider.OpenRouterProvider;
import vn.alfha.modelhub.exception.ProviderException;

@Service
public class GenerationService {
    private static final ObjectReader JSON_READER = new ObjectMapper().reader()
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final Map<String, ?> DEFECT_IDS_SCHEMA = Map.of("type", "object",
            "properties", Map.of("defectIds", Map.of("type", "array", "items", Map.of("type", "string"))),
            "required", List.of("defectIds"), "additionalProperties", false);
    private final java.util.function.Function<String, ModelProvider> providers;
    private HistoryService history;
    @Autowired
    public GenerationService(ModelRegistry registry, HistoryService history) {
        this.providers = registry::provider;
        this.history = history;
    }
    public GenerationService(ModelProvider provider, String model) {
        if (model.isBlank()) throw new IllegalArgumentException("Configured model must not be blank");
        this.providers = requested -> {
            if (!model.equals(requested)) throw new UnknownModelException();
            return provider;
        };
    }
    GenerationService(java.util.Map<String, ModelProvider> providers) {
        this.providers = model -> {
            var provider = providers.get(model);
            if (provider == null) throw new UnknownModelException();
            return provider;
        };
    }
    public void validate(GenerateRequest request) {
        if (request == null || request.model() == null || request.model().isBlank()
                || !validPrompt(request.prompt()))
            throw new IllegalArgumentException("Provide a model and a prompt of 1 to 32000 characters");
        providers.apply(request.model());
    }
    public static boolean validPrompt(String prompt) {
        return prompt != null && !prompt.isBlank() && prompt.length() <= 32000;
    }
    public GenerateResponse generate(GenerateRequest request) {
        return generate(request, false);
    }
    public GenerateResponse generateRouted(GenerateRequest request) {
        return generate(request, true);
    }
    public GenerateResponse generateRoutingDispatch(GenerateRequest request, boolean requestAllowsCloud) {
        if (!requestAllowsCloud || providers.apply(request.model()) instanceof OllamaProvider)
            return generateInstalledRaw(request);
        return generateRouted(request);
    }
    public GenerateResponse generateJson(JsonGenerateRequest request) {
        var input = new GenerateRequest(request.model(), request.prompt());
        validate(input);
        var ollama = requireOllama(input.model());

        var names = request.requiredStringFields();
        if (names == null || names.isEmpty() || names.size() > 16 || names.stream().anyMatch(name -> name == null
                || !name.isString() || !name.asString().matches("[A-Za-z_][A-Za-z0-9_]{0,63}")))
            throw new IllegalArgumentException("Provide 1 to 16 distinct JSON field names");
        var fields = names.stream().map(JsonNode::asString).toList();
        if (new HashSet<>(fields).size() != fields.size())
            throw new IllegalArgumentException("Provide 1 to 16 distinct JSON field names");

        var properties = new LinkedHashMap<String, Map<String, String>>();
        for (String field : fields) properties.put(field, Map.of("type", "string"));
        var schema = Map.of("type", "object", "properties", properties, "required", fields, "additionalProperties", false);
        return generateJson(input, ollama, schema, body -> body.size() == fields.size()
                && fields.stream().allMatch(field -> body.path(field).isString()));
    }

    OllamaProvider requireOllama(String model) {
        if (providers.apply(model) instanceof OllamaProvider ollama) return ollama;
        throw new IllegalArgumentException("This mode requires an Ollama model");
    }

    GenerateResponse generateInstalledRaw(GenerateRequest input) {
        validate(input);
        var ollama = requireOllama(input.model());
        return execute(input, () -> ollama.generateInstalledRaw(input.model(), input.prompt()), response -> true);
    }

    GenerateResponse generateDefectIds(GenerateRequest input) {
        validate(input);
        return generateJson(input, requireOllama(input.model()), DEFECT_IDS_SCHEMA, body -> {
            var findings = body.path("defectIds");
            if (body.size() != 1 || !findings.isArray()) return false;
            var seen = new HashSet<String>();
            for (JsonNode finding : findings) {
                if (!finding.isString() || !seen.add(finding.asString())) return false;
            }
            return true;
        });
    }

    private GenerateResponse generateJson(GenerateRequest input, OllamaProvider ollama, Map<String, ?> schema,
                                          Predicate<JsonNode> validObject) {
        return execute(input, () -> ollama.generateJson(input.model(), input.prompt(), schema),
                response -> matchesJsonContract(response, validObject));
    }

    private boolean matchesJsonContract(String response, Predicate<JsonNode> validObject) {
        try {
            var body = JSON_READER.readTree(response);
            return body != null && body.isObject() && validObject.test(body);
        } catch (JacksonException e) {
            return false;
        }
    }

    private GenerateResponse generate(GenerateRequest request, boolean routed) {
        validate(request);
        String model = request.model();
        ModelProvider provider = providers.apply(model);
        return execute(request, () -> routed && provider instanceof OpenRouterProvider cloud
                ? cloud.generateWithZeroPriceCeiling(model, request.prompt())
                : provider.generate(model, request.prompt()), response -> true);
    }

    private GenerateResponse execute(GenerateRequest request, Supplier<String> dispatch, Predicate<String> validOutput) {
        String model = request.model();
        long start = System.nanoTime();
        GenerateResponse execution;
        try {
            String result = dispatch.get();
            long duration = elapsed(start);
            execution = validOutput.test(result) ? new GenerateResponse(model, result, duration, "SUCCESS", null)
                    : new GenerateResponse(model, result, duration, "FAILED",
                            new ApiError("OUTPUT_FORMAT_ERROR", "Response does not match the requested JSON output contract"));
        } catch (ProviderException e) {
            execution = new GenerateResponse(model, null, elapsed(start), "FAILED",
                    new ApiError(e.code(), e.getMessage(), e.retryAfterMs()));
        } catch (RuntimeException e) {
            execution = new GenerateResponse(model, null, elapsed(start), "FAILED",
                    new ApiError("INTERNAL_ERROR", "An unexpected error occurred"));
        }
        return history == null ? execution : history.record(request.prompt(), execution);
    }
    private long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    public static class UnknownModelException extends RuntimeException { }
}
