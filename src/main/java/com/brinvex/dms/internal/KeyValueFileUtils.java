package com.brinvex.dms.internal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

class KeyValueFileUtils {

    /**
     * Writes a Map to a text file. Each entry is written as key=value.
     */
    public static void writeMapToFile(Map<String, String> map, File file, Charset charset) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file, charset))) {
            for (Map.Entry<String, String> entry : map.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue());
                writer.newLine();
            }
        }
    }

    /**
     * Reads a Map from a text file. Each line should be formatted as key=value.
     */
    public static Map<String, String> readMapFromFile(File file, Charset charset) throws IOException {
        Map<String, String> map = new LinkedHashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(file, charset))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("=", 2);
                if (parts.length >= 2) {
                    String key = parts[0];
                    String value = parts[1];
                    map.put(key, value);
                } else {
                    throw new IllegalStateException("Invalid line: " + line);
                }
            }
        }
        return map;
    }
}