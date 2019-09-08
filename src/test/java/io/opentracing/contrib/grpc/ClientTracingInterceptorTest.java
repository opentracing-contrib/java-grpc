/*
 * Copyright 2017-2019 The OpenTracing Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.opentracing.contrib.grpc;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.grpc.CallOptions;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.testing.GrpcServerRule;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.log.Fields;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracerTestUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.Assertions;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ClientTracingInterceptorTest {

  private static final String PREFIX = "testing-";

  private static final Map<String, Object> BASE_TAGS = ImmutableMap.<String, Object>builder()
      .put(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME)
      .put(GrpcTags.GRPC_STATUS.getKey(), Status.Code.OK.name())
      .build();

  private static final Map<String, Object> BASE_CLIENT_TAGS = ImmutableMap.<String, Object>builder()
      .putAll(BASE_TAGS)
      .put(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
      .build();

  private static final Set<String> CLIENT_ATTRIBUTE_TAGS = ImmutableSet.of(
      GrpcTags.GRPC_CALL_OPTIONS.getKey(),
      //TODO: @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1767")
      //GrpcTags.GRPC_AUTHORITY.getKey(),
      GrpcTags.GRPC_COMPRESSOR.getKey(),
      GrpcTags.GRPC_DEADLINE.getKey(),
      GrpcTags.GRPC_METHOD_NAME.getKey(),
      GrpcTags.GRPC_METHOD_TYPE.getKey(),
      GrpcTags.GRPC_HEADERS.getKey());

  private final MockTracer clientTracer = new MockTracer();

  @Rule
  public GrpcServerRule grpcServer = new GrpcServerRule();

  @Before
  public void before() {
    GlobalTracerTestUtil.resetGlobalTracer();
    clientTracer.reset();
    TracedService.addGeeterService(grpcServer.getServiceRegistry());
  }

  @Test
  public void testTracedClientBasic() {
    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor(clientTracer);
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world",
        client.greet("world").getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    Assertions.assertThat(span.tags()).as("span should have base client tags")
        .isEqualTo(BASE_CLIENT_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientTwoInterceptors() {
    SecondClientInterceptor secondInterceptor = new SecondClientInterceptor(clientTracer);
    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor(clientTracer);
    TracedClient client = new TracedClient(grpcServer.getChannel(), secondInterceptor,
        tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world",
        client.greet("world").getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    Assertions.assertThat(span.tags()).as("span should have base client tags")
        .isEqualTo(BASE_CLIENT_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientWithVerbosity() {
    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withVerbosity()
        .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world",
        client.greet("world").getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    System.out.println(span.logEntries());
    List<String> events = new ArrayList<>(span.logEntries().size());
    for (MockSpan.LogEntry logEntry : span.logEntries()) {
      events.add((String) logEntry.fields().get(Fields.EVENT));
    }
    Assertions.assertThat(events)
        .as("span should contain verbose log fields")
        .contains(
            GrpcFields.CLIENT_CALL_START,
            GrpcFields.CLIENT_CALL_SEND_MESSAGE,
            GrpcFields.CLIENT_CALL_HALF_CLOSE,
            GrpcFields.CLIENT_CALL_LISTENER_ON_HEADERS,
            GrpcFields.CLIENT_CALL_LISTENER_ON_MESSAGE,
            GrpcFields.CLIENT_CALL_LISTENER_ON_CLOSE);
    Assertions.assertThat(span.tags()).as("span should have base client tags")
        .isEqualTo(BASE_CLIENT_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientWithStreaming() {
    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withStreaming()
        .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world",
        client.greet("world").getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    List<String> events = new ArrayList<>(span.logEntries().size());
    for (MockSpan.LogEntry logEntry : span.logEntries()) {
      events.add((String) logEntry.fields().get(Fields.EVENT));
    }
    Assertions.assertThat(events)
        .as("span should contain streaming log fields")
        .contains(
            GrpcFields.CLIENT_CALL_SEND_MESSAGE,
            GrpcFields.CLIENT_CALL_HALF_CLOSE,
            GrpcFields.CLIENT_CALL_LISTENER_ON_MESSAGE);
    Assertions.assertThat(span.tags()).as("span should have base client tags")
        .isEqualTo(BASE_CLIENT_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientWithOperationName() {
    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withOperationName(new OperationNameConstructor() {
          @Override
          public <ReqT, RespT> String constructOperationName(MethodDescriptor<ReqT, RespT> method) {
            return PREFIX + method.getFullMethodName();
          }
        })
        .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world",
        client.greet("world").getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(),
        PREFIX + "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    Assertions.assertThat(span.tags()).as("span should have base client tags")
        .isEqualTo(BASE_CLIENT_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientWithTracedAttributes() {
    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withTracedAttributes(ClientTracingInterceptor.ClientRequestAttribute.values())
        .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), 50, "gzip", tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world",
        client.greet("world").getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    Assertions.assertThat(span.tags()).as("span should have base client tags")
        .containsAllEntriesOf(BASE_CLIENT_TAGS);
    Assertions.assertThat(span.tags().keySet())
        .as("span should have tags for all client request attributes")
        .containsAll(CLIENT_ATTRIBUTE_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientwithActiveSpanSource() {
    final MockSpan parentSpan = clientTracer.buildSpan("parent").start();
    ActiveSpanSource activeSpanSource = new ActiveSpanSource() {
      @Override
      public Span getActiveSpan() {
        return parentSpan;
      }
    };
    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withActiveSpanSource(activeSpanSource)
        .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world",
        client.greet("world").getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have parent", span.parentId(), parentSpan.context().spanId());
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    Assertions.assertThat(span.tags()).as("span should have base client tags")
        .isEqualTo(BASE_CLIENT_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientwithActiveSpanContextSource() {
    final MockSpan parentSpan = clientTracer.buildSpan("parent").start();
    ActiveSpanContextSource activeSpanContextSource = new ActiveSpanContextSource() {
      @Override
      public SpanContext getActiveSpanContext() {
        return parentSpan.context();
      }
    };
    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withActiveSpanContextSource(activeSpanContextSource)
        .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world",
        client.greet("world").getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have parent", span.parentId(), parentSpan.context().spanId());
    assertEquals("span should have no logs", span.logEntries().size(), 0);
    Assertions.assertThat(span.tags()).as("span should have base client tags")
        .isEqualTo(BASE_CLIENT_TAGS);
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientWithClientSpanDecorator() {
    ClientSpanDecorator spanTagger = new ClientSpanDecorator() {
      @Override
      public void interceptCall(Span span, MethodDescriptor method, CallOptions callOptions) {
        span.setTag("test_tag", "test_value");
        span.setTag("tag_from_method", method.getFullMethodName());
        span.setTag("tag_from_call_options", callOptions.getCompressor());
      }
    };
    ClientSpanDecorator spanLogger = new ClientSpanDecorator() {
      @Override
      public void interceptCall(Span span, MethodDescriptor method, CallOptions callOptions) {
        span.log("A span log");
      }
    };

    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withClientSpanDecorator(spanTagger)
        .withClientSpanDecorator(spanLogger)
        .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world",
        client.greet("world").getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);

    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have prefix", span.operationName(), "helloworld.Greeter/SayHello");
    assertEquals("span should have no parents", span.parentId(), 0);
    assertEquals("span should have one log from the decorator",
        span.logEntries().get(0).fields().get("event"), "A span log");
    Assertions.assertThat(span.tags()).as("span should have 3 tags from the decorator")
        .hasSize(3 + BASE_CLIENT_TAGS.size());
    Assertions.assertThat(span.tags()).as("span contains added tags")
        .containsEntry("test_tag", "test_value")
        .containsKeys("tag_from_method", "tag_from_call_options");
    assertFalse("span should have no baggage",
        span.context().baggageItems().iterator().hasNext());
  }

  @Test
  public void testTracedClientWithClientCloseDecorator() {
    ClientCloseDecorator closeTagger = new ClientCloseDecorator() {
      @Override
      public void close(Span span, Status status, Metadata trailers) {
        span.setTag("some_tag", "some_value");
      }
    };
    ClientCloseDecorator closeLogger = new ClientCloseDecorator() {
      @Override
      public void close(Span span, Status status, Metadata trailers) {
        span.log("A close log");
      }
    };

    ClientTracingInterceptor tracingInterceptor = new ClientTracingInterceptor
        .Builder(clientTracer)
        .withClientCloseDecorator(closeTagger)
        .withClientCloseDecorator(closeLogger)
        .build();
    TracedClient client = new TracedClient(grpcServer.getChannel(), tracingInterceptor);

    assertEquals("call should complete successfully", "Hello world",
        client.greet("world").getMessage());
    await().atMost(5, TimeUnit.SECONDS).until(reportedSpansSize(clientTracer), equalTo(1));
    assertEquals("one span should have been created and finished for one client request",
        clientTracer.finishedSpans().size(), 1);
    MockSpan span = clientTracer.finishedSpans().get(0);
    assertEquals("span should have one log from the decorator",
        span.logEntries().get(0).fields().get("event"), "A close log");
    Assertions.assertThat(span.tags())
        .containsEntry("some_tag", "some_value");
  }

  private Callable<Integer> reportedSpansSize(final MockTracer mockTracer) {
    return new Callable<Integer>() {
      @Override
      public Integer call() {
        return mockTracer.finishedSpans().size();
      }
    };
  }
}
