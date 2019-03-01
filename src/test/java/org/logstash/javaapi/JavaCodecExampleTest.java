package org.logstash.javaapi;

import co.elastic.logstash.api.Codec;
import co.elastic.logstash.api.Configuration;
import org.junit.Assert;
import org.junit.Test;
import org.logstash.Event;
import org.logstash.plugins.ConfigurationImpl;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class JavaCodecExampleTest {

    @Test
    public void testJavaCodec_decode() {
        String delimiter = "/";
        Map<String, Object> configValues = new HashMap<>();
        configValues.put(JavaCodecExample.DELIMITER_CONFIG.name(), delimiter);
        Configuration config = new ConfigurationImpl(configValues);
        JavaCodecExample codec = new JavaCodecExample(config, null);

        TestConsumer testConsumer = new TestConsumer();
        String[] inputs = {"foo", "bar", "baz"};
        String input = String.join(delimiter, inputs);
        codec.decode(ByteBuffer.wrap(input.getBytes()), testConsumer);

        List<Map<String, Object>> events = testConsumer.getEvents();
        Assert.assertEquals(inputs.length, events.size());
        for (int k = 0; k < inputs.length; k++) {
            Assert.assertEquals(inputs[k], events.get(k).get("message"));
        }
    }

    @Test
    public void testJavaCodec_encode() throws Codec.EncodeException {
        String delimiter = "/";
        Map<String, Object> configValues = new HashMap<>();
        configValues.put(JavaCodecExample.DELIMITER_CONFIG.name(), delimiter);
        Configuration config = new ConfigurationImpl(configValues);
        JavaCodecExample codec = new JavaCodecExample(config, null);

        byte[] bytes = new byte[100];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        Event e = new Event();
        e.setField("message", "foo");
        boolean result = codec.encode(e, buffer);

        String resultString = new String(buffer.array(), buffer.position(), buffer.limit());
        Assert.assertTrue(result);
        Assert.assertTrue(resultString.contains("foo"));
        Assert.assertTrue(resultString.endsWith(delimiter));
    }

    @Test
    public void testClone() throws Codec.EncodeException {
        String delimiter = "/";
        Map<String, Object> configValues = new HashMap<>();
        configValues.put(JavaCodecExample.DELIMITER_CONFIG.name(), delimiter);
        Configuration config = new ConfigurationImpl(configValues);

        JavaCodecExample codec1 = new JavaCodecExample(config, null);
        byte[] bytes = new byte[100];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        Event e = new Event();
        e.setField("message", "foo");
        codec1.encode(e, buffer);

        JavaCodecExample codec2 = (JavaCodecExample) codec1.cloneCodec();
        byte[] bytes2 = new byte[100];
        ByteBuffer buffer2 = ByteBuffer.wrap(bytes2);
        codec1.encode(e, buffer2);

        Assert.assertEquals(new String(buffer.array(), buffer.position(), buffer.limit()),
                new String(buffer2.array(), buffer2.position(), buffer2.limit()));
        Assert.assertNotEquals(codec1.getId(), codec2.getId());
    }
}

class TestConsumer implements Consumer<Map<String, Object>> {

    List<Map<String, Object>> events = new ArrayList<>();

    @Override
    public void accept(Map<String, Object> stringObjectMap) {
        events.add(stringObjectMap);
    }

    public List<Map<String, Object>> getEvents() {
        return events;
    }

}