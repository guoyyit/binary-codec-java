package com.github.guoyyit.binarycodecjava.codec;

/**
 * 编解码入口门面，可被多线程共享。编码器与解码器均无内部可变状态，
 * 并发编码同一消息与单线程结果一致，并发解码互不干扰。
 */
public final class Codec {

    private final SchemaRegistry registry;
    private final BinaryEncoder encoder;
    private final BinaryDecoder decoder;

    public Codec(SchemaRegistry registry) {
        this(registry, CodecLimits.DEFAULT);
    }

    public Codec(SchemaRegistry registry, CodecLimits limits) {
        this.registry = registry;
        this.encoder = new BinaryEncoder(registry);
        this.decoder = new BinaryDecoder(registry, limits);
    }

    public byte[] encode(MessageValue value) {
        return encoder.encode(value);
    }

    public MessageValue decode(String messageName, byte[] data) {
        return decoder.decode(messageName, data);
    }

    /**
     * 创建指定消息类型的空实例；类型未注册时抛出 {@link CodecException}。
     */
    public MessageValue newMessage(String messageName) {
        MessageDef def = registry.findMessage(messageName);
        if (def == null) {
            throw new CodecException("unknown message type '" + messageName + "'");
        }
        return new MessageValue(def, registry.defaultResolver());
    }
}
