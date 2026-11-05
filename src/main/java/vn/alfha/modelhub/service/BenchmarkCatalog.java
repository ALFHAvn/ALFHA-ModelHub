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
    private record Key(String id, String version) { }
    private record Entry(BenchmarkDataset dataset, String hash) { }

    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    public BenchmarkCatalog(ObjectMapper mapper) throws IOException, NoSuchAlgorithmException {
        for (var key : List.of(new Key("basics", "1"), new Key("basics", "2"),
                new Key("software-engineering", "1"))) {
            String path = "benchmarks/" + key.id() + "-v" + key.version() + ".json";
            try (var input = new ClassPathResource(path).getInputStream()) {
                byte[] bytes = input.readAllBytes();
                var dataset = mapper.readValue(bytes, BenchmarkDataset.class);
                if (!key.id().equals(dataset.id()) || !key.version().equals(dataset.version()))
                    throw new IOException("Dataset identity does not match " + path);
                // Hash the parsed snapshot so checkout line endings do not change dataset identity.
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(mapper.writeValueAsBytes(dataset)));
                entries.put(key, new Entry(dataset, hash));
            }
        }
    }
    public BenchmarkDataset dataset() { return dataset("1"); }
    public String hash() { return hash("1"); }
    public BenchmarkDataset dataset(String version) {
        return dataset("basics", version);
    }
    public BenchmarkDataset dataset(String id, String version) {
        return entry(id, version).dataset();
    }
    public String hash(String version) { return hash("basics", version); }
    public String hash(String id, String version) { return entry(id, version).hash(); }

    private Entry entry(String id, String version) {
        var entry = entries.get(new Key(id, version == null ? "1" : version));
        if (entry == null) throw new IllegalArgumentException("Unknown benchmark dataset or version");
        return entry;
    }
}
