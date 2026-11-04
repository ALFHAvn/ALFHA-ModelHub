package vn.alfha.modelhub.service;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.alfha.modelhub.provider.*;

@Component
public class ModelRegistry {
    public record ConfiguredModel(String model, String provider) { }
    private final Map<String, ModelProvider> providers;
    private final List<ConfiguredModel> models;

    public ModelRegistry(OllamaProvider ollama, OpenRouterProvider openRouter,
            @Value("${modelhub.ollama.model}") String localModel,
            @Value("${modelhub.openrouter.enabled}") boolean cloudEnabled,
            @Value("${modelhub.openrouter.model}") String cloudModel) {
        if (localModel.isBlank()) throw new IllegalArgumentException("Local model must not be blank");
        if (cloudEnabled && (!OpenRouterProvider.isFreeModel(cloudModel) || localModel.equals(cloudModel)))
            throw new IllegalArgumentException("Configure a distinct free OpenRouter model");
        providers = cloudEnabled ? Map.of(localModel, ollama, cloudModel, openRouter) : Map.of(localModel, ollama);
        models = cloudEnabled ? List.of(new ConfiguredModel(localModel, "ollama"), new ConfiguredModel(cloudModel, "openrouter"))
                : List.of(new ConfiguredModel(localModel, "ollama"));
    }
    ModelProvider provider(String model) {
        var provider = providers.get(model);
        if (provider == null) throw new GenerationService.UnknownModelException();
        return provider;
    }
    public List<ConfiguredModel> models() { return models; }
}
