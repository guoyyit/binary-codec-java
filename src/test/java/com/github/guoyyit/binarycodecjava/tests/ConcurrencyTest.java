package com.github.guoyyit.binarycodecjava.tests;

import com.github.guoyyit.binarycodecjava.codec.Codec;
import com.github.guoyyit.binarycodecjava.model.MessageValue;
import com.github.guoyyit.binarycodecjava.parser.SchemaException;
import com.github.guoyyit.binarycodecjava.registry.SchemaRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTest {

    private static final String SCHEMA = """
            schema shop;
            version "1";
            message Product {
                int64 id = 1;
                string name = 2;
                map<string, int32> attributes = 3;
            }
            """;

    private Codec codec() {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(SCHEMA);
        return new Codec(registry);
    }

    @Test
    void concurrentEncodingOfSharedMessageIsStable() throws Exception {
        Codec codec = codec();
        MessageValue product = codec.newMessage("shop", "Product");
        product.setField("id", 42L);
        product.setField("name", "widget");
        java.util.Map<String, Integer> attributes = new java.util.LinkedHashMap<>();
        attributes.put("z", 1);
        attributes.put("a", 2);
        product.setField("attributes", attributes);

        byte[] expected = codec.encode(product);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<byte[]>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return codec.encode(product);
            }));
        }
        start.countDown();
        for (Future<byte[]> future : futures) {
            assertArrayEquals(expected, future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
    }

    @Test
    void concurrentDecodingRunsIndependently() throws Exception {
        Codec codec = codec();
        List<byte[]> messages = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            MessageValue product = codec.newMessage("shop", "Product");
            product.setField("id", i);
            product.setField("name", "item-" + i);
            messages.add(codec.encode(product));
        }

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    for (int round = 0; round < 100; round++) {
                        int index = (round + System.identityHashCode(Thread.currentThread()))
                                & Integer.MAX_VALUE;
                        byte[] data = messages.get(index % messages.size());
                        MessageValue decoded = codec.decode(data, "shop", "Product");
                        byte[] reencoded = codec.encode(decoded);
                        assertArrayEquals(data, reencoded);
                    }
                } catch (Throwable throwable) {
                    errors.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(0, errors.get());
    }

    @Test
    void concurrentReadsStayConsistentDuringHotRegistration() throws Exception {
        SchemaRegistry registry = new SchemaRegistry();
        registry.register(SCHEMA);
        Codec codec = new Codec(registry);

        String badSchema = """
                schema broken;
                version "1";
                message M {
                    Missing ref = 1;
                }
                """;

        String goodNewSchema = """
                schema metrics;
                version "1";
                message Metric {
                    int64 value = 1;
                }
                """;

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int worker = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 200; i++) {
                    if (worker == 0 && i % 10 == 0) {
                        assertThrows(SchemaException.class, () -> registry.register(badSchema));
                    } else if (worker == 1 && i == 0) {
                        registry.register(goodNewSchema);
                    } else {
                        assertEquals("1", registry.get("shop", "1").version());
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertTrue(registry.isRegistered("shop"));
        assertTrue(registry.isRegistered("metrics"));
        assertEquals("1", registry.get("shop", "1").version());

        MessageValue metric = codec.newMessage("metrics", "Metric");
        metric.setField("value", 99L);
        byte[] wire = codec.encode(metric);
        assertEquals(99L, codec.decode(wire, "metrics", "Metric").getLong("value"));
    }
}
