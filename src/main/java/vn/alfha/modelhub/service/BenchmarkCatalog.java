package vn.alfha.modelhub.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.dto.BenchmarkDataset;

@Component
public class BenchmarkCatalog {
    private final Map<String, BenchmarkDataset> datasets = new LinkedHashMap<>();
    private final Map<String, String> hashes = new LinkedHashMap<>();
    public BenchmarkCatalog(ObjectMapper mapper) throws IOException, NoSuchAlgorithmException {
        for (String version : List.of("1", "2")) {
            try (var input = new ClassPathResource("benchmarks/basics-v" + version + ".json").getInputStream()) {
                byte[] bytes = input.readAllBytes();
                var dataset = mapper.readValue(bytes, BenchmarkDataset.class);
                // Hash the parsed snapshot so checkout line endings do not change dataset identity.
                datasets.put(version, dataset);
                hashes.put(version, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(dataset))));
            }
        }
    }
    public BenchmarkDataset dataset() { return dataset("1"); }
    public String hash() { return hash("1"); }
    public BenchmarkDataset dataset(String version) {
        var dataset = datasets.get(version == null ? "1" : version);
        if (dataset == null) throw new IllegalArgumentException("Unknown dataset version");
        return dataset;
    }
    public String hash(String version) { return hashes.get(dataset(version).version()); }
}
