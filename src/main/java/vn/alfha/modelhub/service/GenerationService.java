package vn.alfha.modelhub.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vn.alfha.modelhub.dto.*;
import vn.alfha.modelhub.provider.ModelProvider;
import vn.alfha.modelhub.exception.ProviderException;

@Service
public class GenerationService {
    private final java.util.function.Function<String, ModelProvider> providers;
    @Autowired
    public GenerationService(ModelRegistry registry) {
        this.providers = registry::provider;
    }
    public GenerationService(ModelProvider provider, String model) {
        if (model.isBlank()) throw new IllegalArgumentException("Configured model must not be blank");
        this.providers = requested -> {
            if (!model.equals(requested)) throw new UnknownModelException();
            return provider;
        };
    }
    public GenerateResponse generate(GenerateRequest request) {
        if (request == null || request.model() == null || request.model().isBlank()
                || request.prompt() == null || request.prompt().isBlank() || request.prompt().length() > 32000)
            throw new IllegalArgumentException("Provide a model and a prompt of 1 to 32000 characters");
        String model = request.model();
        ModelProvider provider = providers.apply(model);
        long start = System.nanoTime();
        try {
            String result = provider.generate(model, request.prompt());
            return new GenerateResponse(model, result, elapsed(start), "SUCCESS", null);
        } catch (ProviderException e) {
            return new GenerateResponse(model, null, elapsed(start), "FAILED", new ApiError(e.code(), e.getMessage()));
        }
    }
    private long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    public static class UnknownModelException extends RuntimeException { }
}
