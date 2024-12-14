package test.com.brinvex.dms;

import com.brinvex.dms.api.Dms;
import com.brinvex.dms.api.DmsFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class DmsTest {

    private static final Logger LOG = LoggerFactory.getLogger(DmsTest.class);

    private static DmsFactory dmsFactory;

    private Dms dms;

    @BeforeAll
    static void beforeAll() {
        Path basePath = Path.of("c:/prj/bx/bx-dms/test-data/");
        dmsFactory = DmsFactory.newFilesystemDmsFactory(basePath);
    }

    @BeforeEach
    void setUp(TestInfo testInfo) {
        String testName = testInfo.getDisplayName();
        dms = dmsFactory.getDms(testName);
        dms.resetWorkspace();
        LocalDateTime now = LocalDateTime.now();
        LOG.info("setUp {} - invoking dms.purgeWorkspace({})", testName, now);
        int purged = dms.purgeWorkspace(now);
        LOG.info("setUp {} - purged={}", testName, purged);
    }

    @Test
    void fileKeys_empty() {
        Collection<String> fileKeys = dms.getKeys("some/directory");
        assertTrue(fileKeys.isEmpty());
    }

    @Test
    void add() {
        String content1 = "some_value";
        String directory = "some/directory";
        String key = "some_key";

        dms.add(directory, key, content1);
        Collection<String> fileKeys = dms.getKeys(directory);
        assertEquals(1, fileKeys.size());
        assertTrue(fileKeys.contains(key));

        boolean exists = dms.exists(directory, key);
        assertTrue(exists);

        String content2 = dms.getTextContent(directory, key);
        assertEquals(content1, content2);
    }

    @Test
    void add_duplicate() {
        dms.add("some/directory", "some_key", "some_value1");
        try {
            dms.add("some/directory", "some_key", "some_value2");
            fail("Should fail");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    void put() {
        boolean added;
        String directory = "some/directory";
        String key = "some_key";
        added = dms.put(directory, key, "some_value1");
        assertTrue(added);

        added = dms.put(directory, key, "some_value1");
        assertFalse(added);

        dms.delete(directory, key);
        added = dms.put(directory, key, "some_value1");
        assertTrue(added);

        dms.delete(directory, key);
        added = dms.put(directory, key, "some_value1");
        assertTrue(added);

        added = dms.put(directory, key, "some_value1");
        assertFalse(added);

        int purged;
        purged = dms.purge(directory);
        assertEquals(4, purged);
        purged = dms.purge(directory);
        assertEquals(0, purged);

    }

    @Test
    void properties() {
        String directory = "some/directory";
        String key = "tmp_test.properties";

        boolean added;

        Map<String, String> props1 = Map.of();
        added = dms.put(directory, key, props1);
        assertTrue(added);

        Map<String, String> props2 = dms.getPropertiesContent(directory, key);
        assertEquals(props1, props2);
        assertEquals(0, props2.size());

        props1 = new LinkedHashMap<>();
        props1.put("A", LocalDateTime.now().toString());
        added = dms.put(directory, key, props1, StandardCharsets.ISO_8859_1);
        assertFalse(added);

        props2 = dms.getPropertiesContent(directory, key);
        assertEquals(props1, props2);
        assertEquals(1, props2.size());

        props1 = new LinkedHashMap<>();
        props1.put("A", LocalDateTime.now().toString());
        props1.put("B", "1234+ľščščťžŤŘČÁĚ");
        added = dms.put(directory, key, props1);
        assertFalse(added);

        props2 = dms.getPropertiesContent(directory, key);
        assertEquals(props1, props2);
        assertEquals(2, props2.size());

    }


    @Test
    void softDelete() {
        String content1 = "some_value";
        String directory = "some/directory";
        String key = "some_key";

        dms.add(directory, key, content1);
        Collection<String> fileKeys = dms.getKeys(directory);
        assertEquals(1, fileKeys.size());
        assertTrue(fileKeys.contains(key));

        boolean exists = dms.exists(directory, key);
        assertTrue(exists);

        String content2 = dms.getTextContent(directory, key);
        assertEquals(content1, content2);

        dms.delete(directory, key);

        boolean exists2 = dms.exists(directory, key);
        assertFalse(exists2);

        try {
            dms.delete(directory, key);
            fail("Should fail");
        } catch (IllegalArgumentException expected) {
        }
        try {
            dms.getTextContent(directory, key);
            fail("Should fail");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    void hardDelete() {
        String content1 = "some_value";
        String directory = "some/directory";
        String key = "some_key";

        dms.add(directory, key, content1);
        Collection<String> fileKeys = dms.getKeys(directory);
        assertEquals(1, fileKeys.size());
        assertTrue(fileKeys.contains(key));

        boolean exists = dms.exists(directory, key);
        assertTrue(exists);

        String content2 = dms.getTextContent(directory, key);
        assertEquals(content1, content2);

        dms.delete(directory, key);
        dms.purge(directory, key, LocalDateTime.now());

        boolean exists2 = dms.exists(directory, key);
        assertFalse(exists2);

        try {
            dms.delete(directory, key);
            fail("Should fail");
        } catch (IllegalArgumentException expected) {
        }
        try {
            dms.getTextContent(directory, key);
            fail("Should fail");
        } catch (IllegalArgumentException expected) {
        }
        try {
            dms.delete(directory, key);
            dms.purge(directory, key, LocalDateTime.now());
            fail("Should fail");
        } catch (IllegalArgumentException expected) {
        }
    }

    @Test
    void hardDeleteAll() throws InterruptedException {
        String content = "some_value";
        String directory = "some/directory";
        String key = "some_key";

        dms.add(directory, key, content);
        Collection<String> fileKeys = dms.getKeys(directory);
        assertEquals(1, fileKeys.size());
        assertTrue(fileKeys.contains(key));

        assertTrue(dms.exists(directory, key));

        int hardDeleted = dms.purge(directory);
        assertEquals(0, hardDeleted);

        assertTrue(dms.exists(directory, key));

        dms.delete(directory, key);
        assertFalse(dms.exists(directory, key));

        dms.add(directory, key, content);
        assertTrue(dms.exists(directory, key));

        LocalDateTime timeBeforeSecondSoftDel = LocalDateTime.now();
        Thread.sleep(Duration.ofSeconds(1));

        dms.delete(directory, key);
        assertFalse(dms.exists(directory, key));

        hardDeleted = dms.purge(directory, timeBeforeSecondSoftDel);
        assertEquals(1, hardDeleted);

        hardDeleted = dms.purge(directory);
        assertEquals(1, hardDeleted);

        dms.add(directory, key, content);
        assertTrue(dms.exists(directory, key));

        dms.delete(directory, key);
        assertFalse(dms.exists(directory, key));

        dms.add(directory, key, content);
        assertTrue(dms.exists(directory, key));

        dms.delete(directory, key);
        assertFalse(dms.exists(directory, key));

        hardDeleted = dms.purge(directory, timeBeforeSecondSoftDel);
        assertEquals(0, hardDeleted);

        hardDeleted = dms.purge(directory);
        assertEquals(2, hardDeleted);

    }
}
