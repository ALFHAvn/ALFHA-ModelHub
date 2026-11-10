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
            @Value("${modelhub.ollama.secondary-model:}") String secondaryModel,
            @Value("${modelhub.routing.local-pair.enabled:false}") boolean localPairEnabled) {
        if (localModel.isBlank()) throw new IllegalArgumentException("Local model must not be blank");
        if (cloudEnabled && (!OpenRouterProvider.isFreeModel(cloudModel) || localModel.equals(cloudModel)))
            throw new IllegalArgumentException("Configure a distinct free OpenRouter model");
        var primary = new ConfiguredModel(localModel, "ollama");
        var secondary = secondaryModel.isBlank() ? null : new ConfiguredModel(secondaryModel, "ollama");
        if (localPairEnabled && secondary == null)
            throw new IllegalArgumentException("Local-pair routing requires a distinct secondary Ollama model");
        var defaultRouting = cloudEnabled ? List.of(primary, new ConfiguredModel(cloudModel, "openrouter"))
                : List.of(primary);
        var configured = new ArrayList<>(defaultRouting);
        var adapters = new HashMap<String, ModelProvider>();
        adapters.put(localModel, ollama);
        if (cloudEnabled) adapters.put(cloudModel, openRouter);
        if (secondary != null) {
            if (adapters.putIfAbsent(secondaryModel, ollama) != null)
                throw new IllegalArgumentException("Configure a distinct secondary Ollama model");
            configured.add(secondary);
        }
        providers = Map.copyOf(adapters);
        models = List.copyOf(configured);
        routingModels = localPairEnabled ? List.of(primary, secondary) : defaultRouting;
    }

    public ModelRegistry(OllamaProvider ollama, OpenRouterProvider openRouter, String localModel,
            boolean cloudEnabled, String cloudModel, String secondaryModel) {
        this(ollama, openRouter, localModel, cloudEnabled, cloudModel, secondaryModel, false);
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
