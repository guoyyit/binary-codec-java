package com.github.guoyyit.binarycodecjava.tests;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.guoyyit.binarycodecjava.codec.Codec;
import com.github.guoyyit.binarycodecjava.codec.MessageValue;
import com.github.guoyyit.binarycodecjava.codec.SchemaException;
import com.github.guoyyit.binarycodecjava.codec.SchemaRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 并发场景：共享编解码入口、并发热注册、并发编码确定性与并发解码隔离。
 */
class ConcurrencyTest {

    static final String SCHEMA = """
            message Event {
                int64 id = 1;
                string topic = 2;
                repeated int32 values = 3;
                map<string, int64> tags = 4;
            }
            """;

    private MessageValue sample(Codec codec, long id) {
        return codec.newMessage("Event")
                .set("id", id)
                .set("topic", "t" + (id % 4))
                .set("values", List.of(1L, 2L, 3L))
                .set("tags", Map.of("k" + (id % 3), id));
    }

    @Test
    void concurrentEncodingMatchesSingleThreadedResult() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(SCHEMA);
        Codec codec = new Codec(registry);
        MessageValue value = sample(codec, 7L);
        byte[] expected = codec.encode(value);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Future<?>[] futures = new Future<?>[threads];
        for (int i = 0; i < threads; i++) {
            futures[i] = pool.submit(() -> {
                start.await();
                for (int j = 0; j < 200; j++) {
                    assertArrayEquals(expected, codec.encode(value));
                }
                return null;
            });
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
    }

    @Test
    void concurrentDecodingIsIsolated() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(SCHEMA);
        Codec codec = new Codec(registry);
        int threads = 8;
        byte[][] inputs = new byte[threads][];
        for (int i = 0; i < threads; i++) {
            inputs[i] = codec.encode(sample(codec, i));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Future<?>[] futures = new Future<?>[threads];
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures[i] = pool.submit(() -> {
                start.await();
                for (int j = 0; j < 200; j++) {
                    MessageValue decoded = codec.decode("Event", inputs[index]);
                    assertEquals((long) index, decoded.get("id"));
                    assertEquals("t" + (index % 4), decoded.get("topic"));
                }
                return null;
            });
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
    }

    @Test
    void concurrentHotRegistrationKeepsRegistryConsistent() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(SCHEMA);
        Codec codec = new Codec(registry);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        Future<?>[] futures = new Future<?>[threads];
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures[i] = pool.submit(() -> {
                start.await();
                for (int j = 0; j < 50; j++) {
                    try {
                        registry.register("message Hot" + index + "_" + j + " { int32 v = 1; }");
                    } catch (SchemaException e) {
                        failures.incrementAndGet();
                    }
                    MessageValue roundTrip = codec.decode("Event", codec.encode(sample(codec, j)));
                    assertEquals((long) j, roundTrip.get("id"));
                }
                return null;
            });
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertEquals(0, failures.get());
        assertNotNull(registry.findMessage("Hot3_25"));
        assertNotNull(registry.findMessage("Event"));
    }

    @Test
    void failedRegistrationDuringConcurrentReadDoesNotCorrupt() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(SCHEMA);
        Codec codec = new Codec(registry);
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Future<?>[] futures = new Future<?>[threads];
        for (int i = 0; i < threads; i++) {
            final boolean bad = i % 2 == 0;
            futures[i] = pool.submit(() -> {
                start.await();
                for (int j = 0; j < 100; j++) {
                    if (bad) {
                        try {
                            registry.register("message Broken { Missing x = 1; }");
                        } catch (SchemaException expected) {
                            // 预期失败，不应影响已生效模式
                        }
                    } else {
                        MessageValue roundTrip = codec.decode("Event", codec.encode(sample(codec, j)));
                        assertEquals((long) j, roundTrip.get("id"));
                    }
                }
                return null;
            });
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertTrue(registry.findMessage("Broken") == null);
        assertNotNull(registry.findMessage("Event"));
    }
}
