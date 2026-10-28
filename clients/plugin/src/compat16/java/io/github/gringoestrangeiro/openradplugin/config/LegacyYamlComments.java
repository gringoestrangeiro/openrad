package io.github.gringoestrangeiro.openradplugin.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class LegacyYamlComments {
    private static final long MAX_SIZE = 256L * 1024L;

    private LegacyYamlComments() {}

    public static String preserve(Path original, String serialized) throws IOException {
        if (Files.isSymbolicLink(original) || !Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)
                || Files.size(original) > MAX_SIZE) {
            throw new IOException("invalid legacy config path or size");
        }
        List<String> comments = new ArrayList<>();
        for (String line : Files.readAllLines(original, StandardCharsets.UTF_8)) {
            if (line.stripLeading().startsWith("#")) {
                comments.add(line.stripLeading());
            }
        }
        if (comments.isEmpty()) return serialized;
        StringBuilder result = new StringBuilder(serialized.length() + comments.size() * 70);
        for (String line : comments) {
            result.append(line).append('\n');
        }
        result.append('\n').append(serialized);
        return result.toString();
    }
}
