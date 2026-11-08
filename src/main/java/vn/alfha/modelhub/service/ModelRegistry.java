package vn.alfha.modelhub.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.alfha.modelhub.provider.*;

@Component
public class ModelRegistry {
    public record ConfiguredModel(String model, String provider) { }
    private final Map<String, ModelProvider> providers;
    private final List<ConfiguredModel> models;
    private final List<ConfiguredModel> routingModels;

    @Autowired
    public ModelRegistry(OllamaProvider ollama, OpenRouterProvider openRouter,
            @Value("${modelhub.ollama.model}") String localModel,
            @Value("${modelhub.openrouter.enabled}") boolean cloudEnabled,
            @Value("${modelhub.openrouter.model}") String cloudModel,
            @Value("${modelhub.ollama.secondary-model:}") String secondaryModel) {
        if (localModel.isBlank()) throw new IllegalArgumentException("Local model must not be blank");
        if (cloudEnabled && (!OpenRouterProvider.isFreeModel(cloudModel) || localModel.equals(cloudModel)))
            throw new IllegalArgumentException("Configure a distinct free OpenRouter model");
        routingModels = cloudEnabled ? List.of(new ConfiguredModel(localModel, "ollama"), new ConfiguredModel(cloudModel, "openrouter"))
                : List.of(new ConfiguredModel(localModel, "ollama"));
        var configured = new ArrayList<>(routingModels);
        var adapters = new HashMap<String, ModelProvider>();
        adapters.put(localModel, ollama);
        if (cloudEnabled) adapters.put(cloudModel, openRouter);
        if (!secondaryModel.isBlank()) {
            if (adapters.putIfAbsent(secondaryModel, ollama) != null)
                throw new IllegalArgumentException("Configure a distinct secondary Ollama model");
            configured.add(new ConfiguredModel(secondaryModel, "ollama"));
        }
        providers = Map.copyOf(adapters);
        models = List.copyOf(configured);
    }

    public ModelRegistry(OllamaProvider ollama, OpenRouterProvider openRouter, String localModel,
            boolean cloudEnabled, String cloudModel) {
        this(ollama, openRouter, localModel, cloudEnabled, cloudModel, "");
    }
    ModelProvider provider(String model) {
        var provider = providers.get(model);
        if (provider == null) throw new GenerationService.UnknownModelException();
        return provider;
    }
    public List<ConfiguredModel> models() { return models; }
    List<ConfiguredModel> routingModels() { return routingModels; }
}
