package com.github.guoyyit.binarycodecjava.registry;

import com.github.guoyyit.binarycodecjava.parser.SchemaException;
import com.github.guoyyit.binarycodecjava.parser.SchemaParser;
import com.github.guoyyit.binarycodecjava.model.Schema;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SchemaRegistry {
    private final Map<String, Map<String, Schema>> schemas = new ConcurrentHashMap<>();
    private final SchemaParser parser = new SchemaParser();

    public void register(String schemaText) {
        Schema candidate = parser.parse(schemaText);
        Map<String, Schema> versions =
                schemas.computeIfAbsent(candidate.name(), key -> new ConcurrentHashMap<>());
        Schema existing = versions.putIfAbsent(candidate.version(), candidate);
        if (existing != null) {
            throw new SchemaException("schema '" + candidate.name() + "' version "
                    + candidate.version() + " is already registered", 1);
        }
    }

    public Schema get(String name, String version) {
        Map<String, Schema> versions = schemas.get(name);
        Schema schema = versions == null ? null : versions.get(version);
        if (schema == null) {
            throw new IllegalArgumentException(
                    "schema '" + name + "' version '" + version + "' is not registered");
        }
        return schema;
    }

    public Schema getLatest(String name) {
        Map<String, Schema> versions = schemas.get(name);
        if (versions == null || versions.isEmpty()) {
            throw new IllegalArgumentException("schema '" + name + "' is not registered");
        }
        Schema latest = null;
        for (Schema schema : versions.values()) {
            if (latest == null || schema.version().compareTo(latest.version()) > 0) {
                latest = schema;
            }
        }
        return latest;
    }

    public boolean isRegistered(String name) {
        Map<String, Schema> versions = schemas.get(name);
        return versions != null && !versions.isEmpty();
    }
}
