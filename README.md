# Logstash Java Plugin

[![Travis Build Status](https://travis-ci.org/logstash-plugins/logstash-codec-java_codec_example.svg)](https://travis-ci.org/logstash-plugins/logstash-codec-java_codec_example)

This is a Java plugin for [Logstash](https://github.com/elastic/logstash).

It is fully free and fully open source. The license is Apache 2.0, meaning you are free to use it however you want.

## How to write a Java codec

> <b>IMPORTANT NOTE:</b> Native support for Java plugins in Logstash is in the beta phase. While no API changes are
anticipated, some changes may be necessary before GA.

### Overview 

Native support for Java plugins in Logstash consists of several components including:
* Extensions to the Java execution engine to support running Java plugins in Logstash pipelines
* APIs for developing Java plugins. The APIs are in the `co.elastic.logstash.api` package. If a Java plugin 
references any classes or specific concrete implementations of API interfaces outside that package, breakage may 
occur because the implementation of classes outside of the API package may change at any time.
* Tooling to automate the packaging and deployment of Java plugins in Logstash [not complete as of the beta phase]

To develop a new Java codec for Logstash, you write a new Java class that conforms to the Logstash Java Codec
API, package it, and install it with the `logstash-plugin` utility. We'll go through each of those steps in this guide.

### Coding the plugin

It is recommended that you start by copying the 
[example codec plugin](https://github.com/logstash-plugins/logstash-codec-java_codec_example). The example codec
plugin decodes messages separated by a configurable delimiter and encodes messages by writing their string 
representation separated by a delimiter. For example, if the codec were configured with `/` as the delimiter, the input
text `event1/event2/` would be decoded into two separate events with `message` fields of `event1` and `event2`, 
respectively. Note that this is only an example codec and does not cover all the edge cases that a
production-grade codec should cover.

Let's look at the main class in that codec filter:
 
```java
@LogstashPlugin(name="java_codec_example")
public class JavaCodecExample implements Codec {

    public static final PluginConfigSpec<String> DELIMITER_CONFIG =
            PluginConfigSpec.stringSetting("delimiter", ",");

    private final String id;
    private final String delimiter;
    private final CharsetEncoder encoder;
    private Event currentEncodedEvent;
    private CharBuffer currentEncoding;

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
```

Let's step through and examine each part of that class.

#### Class declaration
```java
@LogstashPlugin(name="java_codec_example")
public class JavaCodecExample implements Codec {
```
There are two things to note about the class declaration:
* All Java plugins must be annotated with the `@LogstashPlugin` annotation. Additionally:
  * The `name` property of the annotation must be supplied and defines the name of the plugin as it will be used
   in the Logstash pipeline definition. For example, this codec would be referenced in the codec section of the
   an appropriate input or output in the Logstash pipeline defintion as `codec => java_codec_example { }`
  * The value of the `name` property must match the name of the class excluding casing and underscores.
* The class must implement the `co.elastic.logstash.api.Codec` interface.

#### Plugin settings

The snippet below contains both the setting definition and the method referencing it:
```java
public static final PluginConfigSpec<String> DELIMITER_CONFIG =
        PluginConfigSpec.stringSetting("delimiter", ",");

@Override
public Collection<PluginConfigSpec<?>> configSchema() {
    return Collections.singletonList(DELIMITER_CONFIG);
}
```
The `PluginConfigSpec` class allows developers to specify the settings that a plugin supports complete with setting 
name, data type, deprecation status, required status, and default value. In this example, the `delimiter` setting 
defines the delimiter on which the codec will split events. It is not a required setting and if it is not explicitly 
set, its default value will be `,`.

The `configSchema` method must return a list of all settings that the plugin supports. The Logstash execution engine 
will validate that all required settings are present and that no unsupported settings are present.

#### Constructor and initialization
```java
private final String id;
private final String delimiter;
private final CharsetEncoder encoder;

public JavaCodecExample(final Configuration config, final Context context) {
    this(config.get(DELIMITER_CONFIG));
}

private JavaCodecExample(String delimiter) {
    this.id = UUID.randomUUID().toString();
    this.delimiter = delimiter;
    this.encoder = Charset.defaultCharset().newEncoder();
}
```
All Java codec plugins must have a constructor taking a `Configuration` and `Context` argument. This is the 
constructor that will be used to instantiate them at runtime. The retrieval and validation of all plugin settings 
should occur in this constructor. In this example, the delimiter to be used for delimiting events is retrieved from 
its setting and stored in a local variable so that it can be used later in the `decode` and `encode` methods. The
codec's ID is initialized to a random UUID (as should be done for most codecs), and a local `encoder` variable is
initialized to encode and decode with a specified character set.

Any additional initialization may occur in the constructor as well. If there are any unrecoverable errors encountered
in the configuration or initialization of the codec plugin, a descriptive exception should be thrown. The exception
will be logged and will prevent Logstash from starting.

#### Codec methods
```java
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

```
The `decode`, `flush`, and `encode` methods provide the core functionality of the codec. Codecs may be used by inputs
to decode a sequence or stream of bytes into events or by outputs to encode events into a sequence of bytes.

The `decode` method decodes events from the specified `ByteBuffer` and passes them to the provided `Consumer`. The 
input must provide a `ByteBuffer` that is ready for reading with `byteBuffer.position()` indicating the next
position to read and `byteBuffer.limit()` indicating the first byte in the buffer that is not safe to read. Codecs must
ensure that `byteBuffer.position()` reflects the last-read position before returning control to the input. The input
is then responsible for returning the buffer to write mode via either `byteBuffer.clear()` or `byteBuffer.compact()`
before resuming writes. In the example above, the `decode` method simply splits the incoming byte stream on the
specified delimiter. A production-grade codec such as [`java-line`](https://github.com/elastic/logstash/blob/6.7/logstash-core/src/main/java/org/logstash/plugins/codecs/Line.java)
would not make the simplifying assumption that the end of the supplied byte stream corresponded with the end of an 
event.

The `flush` method works in coordination with the `decode` method to decode all remaining events from the specified 
`ByteBuffer` along with any internal state that may remain after previous calls to the `decode` method. As an example
of internal state that a codec might maintain, consider an input stream of bytes `event1/event2/event3` with a 
delimiter of `/`. Due to buffering or other reasons, the input might supply a partial stream of bytes such as 
`event1/eve` to the codec's `decode` method. In this case, the codec could save the beginning three characters `eve` 
of the second event rather than assuming that the supplied byte stream ends on an event boundary. If the next call to
`decode` supplied the `nt2/ev` bytes, the codec would prepend the saved `eve` bytes to produce the full `event2` event
and then save the remaining `ev` bytes for decoding when the remainder of the bytes for that event were supplied.
A call to `flush` signals the codec that the supplied bytes represent the end of an event stream and all remaining
bytes should be decoded to events. The `flush` example above is a simplistic implementation that does not maintain
any state about partially-supplied byte streams across calls to `decode`.

The `encode` method encodes an event into a sequence of bytes and writes it into the specified `ByteBuffer`. Under 
ideal circumstances, the entirety of the event's encoding will fit into the supplied buffer. In cases where the buffer
has insufficient space to hold the event's encoding, the codec must fill the buffer with as much of the event's
encoding as possible, the `encode` must return `false`, and the output must call the `encode` method with the same
event and a buffer that has more `buffer.remaining()` bytes. The output typically does that by draining the partial
encoding from the supplied buffer. This process must be repeated until the event's entire encoding is written to the
buffer at which point the `encode` method will return `true`. Attempting to call this method with a new event before
the entirety of the previous event's encoding has been written to a buffer must result in an `EncodeException`. As
the coneptual inverse of the `decode` method, the `encode` method must return the buffer in a state from which it can
be read, typically by calling `buffer.flip()` before returning. In the example above, the `encode` method attempts to
write the event's encoding to the supplied buffer. If the buffer contains sufficient free space, the entirety of the
event is written and `true` is returned. Otherwise, the method writes as much of the event's encoding to the buffer as
possible, returns `false`, and stores the remainder to be written to the buffer in the next call to the `encode`
method.

#### cloneCodec method

```java
@Override
public Codec cloneCodec() {
    return new JavaCodecExample(this.delimiter);
}
```
The `cloneCodec` method should return an identical instance of the codec with the exception of its ID. Because codecs
may be stateful, a separate instance of each codec must be supplied to each worker thread in a pipeline. For all
pipelines with more than one worker, the `cloneCodec` method is called by the Logstash execution engine to create all 
codec instances beyond the first. In the example above, the codec is cloned with the same delimiter but a different ID. 

#### getId method

```java
@Override
public String getId() {
    return id;
}
```
For codec plugins, the `getId` method should always return the id that was set at instantiation time. This is typically
an UUID.

#### Unit tests
Lastly, but certainly not least importantly, unit tests are strongly encouraged. The example codec plugin includes
an [example unit test](https://github.com/logstash-plugins/logstash-codec-java_codec_example/blob/master/src/test/java/org/logstash/javaapi/JavaCodecExampleTest.java)
that you can use as a template for your own.

### Packaging and deployment

For the purposes of dependency management and interoperability with Ruby plugins, Java plugins will be packaged
as Ruby gems. One of the goals for Java plugin support is to eliminate the need for any knowledge of Ruby or its
toolchain for Java plugin development. Future phases of the Java plugin project will automate the packaging of
Java plugins as Ruby gems so no direct knowledge of or interaction with Ruby will be required. In the experimental
phase, Java plugins must still be manually packaged as Ruby gems and installed with the `logstash-plugin` utility.

#### Compile to JAR file

The Java plugin should be compiled and assembled into a fat jar with the `vendor` task in the Gradle build file. This
will package all Java dependencies into a single jar and write it to the correct folder for later packaging into
a Ruby gem.

#### Manual packaging as Ruby gem 

Several Ruby source files are required to correctly package the jar file as a Ruby gem. These Ruby files are used
only at Logstash startup time to identify the Java plugin and are not used during runtime event processing. In a 
future phase of the Java plugin support project, these Ruby source files will be automatically generated. 

`logstash-codec-<codec-name>.gemspec`
```
PLUGIN_VERSION = File.read(File.expand_path(File.join(File.dirname(__FILE__), "VERSION"))).strip unless defined?(PLUGIN_VERSION)

Gem::Specification.new do |s|
  s.name            = 'logstash-codec-java_codec_example'
  s.version         = PLUGIN_VERSION
  s.licenses        = ['Apache-2.0']
  s.summary         = "Example codec using Java plugin API"
  s.description     = ""
  s.authors         = ['Elasticsearch']
  s.email           = 'info@elastic.co'
  s.homepage        = "http://www.elastic.co/guide/en/logstash/current/index.html"
  s.require_paths = ['lib', 'vendor/jar-dependencies']

  # Files
  s.files = Dir["lib/**/*","spec/**/*","*.gemspec","*.md","CONTRIBUTORS","Gemfile","LICENSE","NOTICE.TXT", "vendor/jar-dependencies/**/*.jar", "vendor/jar-dependencies/**/*.rb", "VERSION", "docs/**/*"]

  # Special flag to let us know this is actually a logstash plugin
  s.metadata = { 'logstash_plugin' => 'true', 'logstash_group' => 'codec'}

  # Gem dependencies
  s.add_runtime_dependency "logstash-core-plugin-api", ">= 1.60", "<= 2.99"
  s.add_runtime_dependency 'jar-dependencies'

  s.add_development_dependency 'logstash-devutils'
end
```
The above file can be used unmodified except that `s.name` must follow the `logstash-codec-<codec-name>` pattern.
Also, `s.version` must match the `project.version` specified in the `build.gradle` file though those both should
be read from the `VERSION` file in this example.

`lib/logstash/codecs/<codec-name>.rb`
```
# encoding: utf-8
require "logstash/codecs/base"
require "logstash/namespace"
require "logstash-codec-java_codec_example_jars"
require "java"

class LogStash::Codecs::JavaCodecExample < LogStash::Codecs::Base
  config_name "java_codec_example"

  def self.javaClass() org.logstash.javaapi.JavaCodecExample.java_class; end
end
```
The following items should be modified in the file above:
1. It should be named to correspond with the codec name.
1. `require "logstash-codec-java_codec_example_jars"` should be changed to reference the appropriate "jars" file
as described below.
1. `class LogStash::Codecs::JavaCodecExample < LogStash::Codecs::Base` should be changed to provide a unique and
descriptive Ruby class name.
1. `config_name "java_codec_example"` must match the name of the plugin as specified in the `name` property of
the `@LogstashPlugin` annotation.
1. `def self.javaClass() org.logstash.javaapi.JavaCodecExample.java_class; end` must be modified to return the
class of the Java codec.

`lib/logstash-codec-<codec-name>_jars.rb`
```
require 'jar_dependencies'
require_jar('org.logstash.javaapi', 'logstash-codec-java_codec_example', '0.2.0')
```
The following items should be modified in the file above:
1. It should be named to correspond with the codec name.
1. The `require_jar` directive should be modified to correspond to the `group` specified in the Gradle build file,
the name of the codec JAR file, and the version as specified in both the gemspec and Gradle build file.

Once the above files have been properly created along with the plugin JAR file, the gem can be built with the
following command:
```
gem build logstash-codec-<codec-name>.gemspec
``` 

#### Installing the Java plugin in Logstash

Once your Java plugin has been packaged as a Ruby gem, it can be installed in Logstash with the following command:
```
bin/logstash-plugin install --no-verify --local /path/to/javaPlugin.gem
```
Substitute backslashes for forward slashes as appropriate in the command above for installation on Windows platforms. 

### Running Logstash with the Java codec plugin

To test the plugin, start Logstash with:

```
echo "foo,bar" | bin/logstash --java-execution -e 'input { java_stdin { codec => java_codec_example } } }'
```

Note that the `--java-execution` flag to enable the Java execution engine is required as Java plugins are not supported
in the Ruby execution engine.

The expected Logstash output (excluding initialization) with the configuration above is:

```
{
      "@version" => "1",
       "message" => "foo",
    "@timestamp" => yyyy-MM-ddThh:mm:ss.SSSZ,
          "host" => "<yourHostName>"
}
{
      "@version" => "1",
       "message" => "bar\n",
    "@timestamp" => yyyy-MM-ddThh:mm:ss.SSSZ,
          "host" => "<yourHostName>"
}
```

### Feedback

If you have any feedback on Java plugin support in Logstash, please comment on our 
[main Github issue](https://github.com/elastic/logstash/issues/9215) or post in the 
[Logstash forum](https://discuss.elastic.co/c/logstash).