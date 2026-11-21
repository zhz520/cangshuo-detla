package com.delta.system.service;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Non-secret build identity used to compare deployed backend code across tenants. */
@Component
public class RuntimeBuildIdentity {
    private static final Pattern VERSION = Pattern.compile("\"version\"\\s*:\\s*\"([0-9]+\\.[0-9]+\\.[0-9]+)\"");
    private volatile Snapshot snapshot;

    public record Snapshot(String version, String buildId) {}

    public Snapshot current() {
        Snapshot value = snapshot;
        if (value == null) {
            synchronized (this) {
                value = snapshot;
                if (value == null) snapshot = value = load();
            }
        }
        return value;
    }

    private Snapshot load() {
        String version = "unknown";
        try (InputStream input = getClass().getResourceAsStream("/platform/catalog.json")) {
            if (input != null) {
                Matcher matcher = VERSION.matcher(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                if (matcher.find()) version = matcher.group(1);
            }
        } catch (Exception ignored) { }
        String build = "development";
        try {
            Path jar = executableJar(getClass().getProtectionDomain().getCodeSource().getLocation());
            if (jar != null) build = sha256(jar);
            else if (!runningFromDevelopmentClasspath()) build = "unavailable";
        } catch (Exception ignored) { }
        return new Snapshot(version, build);
    }

    static Path executableJar(URL location) {
        Path located = jarFromLocation(location);
        if (located != null) return located;
        // Spring Boot's executable launcher normally exposes only the application
        // JAR on java.class.path. Accept it only when the choice is unambiguous.
        List<Path> jars = new ArrayList<>();
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(Pattern.quote(java.io.File.pathSeparator))) {
            if (entry.isBlank()) continue;
            Path candidate = Path.of(entry).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate) && candidate.getFileName().toString().endsWith(".jar")) {
                jars.add(candidate);
            }
        }
        return jars.size() == 1 ? jars.get(0) : null;
    }

    static Path jarFromLocation(URL location) {
        if (location == null) return null;
        try {
            if ("file".equalsIgnoreCase(location.getProtocol())) {
                Path path = Path.of(location.toURI()).toAbsolutePath().normalize();
                return Files.isRegularFile(path) && path.toString().endsWith(".jar") ? path : null;
            }
            if ("jar".equalsIgnoreCase(location.getProtocol())) {
                try {
                    if (location.openConnection() instanceof JarURLConnection connection) {
                        Path path = Path.of(connection.getJarFileURL().toURI()).toAbsolutePath().normalize();
                        if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) return path;
                    }
                } catch (Exception ignored) {
                    // Spring Boot nested classpaths are not always accepted by JarURLConnection.
                }
                String text = location.toString();
                int separator = text.indexOf("!/");
                if (separator > 4) {
                    String outer = text.substring(0, separator);
                    while (outer.startsWith("jar:")) outer = outer.substring(4);
                    if (outer.startsWith("file:")) {
                        Path path = Path.of(URI.create(outer)).toAbsolutePath().normalize();
                        if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) return path;
                    }
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream raw = Files.newInputStream(file);
             DigestInputStream hashed = new DigestInputStream(raw, digest)) {
            hashed.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean runningFromDevelopmentClasspath() {
        String classpath = System.getProperty("java.class.path", "");
        if (classpath.isBlank()) return false;
        for (String entry : classpath.split(Pattern.quote(java.io.File.pathSeparator))) {
            if (!entry.isBlank() && Files.isDirectory(Path.of(entry))) return true;
        }
        return false;
    }
}
