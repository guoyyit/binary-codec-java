package com.github.guoyyit.binarycodecjava.codec;

import com.github.guoyyit.binarycodecjava.model.MessageDescriptor;
import com.github.guoyyit.binarycodecjava.model.MessageValue;
import com.github.guoyyit.binarycodecjava.registry.SchemaRegistry;

public final class Codec {
    private final SchemaRegistry registry;
    private final BinaryEncoder encoder = new BinaryEncoder();
    private final BinaryDecoder decoder;

    public Codec(SchemaRegistry registry) {
        this.registry = registry;
        this.decoder = new BinaryDecoder(registry);
    }

    public SchemaRegistry registry() {
        return registry;
    }

    public MessageValue newMessage(String schemaName, String messageName) {
        MessageDescriptor descriptor = registry.getLatest(schemaName).findMessage(messageName);
        if (descriptor == null) {
            throw new IllegalArgumentException(
                    "message '" + messageName + "' not found in schema " + schemaName);
        }
        return new MessageValue(descriptor);
    }

    public byte[] encode(MessageValue message) {
        return encoder.encode(message);
    }

    public MessageValue decode(byte[] data, String schemaName, String messageName) {
        return decoder.decode(data, schemaName, messageName);
    }

    public MessageValue decode(byte[] data, String schemaName, String version, String messageName) {
        return decoder.decode(data, schemaName, version, messageName);
    }

    public MessageValue decode(byte[] data, MessageDescriptor descriptor) {
        return decoder.decode(data, descriptor);
    }
}
