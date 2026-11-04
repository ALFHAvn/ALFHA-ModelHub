package vn.alfha.modelhub.service;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import vn.alfha.modelhub.dto.BenchmarkDataset;

@Component
public class BenchmarkCatalog {
    private final BenchmarkDataset dataset;
    private final String hash;
    public BenchmarkCatalog(ObjectMapper mapper) throws IOException, NoSuchAlgorithmException {
        try (var input = new ClassPathResource("benchmarks/basics-v1.json").getInputStream()) {
            byte[] bytes = input.readAllBytes();
            dataset = mapper.readValue(bytes, BenchmarkDataset.class);
            // Hash the parsed snapshot so checkout line endings do not change dataset identity.
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(dataset)));
        }
    }
    public BenchmarkDataset dataset() { return dataset; }
    public String hash() { return hash; }
}
