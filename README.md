[![Build Status][ci-img]][ci] [![Coverage Status][cov-img]][cov] [![Released Version][maven-img]][maven] [![Apache-2.0 license](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

# OpenTracing gRPC Instrumentation
OpenTracing instrumentation for gRPC.

## Installation

pom.xml
```xml
<dependency>
    <groupId>io.opentracing.contrib</groupId>
    <artifactId>opentracing-grpc</artifactId>
    <version>VERSION</version>
</dependency>
```

## Usage

### Server

- Instantiate tracer
- Optionally register tracer with GlobalTracer: `GlobalTracer.register(tracer)`
- Create a `TracingServerInterceptor`
- Intercept a service

```java
import io.opentracing.Tracer;

    public class YourServer {

        private int port;
        private Server server;
        private final Tracer tracer;

        private void start() throws IOException {
            TracingServerInterceptor tracingInterceptor = TracingServerInterceptor
                .newBuilder()
                .withTracer(this.tracer)
                .build();
            
            // If GlobalTracer is used: 
            TracingServerInterceptor

            server = ServerBuilder.forPort(port)
                .addService(tracingInterceptor.intercept(someServiceDef))
                .build()
                .start();
        }
    }
```

### Client

- Instantiate a tracer
- Optionally register tracer with GlobalTracer: `GlobalTracer.register(tracer)`
- Create a `TracingClientInterceptor`
- Intercept the client channel

```java
import io.opentracing.Tracer;import io.opentracing.contrib.grpc.TracingClientInterceptor;

    public class YourClient {

        private final ManagedChannel channel;
        private final GreeterGrpc.GreeterBlockingStub blockingStub;
        private final Tracer tracer;

        public YourClient(String host, int port) {

            channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext(true)
                .build();

            TracingClientInterceptor tracingInterceptor = TracingClientInterceptor
                .newBuilder()
                .withTracer(this.tracer)
                .build();
            
            // If GlobalTracer is used: 
            TracingClientInterceptor

            blockingStub = GreeterGrpc.newBlockingStub(tracingInterceptor.intercept(channel));
        }
    }
```


## Server Tracing

A `TracingServerInterceptor` uses default settings, which you can override by creating it using a `TracingServerInterceptor.Builder`.

- `withOperationName(OperationNameConstructor constructor)`: Define how the operation name is constructed for all spans created for the intercepted service. Default sets the operation name as the name of the RPC method. More details in the `Operation Name` section.
- `withStreaming()`: Logs to the server span whenever a message is received. *Note:* This package supports streaming but has not been rigorously tested. If you come across any issues, please let us know.
- `withVerbosity()`: Logs to the server span additional events, such as message received, half close (client finished sending messages), and call complete. Default only logs if a call is cancelled.
- `withTracedAttributes(ServerRequestAttribute... attrs)`: Sets tags on the server span in case you want to track information about the RPC call. See ServerRequestAttribute.java for a list of traceable request attributes.

### Example

```java
    TracingServerInterceptor tracingInterceptor = TracingServerInterceptor
        .newBuilder()
        .withTracer(tracer)
        .withStreaming()
        .withVerbosity()
        .withOperationName(new OperationNameConstructor() {
            @Override
            public <ReqT, RespT> String constructOperationName(MethodDescriptor<ReqT, RespT> method) {
                // construct some operation name from the method descriptor
            }
        })
        .withTracedAttributes(ServerRequestAttribute.HEADERS,
            ServerRequestAttribute.METHOD_TYPE)
        .build();
```

## Client Tracing

A `TracingClientInterceptor` also has default settings, which you can override by creating it using a `TracingClientInterceptor.Builder`.

- `withOperationName(String operationName)`: Define how the operation name is constructed for all spans created for this intercepted client. Default is the name of the RPC method. More details in the `Operation Name` section.
- `withActiveSpanSource(ActiveSpanSource activeSpanSource)`: Define how to extract the current active span, if any. More details in the `Active Span Sources` section.
- `withActiveSpanContextSource(ActiveSpanContextSource activeSpanContextSource)`: Define how to extract the current active span context, if any. More details in the `Active Span Context Sources` section.
- `withStreaming()`: Logs to the client span whenever a message is sent or a response is received. *Note:* This package supports streaming but has not been rigorously tested. If you come across any issues, please let us know.
- `withVerbosity()`: Logs to the client span additional events, such as call started, message sent, half close (client finished sending messages), response received, and call complete. Default only logs if a call is cancelled.
- `withTracedAttributes(ClientRequestAttribute... attrs)`: Sets tags on the client span in case you want to track information about the RPC call. See ClientRequestAttribute.java for a list of traceable request attributes.

### Example
```java
import io.opentracing.Span;

    TracingClientInterceptor tracingInterceptor = TracingClientInterceptor
        .newBuilder()
        .withTracer(tracer)
        .withStreaming()
        .withVerbosity()
        .withOperationName(new OperationNameConstructor() {
            @Override
            public <ReqT, RespT> String constructOperationName(MethodDescriptor<ReqT, RespT> method) {
                // construct some operation name from the method descriptor
            }
        })
        .withActiveSpanSource(new ActiveSpanSource() {
            @Override
            public Span getActiveSpan() {
                // implement how to get the current active span, for example:
                return OpenTracingContextKey.activeSpan();
            }
        })
        .withTracingAttributes(ClientRequestAttribute.ALL_CALL_OPTIONS,
            ClientRequestAttribute.HEADERS)
        .build();
```

## Current Span Context

In your server request handler, you can access the current active span for that request by calling

```java
Span span = OpenTracingContextKey.activeSpan();
```

This is useful if you want to manually set tags on the span, log important events, or create a new child span for internal units of work. You can also use this key to wrap these internal units of work with a new context that has a user-defined active span.

For example:
```java
Tracer tracer = ...;

    // some unit of internal work that you want to trace
    Runnable internalWork = someInternalWork

    // a wrapper that traces the work of the runnable
    class TracedRunnable implements Runnable {
        Runnable work;
        Tracer tracer;

        TracedRunnable(Runnable work, Tracer tracer) {
            this.work = work;
            this.tracer = tracer;
        }

        public void run() {

            // create a child span for the current active span
            Span span = tracer
                .buildSpan("internal-work")
                .asChildOf(OpenTracingContextKey.activeSpan())
                .start();

            // create a new context with the child span as the active span
            Context contextWithNewSpan = Context.current()
                .withValue(OpenTracingContextKey.get(), span);

            // wrap the original work and run it
            Runnable tracedWork = contextWithNewSpan.wrap(this.work);
            tracedWork.run();

            // make sure to finish any manually created spans!
            span.finish();
        }
    }

    Runnable tracedInternalWork = new TracedRunnable(internalWork, tracer);
    tracedInternalWork.run();
```

## Operation Names

The default operation name for any span is the RPC method name (`io.grpc.MethodDescriptor.getFullMethodName()`). However, you may want to add your own prefixes, alter the name, or define a new name. For examples of good operation names, check out the OpenTracing `semantics`.

To alter the operation name, you need to add an implementation of the interface `OperationNameConstructor` to the `TracingClientInterceptor.Builder` or `TracingServerInterceptor.Builder`. For example, if you want to add a prefix to the default operation name of your ClientInterceptor, your code would look like this:

```java
 TracingClientInterceptor interceptor = TracingClientInterceptor.Builder ...
        .withOperationName(new OperationNameConstructor() {
            @Override
            public <ReqT, RespT> String constructOperationName(MethodDescriptor<ReqT, RespT> method) {
                return "your-prefix" + method.getFullMethodName();
            }
        })
        .with....
        .build()
```

## Active Span Sources

If you want your client to continue a trace rather than starting a new one, then you can tell your `TracingClientInterceptor` how to extract the current active span by building it with your own implementation of the interface `ActiveSpanSource`. This interface has one method, `getActiveSpan`, in which you will define how to access the current active span.

For example, if you're creating the client in an environment that has the active span stored in a global dictionary-style context under `OPENTRACING_SPAN_KEY`, then you could configure your Interceptor as follows:

```java
import io.opentracing.Span;

    TracingClientInterceptor interceptor = TracingClientInterceptor
        .newBuilder().withTracer(tracer)
        ...
        .withActiveSpanSource(new ActiveSpanSource() {
            @Override
            public Span getActiveSpan() {
                return Context.get(OPENTRACING_SPAN_KEY);
            }
        })
        ...
        .build();
```

We also provide two built-in implementations:

- `ActiveSpanSource.GRPC_CONTEXT` uses the current `io.grpc.Context` and returns the active span for `OpenTracingContextKey`. 
- `ActiveSpanSource.NONE` always returns null as the active span, which means the client will retrieve the span from `io.opentracing.Tracer.activeSpan()`. This is the default active span source.

## Active Span Context Sources

Instead of `ActiveSpanSource` it's possible to use `ActiveSpanContextSource` if span is not available

```java
import io.opentracing.Span;

    TracingClientInterceptor interceptor = TracingClientInterceptor
        .newBuilder().withTracer(tracer)
        ...
        .withActiveSpanContextSource(new ActiveSpanContextSource() {
            @Override
            public SpanContext getActiveSpanContext() {
                return Context.get(OPENTRACING_SPAN_CONTEXT_KEY);
            }
        })
        ...
        .build();
```

We also provide two built-in implementations:

- `ActiveSpanContextSource.GRPC_CONTEXT` uses the current `io.grpc.Context` and returns the active span context for `OpenTracingContextKey`.
- `ActiveSpanContextSource.NONE` always returns null as the active span context, which means the client will retrieve the span from `io.opentracing.Tracer.activeSpan().context()`. This is the default active span context source.

## Custom Span Decorators

If you want to add custom tags or logs to the server and client spans, then you can implement the 
`ClientSpanDecorator`, `ClientCloseDecorator`, `ServerSpanDecorator`, and `ServerCloseDecorator` interfaces.
Multiple different decorators may be added to the builder.

```java
TracingClientInterceptor clientInterceptor = TracingClientInterceptor
    .newBuilder().withTracer(tracer)
    ...
    .withClientSpanDecorator(new ClientSpanDecorator() {
        @Override
        public void interceptCall(Span span, MethodDescriptor method, CallOptions callOptions) {
            span.setTag("some_tag", "some_value");
            span.log("Example log");
        }
    })
    .withClientCloseDecorator(new ClientCloseDecorator() {
        @Override
        public void close(Span span, Status status, Metadata trailers) {
            span.setTag("some_other_tag", "some_other_value");
        }
    })
    ...
    .build();
    
TracingServerInterceptor serverInterceptor = TracingServerInterceptor
    .newBuilder().withTracer(tracer)
    ...
    .withServerSpanDecorator(new ServerSpanDecorator() {
        @Override
        public void interceptCall(Span span, ServerCall call, Metadata headers) {
            span.setTag("some_tag", "some_value");
            span.log("Intercepting server call");
        }
    })
    .withServerCloseDecorator(new ServerCloseDecorator() {
        @Override
        public void close(Span span, Status status, Metadata trailers) {
            span.setTag("some_other_tag", "some_other_value");
        }
    })
    ...
    .build();
```

## Integrating with Other Interceptors
Although we provide `TracingServerInterceptor.intercept(service)` and `TracingClientInterceptor.intercept(channel)` methods, you don't want to use these if you're chaining multiple interceptors. Instead, use the following code (preferably putting the tracing interceptor at the top of the interceptor stack so that it traces the entire request lifecycle, including other interceptors):

### Server
```java
server = ServerBuilder.forPort(port)
        .addService(ServerInterceptors.intercept(service, someInterceptor,
            someOtherInterceptor, TracingServerInterceptor))
        .build()
        .start();
```

### Client
```java
blockingStub = GreeterGrpc.newBlockingStub(ClientInterceptors.intercept(channel,
        someInterceptor, someOtherInterceptor, TracingClientInterceptor));
```

## License

[Apache 2.0 License](./LICENSE).

[ci-img]: https://travis-ci.org/opentracing-contrib/java-grpc.svg?branch=master
[ci]: https://travis-ci.org/opentracing-contrib/java-grpc
[cov-img]: https://coveralls.io/repos/github/opentracing-contrib/java-grpc/badge.svg?branch=master
[cov]: https://coveralls.io/github/opentracing-contrib/java-grpc?branch=master
[maven-img]: https://img.shields.io/maven-central/v/io.opentracing.contrib/opentracing-grpc.svg
[maven]: http://search.maven.org/#search%7Cga%7C1%7Copentracing-grpc
