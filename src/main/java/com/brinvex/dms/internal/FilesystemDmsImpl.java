package com.brinvex.dms.internal;

import com.brinvex.dms.api.Dms;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedCollection;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.util.Objects.requireNonNull;

@SuppressWarnings("DuplicatedCode")
public class FilesystemDmsImpl implements Dms {

    private static final Logger LOG = LoggerFactory.getLogger(FilesystemDmsImpl.class);

    private final String workspace;

    private final Path workspacePath;

    private boolean workspaceDeleted;

    private interface IOConsumer<I> {
        void accept(I input) throws IOException;
    }

    private interface IOFunction<I, O> {
        O apply(I input) throws IOException;
    }

    @SuppressWarnings("SpellCheckingInspection")
    private static class SoftDeleteHelper {
        private static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
        private static final Pattern deletedPrefixPattern = Pattern.compile("^_DEL_(\\d{8}_\\d{6}_\\d{3})_!@#-$");
        private static final Pattern overriddenPrefixPattern = Pattern.compile("^_OVR_(\\d{8}_\\d{6}_\\d{3})_!@#-$");
        private static final int deletedPrefixLength = "_DEL_yyyyMMdd_HHmmss_SSS_!@#-".length();
        private static final int overriddenPrefixLength = "_OVR_yyyyMMdd_HHmmss_SSS_!@#-".length();

        private static Path contructSoftDeletedPath(Path oldPath, LocalDateTime timestamp) {
            String prefix = "_DEL_" + dtf.format(timestamp) + "_!@#-";
            return oldPath.getParent().resolve(prefix + oldPath.getFileName());
        }

        private static Path contructOverriddenPath(Path oldPath, LocalDateTime timestamp) {
            String prefix = "_OVR_" + dtf.format(timestamp) + "_!@#-";
            return oldPath.getParent().resolve(prefix + oldPath.getFileName());
        }

        private static boolean isObsolete(String filename, String origKey, LocalDateTime obsoleteBefore) {
            int filenameLength = filename.length();
            if (filenameLength <= deletedPrefixLength) {
                return false;
            }
            String left = filename.substring(0, deletedPrefixLength);
            boolean result = true;
            if (origKey != null) {
                String right = filename.substring(deletedPrefixLength);
                result = right.equals(origKey);
            }
            if (result) {
                Matcher m;
                if ((m = deletedPrefixPattern.matcher(left)).find()) {
                    if (obsoleteBefore != null) {
                        LocalDateTime delDate = LocalDateTime.parse(m.group(1), dtf);
                        result = delDate.isBefore(obsoleteBefore);
                    }
                } else if ((m = overriddenPrefixPattern.matcher(left)).find()) {
                    if (obsoleteBefore != null) {
                        LocalDateTime ovrDate = LocalDateTime.parse(m.group(1), dtf);
                        result = ovrDate.isBefore(obsoleteBefore);
                    }
                } else {
                    result = false;
                }
            }
            return result;
        }

        private static boolean isObsolete(String filename) {
            int filenameLength = filename.length();
            if (filenameLength <= deletedPrefixLength) {
                return false;
            }
            String left = filename.substring(0, deletedPrefixLength);
            return deletedPrefixPattern.matcher(left).matches();
        }
    }

    public FilesystemDmsImpl(Path basePath, String workspace) {
        validateWorkspaceSyntax(workspace);
        this.workspace = workspace;
        this.workspacePath = basePath.resolve(workspace);
        if (!Files.exists(workspacePath)) {
            try {
                Files.createDirectories(workspacePath);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create workspace: %s".formatted(workspacePath), e);
            }
        } else if (!Files.isDirectory(workspacePath)) {
            throw new IllegalArgumentException("Workspace is not a directory: %s".formatted(workspace));
        }
        this.workspaceDeleted = false;
    }

    @Override
    public SequencedCollection<String> getKeys(String directory) {
        validateWorkspaceNotDeleted();
        validateDirectorySyntax(directory);
        Path directoryPath = workspacePath.resolve(directory);
        if (!Files.exists(directoryPath)) {
            return Collections.emptyList();
        } else if (!Files.isDirectory(directoryPath)) {
            throw new IllegalArgumentException("Not a directory: %s, workspace=%s".formatted(directoryPath, workspace));
        }
        try (Stream<Path> fileStream = Files.list(directoryPath)) {
            return fileStream
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(Predicate.not(SoftDeleteHelper::isObsolete))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list files at path: %s".formatted(directoryPath), e);
        }
    }

    @Override
    public void add(String directory, String key, String textContent, Charset charset) {
        add(directory, key, path -> Files.writeString(path, textContent, charset));
    }

    @Override
    public void add(String directory, String key, byte[] binaryContent) {
        add(directory, key, path -> Files.write(path, binaryContent));
    }

    private void add(String directory, String key, IOConsumer<Path> fileWriter) {
        validateWorkspaceNotDeleted();
        validateDirectorySyntax(directory);
        validateKeySyntax(key);
        Path directoryPath = getOrCreateDirectory(directory);
        Path filePath = directoryPath.resolve(key);
        if (Files.exists(filePath)) {
            throw new IllegalArgumentException("Document already exists: workspace='%s', directory='%s', key='%s'"
                    .formatted(workspace, directory, key));
        }
        try {
            fileWriter.accept(filePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write to the file: %s".formatted(filePath), e);
        }
    }

    @Override
    public boolean put(String directory, String key, String textContent, Charset charset) {
        return put(directory, key, path -> Files.writeString(path, textContent, charset));
    }

    @Override
    public boolean put(String directory, String key, byte[] binaryContent) {
        return put(directory, key, path -> Files.write(path, binaryContent));
    }

    @Override
    public boolean put(String directory, String key, Map<String, String> propertiesContent, Charset charset) {
        return put(directory, key, path -> KeyValueFileUtils.writeMapToFile(propertiesContent, path.toFile(), charset));
    }

    private boolean put(String directory, String key, IOConsumer<Path> fileWriter) {
        validateWorkspaceNotDeleted();
        validateDirectorySyntax(directory);
        validateKeySyntax(key);
        Path directoryPath = getOrCreateDirectory(directory);
        Path filePath = directoryPath.resolve(key);
        boolean isNew = !Files.exists(filePath);
        if (!isNew) {
            Path overriddenPath = SoftDeleteHelper.contructOverriddenPath(filePath, LocalDateTime.now());
            try {
                Files.move(filePath, overriddenPath);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to move %s -> %s".formatted(filePath, overriddenPath), e);
            }
        }
        try {
            fileWriter.accept(filePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write to the file: %s".formatted(filePath), e);
        }
        return isNew;
    }

    @Override
    public boolean exists(String directory, String key) {
        validateWorkspaceNotDeleted();
        validateDirectorySyntax(directory);
        validateKeySyntax(key);
        Path directoryPath = workspacePath.resolve(directory);
        if (!Files.exists(directoryPath)) {
            return false;
        } else if (!Files.isDirectory(directoryPath)) {
            throw new IllegalArgumentException("Not a directory: %s, workspace=%s".formatted(directoryPath, workspace));
        }
        return Files.exists(directoryPath.resolve(key));
    }

    @Override
    public String getTextContent(String directory, String key, Charset charset) {
        return getContent(directory, key, path -> Files.readString(path, charset));
    }

    @Override
    public String getTextContent(String directory, String key, Charset charset, Charset alternativeCharset) {
        return getContent(directory, key, path -> {

            List<Charset> charsets = new ArrayList<>();
            charsets.add(requireNonNull(charset));
            if (alternativeCharset != null) {
                charsets.add(alternativeCharset);
            }

            List<CharacterCodingException> characterCodingExceptions = new ArrayList<>();
            try {
                for (Charset chs : charsets) {
                    try {
                        return Files.readString(path, chs);
                    } catch (Throwable throwable) {
                        if (throwable instanceof CharacterCodingException characterCodingException) {
                            characterCodingExceptions.add(characterCodingException);
                        } else {
                            Throwable cause = throwable.getCause();
                            if (cause instanceof CharacterCodingException characterCodingCause) {
                                characterCodingExceptions.add(characterCodingCause);
                            } else {
                                throw throwable;
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                for (Exception charsetException : characterCodingExceptions) {
                    e.addSuppressed(charsetException);
                }
                throw e;
            }

            IOException newestException = characterCodingExceptions.removeLast();
            for (Exception charsetException : characterCodingExceptions) {
                newestException.addSuppressed(charsetException);
            }
            throw newestException;
        });
    }

    @Override
    public List<String> getTextLines(String directory, String key, Charset charset) {
        return getContent(directory, key, path -> Files.readAllLines(path, charset));
    }

    @Override
    public List<String> getTextLines(String directory, String key, int limit, Charset charset) {
        return getContent(directory, key, path -> {
            try (Stream<String> lines = Files.lines(path, charset)) {
                return lines.limit(limit).toList();
            }
        });
    }

    @Override
    public List<String> getTextLines(String directory, String key, int limit, Charset charset, Charset alternativeCharset) {
        return getContent(directory, key, path -> {

            List<Charset> charsets = new ArrayList<>();
            charsets.add(requireNonNull(charset));
            if (alternativeCharset != null) {
                charsets.add(alternativeCharset);
            }

            List<CharacterCodingException> characterCodingExceptions = new ArrayList<>();
            try {
                for (Charset chs : charsets) {
                    try (Stream<String> lines = Files.lines(path, chs)) {
                        return lines.limit(limit).toList();
                    } catch (Throwable throwable) {
                        if (throwable instanceof CharacterCodingException characterCodingException) {
                            characterCodingExceptions.add(characterCodingException);
                        } else {
                            Throwable cause = throwable.getCause();
                            if (cause instanceof CharacterCodingException characterCodingCause) {
                                characterCodingExceptions.add(characterCodingCause);
                            } else {
                                throw throwable;
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                for (Exception charsetException : characterCodingExceptions) {
                    e.addSuppressed(charsetException);
                }
                throw e;
            }

            IOException newestException = characterCodingExceptions.removeLast();
            for (Exception charsetException : characterCodingExceptions) {
                newestException.addSuppressed(charsetException);
            }
            throw newestException;
        });
    }

    @Override
    public byte[] getBinaryContent(String directory, String key) {
        return getContent(directory, key, Files::readAllBytes);
    }

    @Override
    public Map<String, String> getPropertiesContent(String directory, String key, Charset charset) {
        return getContent(directory, key, path -> KeyValueFileUtils.readMapFromFile(path.toFile(), charset));
    }

    @Override
    public LocalDateTime getLastModifiedTime(String directory, String key) {
        validateWorkspaceNotDeleted();
        validateDirectorySyntax(directory);
        validateKeySyntax(key);
        Path filePath = workspacePath.resolve(directory).resolve(key);
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("Document doesn't exist: workspace='%s', directory='%s', key='%s'".formatted(workspace, directory, key));
        }
        try {
            FileTime ft = Files.getLastModifiedTime(filePath);
            return LocalDateTime.ofInstant(ft.toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to get the last modified time %s".formatted(filePath), e);
        }
    }

    private <CONTENT> CONTENT getContent(String directory, String key, IOFunction<Path, CONTENT> fileReader) {
        validateWorkspaceNotDeleted();
        validateDirectorySyntax(directory);
        validateKeySyntax(key);
        Path filePath = workspacePath.resolve(directory).resolve(key);
        if (!Files.exists(filePath)) {
            throw new IllegalArgumentException("Document doesn't exist: workspace='%s', directory='%s', key='%s'".formatted(workspace, directory, key));
        }
        try {
            return fileReader.apply(filePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the file %s".formatted(filePath), e);
        }
    }

    @Override
    public void delete(String directory, String key) {
        delete(directory, Set.of(key));
    }

    @Override
    public void delete(String directory, Collection<String> keys) {
        validateWorkspaceNotDeleted();
        validateDirectorySyntax(directory);
        for (String key : keys) {
            validateKeySyntax(key);
        }
        for (String key : keys) {
            Path filePath = workspacePath.resolve(directory).resolve(key);
            if (!Files.exists(filePath)) {
                throw new IllegalArgumentException("Document doesn't exist: workspace='%s', directory='%s', key='%s'"
                        .formatted(workspace, directory, key));
            }
            Path newSoftDelPath = SoftDeleteHelper.contructSoftDeletedPath(filePath, LocalDateTime.now());
            try {
                Files.move(filePath, newSoftDelPath);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to move %s -> %s".formatted(filePath, newSoftDelPath), e);
            }
        }
    }

    @Override
    public <KEY> SequencedMap<KEY, String> getRedundantPeriodKeys(
            String directory,
            Function<String, KEY> keyFnc,
            Function<KEY, LocalDate> keyStartDateInclFnc,
            Function<KEY, LocalDate> keyEndDateInclFnc) {
        SequencedCollection<String> rawKeys = getKeys(directory);
        if (rawKeys.isEmpty()) {
            return Collections.emptySortedMap();
        }
        SequencedMap<KEY, String> keys = new LinkedHashMap<>();
        for (String rawKey : rawKeys) {
            KEY key = keyFnc.apply(rawKey);
            if (key != null) {
                if (keys.put(key, rawKey) != null) {
                    throw new IllegalStateException("Duplicate key: %s, %s".formatted(rawKey, key));
                }
            }
        }
        SequencedSet<KEY> redundantKeys = getRedundantPeriodKeys(keys.keySet(), keyStartDateInclFnc, keyEndDateInclFnc);
        keys.keySet().retainAll(redundantKeys);
        return keys;
    }

    @Override
    public <KEY> SequencedSet<KEY> getRedundantPeriodKeys(
            Collection<KEY> keys,
            Function<KEY, LocalDate> keyStartDateInclFnc,
            Function<KEY, LocalDate> keyEndDateInclFnc
    ) {
        return PeriodDocUtils.findRedundantKeys(keys, keyStartDateInclFnc, keyEndDateInclFnc);
    }

    @Override
    public int purge(String directory, String origKey, LocalDateTime softDeletedBefore) {
        validateWorkspaceNotDeleted();
        validateDirectorySyntax(directory);
        if (origKey != null) {
            validateKeySyntax(origKey);
        }
        Path directoryPath = workspacePath.resolve(directory);
        if (!Files.exists(directoryPath)) {
            return 0;
        } else if (!Files.isDirectory(directoryPath)) {
            throw new IllegalArgumentException("Not a directory: %s, workspace=%s".formatted(directoryPath, workspace));
        }
        List<Path> filesToHardDelete;
        try (Stream<Path> fileStream = Files.list(directoryPath)) {
            filesToHardDelete = fileStream
                    .filter(p -> SoftDeleteHelper.isObsolete(p.getFileName().toString(), origKey, softDeletedBefore))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to list files at path: %s".formatted(directoryPath), e);
        }
        for (Path fileToHardDelete : filesToHardDelete) {
            try {
                LOG.info("Hard deleting: {}", fileToHardDelete);
                Files.delete(fileToHardDelete);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to delete: %s".formatted(fileToHardDelete), e);
            }
        }
        return filesToHardDelete.size();
    }

    @Override
    public void resetWorkspace() {
        if (!workspaceDeleted) {
            deleteWorkspace();
        }
        try {
            Files.createDirectory(workspacePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to init workspace %s".formatted(workspacePath), e);
        }
        workspaceDeleted = false;
    }

    @Override
    public void deleteWorkspace() {
        validateWorkspaceNotDeleted();
        Path newSoftDelWorkspacePath = SoftDeleteHelper.contructSoftDeletedPath(workspacePath, LocalDateTime.now());
        try {
            Files.move(workspacePath, newSoftDelWorkspacePath);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to move %s -> %s".formatted(workspacePath, newSoftDelWorkspacePath), e);
        }
        workspaceDeleted = true;
    }

    @Override
    public int purgeWorkspace(LocalDateTime softDeletedBefore) {
        try (Stream<Path> workspaces = Files.list(workspacePath.getParent())) {
            List<Path> obsoleteWorkspaceVersions = workspaces
                    .filter(ws -> SoftDeleteHelper.isObsolete(ws.getFileName().toString(), workspace, softDeletedBefore))
                    .toList();
            for (Path obsoleteWorkspaceVersion : obsoleteWorkspaceVersions) {
                try (Stream<Path> wsChildStream = Files.walk(obsoleteWorkspaceVersion)) {
                    wsChildStream
                            .sorted(Comparator.reverseOrder())
                            .peek(f -> LOG.info("Recursively hard-deleting: {} ", f))
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    throw new UncheckedIOException(e);
                                }
                            });
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return obsoleteWorkspaceVersions.size();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path getOrCreateDirectory(String directory) {
        Path directoryPath = workspacePath.resolve(directory);
        if (!Files.exists(directoryPath)) {
            try {
                Files.createDirectories(directoryPath);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create directory: %s".formatted(directoryPath), e);
            }
        } else if (!Files.isDirectory(directoryPath)) {
            throw new IllegalArgumentException("Not a directory: %s, workspace=%s".formatted(directoryPath, workspace));
        }
        return directoryPath;
    }

    private void validateWorkspaceNotDeleted() {
        if (workspaceDeleted) {
            throw new IllegalStateException("Workspace already deleted - '%s'".formatted(workspace));
        }
    }

    private void validateWorkspaceSyntax(String workspaceName) {
        if (workspaceName == null || workspaceName.isBlank()) {
            throw new IllegalArgumentException("Invalid workspace: %s".formatted(workspaceName));
        }
    }

    private void validateDirectorySyntax(String directoryName) {
        if (directoryName == null || directoryName.isBlank()) {
            throw new IllegalArgumentException("Invalid directory: %s".formatted(directoryName));
        }
    }

    private void validateKeySyntax(String keyName) {
        if (keyName == null || keyName.isBlank()) {
            throw new IllegalArgumentException("Invalid key: %s".formatted(keyName));
        }
    }

}
