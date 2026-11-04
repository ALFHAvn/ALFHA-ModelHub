package vn.alfha.modelhub.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class BenchmarkCatalogTest {
    @Test void preservesV1IdentityAndChangesOnlyTheAmbiguousPromptInV2() throws Exception {
        var catalog = new BenchmarkCatalog(new ObjectMapper());
        assertEquals("6de7890d47a885fc17f703d0b1a07970395f66640906a783ffd2498de467917e", catalog.hash());
        assertEquals(catalog.dataset(), catalog.dataset(null));
        assertNotEquals(catalog.hash("1"), catalog.hash("2"));
        var first = catalog.dataset("1").tasks();
        var second = catalog.dataset("2").tasks();
        assertEquals(10, second.size());
        assertEquals(first.subList(0, 9), second.subList(0, 9));
        assertEquals(first.getLast().fields(), second.getLast().fields());
        assertEquals(first.getLast().rule(), second.getLast().rule());
        assertTrue(second.getLast().prompt().contains("literal JSON string \"empty\" (not an empty string)"));
        assertThrows(IllegalArgumentException.class, () -> catalog.dataset("unknown"));
    }
}
