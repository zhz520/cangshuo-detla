package com.delta.admin.platform;

import com.delta.common.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Instance-local commercial state, separate from customer business tables. */
final class PlatformStateStore {
    record State(int formatVersion, String instanceId, PlatformLicense.Envelope license, Set<String> enabledFeatures,
                 Map<String, Boolean> remoteFeatureEnabled, Map<String, Instant> remoteFeatureStartsAt,
                 Map<String, Instant> remoteFeatureExpiresAt, Set<String> updateComponents, JsonNode remoteProject) {
        State {
            enabledFeatures = enabledFeatures == null ? Set.of() : Set.copyOf(enabledFeatures);
            remoteFeatureEnabled = remoteFeatureEnabled == null ? Map.of() : Map.copyOf(remoteFeatureEnabled);
            remoteFeatureStartsAt = remoteFeatureStartsAt == null ? Map.of() : Map.copyOf(remoteFeatureStartsAt);
            remoteFeatureExpiresAt = remoteFeatureExpiresAt == null ? Map.of() : Map.copyOf(remoteFeatureExpiresAt);
            updateComponents = updateComponents == null ? null : Set.copyOf(updateComponents);
            remoteProject = remoteProject == null || remoteProject.isNull() ? null : remoteProject;
        }
    }
    private final Path path;
    private final ObjectMapper json;
    private final String initialInstanceId;

    PlatformStateStore(String path, ObjectMapper json) {
        this(path,json,null);
    }

    PlatformStateStore(String path, ObjectMapper json, String initialInstanceId) {
        this.path = Path.of(path).toAbsolutePath().normalize();
        this.json = json;
        this.initialInstanceId = initialInstanceId == null || initialInstanceId.isBlank() ? null : initialInstanceId;
        if (this.initialInstanceId != null) UUID.fromString(this.initialInstanceId);
    }

    State load() {
        if (!Files.exists(path)) {
            var state = new State(1, initialInstanceId == null ? UUID.randomUUID().toString() : initialInstanceId,
                    null, Set.of(), Map.of(), Map.of(), Map.of(), null, null);
            save(state);
            return state;
        }
        try {
            if (Files.size(path) > 65536) throw new IOException("State too large");
            var state = json.readValue(path.toFile(), State.class);
            if (state.formatVersion() != 1) throw new IOException("Invalid state");
            UUID.fromString(state.instanceId());
            if (initialInstanceId != null && !initialInstanceId.equals(state.instanceId())) {
                throw new IOException("State instance does not match the configured tenant instance");
            }
            return state;
        } catch (Exception error) {
            throw new BusinessException(503, "功能授权状态无法读取，请联系维护人员；原有业务功能不受影响");
        }
    }

    void save(State state) {
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(path.getParent(), ".platform-", ".json");
            Files.write(temporary, json.writeValueAsBytes(state));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            throw new BusinessException(503, "功能授权状态保存失败，请检查实例存储权限");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
}
