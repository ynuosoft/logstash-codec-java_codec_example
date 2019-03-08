package org.logstash.javaapi;

import co.elastic.logstash.api.Codec;
import co.elastic.logstash.api.Configuration;
import co.elastic.logstash.api.Context;
import co.elastic.logstash.api.Event;
import co.elastic.logstash.api.LogstashPlugin;
import co.elastic.logstash.api.PluginConfigSpec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

// class name must match plugin name
@LogstashPlugin(name="java_codec_example")
public class JavaCodecExample implements Codec {

    public static final PluginConfigSpec<String> DELIMITER_CONFIG =
            PluginConfigSpec.stringSetting("delimiter", ",");

    private final String id;
    private final String delimiter;
    private final CharsetEncoder encoder;
    private Event currentEncodedEvent;
    private CharBuffer currentEncoding;

    // all codec plugins must provide a constructor that accepts Configuration and Context
    public JavaCodecExample(final Configuration config, final Context context) {
        this(config.get(DELIMITER_CONFIG));
    }

    private JavaCodecExample(String delimiter) {
        this.id = UUID.randomUUID().toString();
        this.delimiter = delimiter;
        this.encoder = Charset.defaultCharset().newEncoder();
    }

    @Override
    public void decode(ByteBuffer byteBuffer, Consumer<Map<String, Object>> consumer) {
        // a not-production-grade delimiter decoder
        byte[] byteInput = new byte[byteBuffer.remaining()];
        byteBuffer.get(byteInput);
        if (byteInput.length > 0) {
            String input = new String(byteInput);
            String[] split = input.split(delimiter);
            for (String s : split) {
                Map<String, Object> map = new HashMap<>();
                map.put("message", s);
                consumer.accept(map);
            }
        }
    }

    @Override
    public void flush(ByteBuffer byteBuffer, Consumer<Map<String, Object>> consumer) {
        // if the codec maintains any internal state such as partially-decoded input, this
        // method should flush that state along with any additional input supplied in
        // the ByteBuffer

        decode(byteBuffer, consumer); // this is a simplistic implementation
    }

    @Override
    public boolean encode(Event event, ByteBuffer buffer) throws EncodeException {
        try {
            if (currentEncodedEvent != null && event != currentEncodedEvent) {
                throw new EncodeException("New event supplied before encoding of previous event was completed");
            } else if (currentEncodedEvent == null) {
                currentEncoding = CharBuffer.wrap(event.toString() + delimiter);
            }

            CoderResult result = encoder.encode(currentEncoding, buffer, true);
            buffer.flip();
            if (result.isError()) {
                result.throwException();
            }

            if (result.isOverflow()) {
                currentEncodedEvent = event;
                return false;
            } else {
                currentEncodedEvent = null;
                return true;
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public Collection<PluginConfigSpec<?>> configSchema() {
        // should return a list of all configuration options for this plugin
        return Collections.singletonList(DELIMITER_CONFIG);
    }

    @Override
    public Codec cloneCodec() {
        return new JavaCodecExample(this.delimiter);
    }

    @Override
    public String getId() {
        return this.id;
    }

}
