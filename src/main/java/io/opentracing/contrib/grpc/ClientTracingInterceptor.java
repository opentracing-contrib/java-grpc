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

import com.google.common.collect.ImmutableMap;
import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.log.Fields;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMap;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * An interceptor that applies tracing via OpenTracing to all client requests.
 */
public class ClientTracingInterceptor implements ClientInterceptor {

  private final Tracer tracer;
  private final OperationNameConstructor operationNameConstructor;
  private final boolean streaming;
  private final boolean verbose;
  private final Set<ClientRequestAttribute> tracedAttributes;
  private final ActiveSpanSource activeSpanSource;
  private final ActiveSpanContextSource activeSpanContextSource;
  private final ClientSpanDecorator clientSpanDecorator;
  private final ClientCloseDecorator clientCloseDecorator;

  /**
   * Instantiate interceptor using GlobalTracer to get tracer
   */
  public ClientTracingInterceptor() {
    this(GlobalTracer.get());
  }

  /**
   * @param tracer to use to trace requests
   */
  public ClientTracingInterceptor(Tracer tracer) {
    this.tracer = tracer;
    this.operationNameConstructor = OperationNameConstructor.DEFAULT;
    this.streaming = false;
    this.verbose = false;
    this.tracedAttributes = new HashSet<>();
    this.activeSpanSource = ActiveSpanSource.GRPC_CONTEXT;
    this.activeSpanContextSource = null;
    this.clientSpanDecorator = null;
    this.clientCloseDecorator = null;
  }

  private ClientTracingInterceptor(Tracer tracer, OperationNameConstructor operationNameConstructor,
      boolean streaming,
      boolean verbose, Set<ClientRequestAttribute> tracedAttributes,
      ActiveSpanSource activeSpanSource, ActiveSpanContextSource activeSpanContextSource,
      ClientSpanDecorator clientSpanDecorator,
      ClientCloseDecorator clientCloseDecorator) {
    this.tracer = tracer;
    this.operationNameConstructor = operationNameConstructor;
    this.streaming = streaming;
    this.verbose = verbose;
    this.tracedAttributes = tracedAttributes;
    this.activeSpanSource = activeSpanSource;
    this.activeSpanContextSource = activeSpanContextSource;
    this.clientSpanDecorator = clientSpanDecorator;
    this.clientCloseDecorator = clientCloseDecorator;
  }

  /**
   * Use this interceptor to trace all requests made by this client channel.
   *
   * @param channel to be traced
   * @return intercepted channel
   */
  public Channel intercept(Channel channel) {
    return ClientInterceptors.intercept(channel, this);
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method,
      CallOptions callOptions,
      Channel next) {

    final String operationName = operationNameConstructor.constructOperationName(method);

    SpanContext activeSpanContext = getActiveSpanContext();
    final Span span = createSpanFromParent(activeSpanContext, operationName);

    try (Scope ignored = tracer.scopeManager().activate(span)) {

      if (clientSpanDecorator != null) {
        clientSpanDecorator.interceptCall(span, method, callOptions);
      }

      for (ClientRequestAttribute attr : this.tracedAttributes) {
        switch (attr) {
          case ALL_CALL_OPTIONS:
            GrpcTags.GRPC_CALL_OPTIONS.set(span, callOptions);
            break;
          case AUTHORITY:
            GrpcTags.GRPC_AUTHORITY.set(span, callOptions.getAuthority());
            break;
          case COMPRESSOR:
            GrpcTags.GRPC_COMPRESSOR.set(span, callOptions.getCompressor());
            break;
          case DEADLINE:
            GrpcTags.GRPC_DEADLINE.set(span, callOptions.getDeadline());
            break;
          case METHOD_NAME:
            GrpcTags.GRPC_METHOD_NAME.set(span, method);
            break;
          case METHOD_TYPE:
            GrpcTags.GRPC_METHOD_TYPE.set(span, method);
            break;
          case HEADERS:
            break;
        }
      }

      return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
          next.newCall(method, callOptions)) {

        volatile boolean finished = false;

        @Override
        public void start(Listener<RespT> responseListener, final Metadata headers) {
          if (verbose) {
            span.log(ImmutableMap.<String, Object>builder()
                .put(Fields.EVENT, GrpcFields.CLIENT_CALL_START)
                .put(Fields.MESSAGE, "Client call started")
                .build());
          }
          if (tracedAttributes.contains(ClientRequestAttribute.HEADERS)) {
            GrpcTags.GRPC_HEADERS.set(span, headers);
          }

          tracer.inject(span.context(), Format.Builtin.HTTP_HEADERS, new TextMap() {
            @Override
            public void put(String key, String value) {
              Metadata.Key<String> headerKey = Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER);
              headers.put(headerKey, value);
            }

            @Override
            public Iterator<Entry<String, String>> iterator() {
              throw new UnsupportedOperationException(
                  "TextMapInjectAdapter should only be used with Tracer.inject()");
            }
          });

          Listener<RespT> tracingResponseListener = new ForwardingClientCallListener
              .SimpleForwardingClientCallListener<RespT>(responseListener) {

            @Override
            public void onHeaders(Metadata headers) {
              if (verbose) {
                span.log(ImmutableMap.<String, Object>builder()
                    .put(Fields.EVENT, GrpcFields.CLIENT_CALL_LISTENER_ON_HEADERS)
                    .put(Fields.MESSAGE, "Client received response headers")
                    .put(GrpcFields.HEADERS, headers.toString())
                    .build());
              }
              delegate().onHeaders(headers);
            }

            @Override
            public void onMessage(RespT message) {
              if (streaming || verbose) {
                span.log(ImmutableMap.<String, Object>builder()
                    .put(Fields.EVENT, GrpcFields.CLIENT_CALL_LISTENER_ON_MESSAGE)
                    .put(Fields.MESSAGE, "Client received response message")
                    .build());
              }
              delegate().onMessage(message);
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
              if (finished) {
                delegate().onClose(status, trailers);
                return;
              }

              if (verbose) {
                span.log(ImmutableMap.<String, Object>builder()
                    .put(Fields.EVENT, GrpcFields.CLIENT_CALL_LISTENER_ON_CLOSE)
                    .put(Fields.MESSAGE, "Client call closed")
                    .build());
                if (!status.isOk()) {
                  GrpcFields.logClientCallError(span, status.getDescription(), status.getCause());
                }
              }
              GrpcTags.GRPC_STATUS.set(span, status);
              if (clientCloseDecorator != null) {
                clientCloseDecorator.close(span, status, trailers);
              }
              delegate().onClose(status, trailers);
              span.finish();
              finished = true;
            }
          };

          try (Scope ignored = tracer.scopeManager().activate(span)) {
            delegate().start(tracingResponseListener, headers);
          }
        }

        @Override
        public void request(int numMessages) {
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            delegate().request(numMessages);
          }
        }

        @Override
        public void sendMessage(ReqT message) {
          if (streaming || verbose) {
            span.log(ImmutableMap.<String, Object>builder()
                .put(Fields.EVENT, GrpcFields.CLIENT_CALL_SEND_MESSAGE)
                .put(Fields.MESSAGE, "Client sent message")
                .build());
          }
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            delegate().sendMessage(message);
          }
        }

        @Override
        public void halfClose() {
          if (streaming || verbose) {
            span.log(ImmutableMap.<String, Object>builder()
                .put(Fields.EVENT, GrpcFields.CLIENT_CALL_HALF_CLOSE)
                .put(Fields.MESSAGE, "Client sent all messages")
                .build());
          }
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            delegate().halfClose();
          }
        }

        @Override
        public void cancel(@Nullable String message, @Nullable Throwable cause) {
          if (finished) {
            delegate().cancel(message, cause);
            return;
          }

          if (verbose) {
            span.log(ImmutableMap.<String, Object>builder()
                .put(Fields.EVENT, GrpcFields.CLIENT_CALL_CANCEL)
                .put(Fields.MESSAGE, "Client call canceled")
                .build());
            GrpcFields.logClientCallError(span, message, cause);
          }
          Status status = cause == null ? Status.UNKNOWN : Status.fromThrowable(cause);
          GrpcTags.GRPC_STATUS.set(span, status.withDescription(message));
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            delegate().cancel(message, cause);
          } finally {
            span.finish();
            finished = true;
          }
        }

        @Override
        public boolean isReady() {
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            return delegate().isReady();
          }
        }

        @Override
        public void setMessageCompression(boolean enabled) {
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            delegate().setMessageCompression(enabled);
          }
        }

        @Override
        public Attributes getAttributes() {
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            return delegate().getAttributes();
          }
        }
      };
    }
  }

  private SpanContext getActiveSpanContext() {
    if (activeSpanSource != null) {
      Span activeSpan = activeSpanSource.getActiveSpan();
      if (activeSpan != null) {
        return activeSpan.context();
      }
    }
    if (activeSpanContextSource != null) {
      final SpanContext spanContext = activeSpanContextSource.getActiveSpanContext();
      if (spanContext != null) {
        return spanContext;
      }
    }
    if (tracer.activeSpan() != null) {
      return tracer.activeSpan().context();
    }
    return null;
  }

  private Span createSpanFromParent(SpanContext parentSpanContext, String operationName) {
    final Tracer.SpanBuilder spanBuilder;
    if (parentSpanContext == null) {
      spanBuilder = tracer.buildSpan(operationName);
    } else {
      spanBuilder = tracer.buildSpan(operationName).asChildOf(parentSpanContext);
    }
    return spanBuilder
        .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
        .withTag(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME)
        .start();
  }

  /**
   * Builds the configuration of a ClientTracingInterceptor.
   */
  public static class Builder {

    private final Tracer tracer;
    private OperationNameConstructor operationNameConstructor;
    private boolean streaming;
    private boolean verbose;
    private Set<ClientRequestAttribute> tracedAttributes;
    private ActiveSpanSource activeSpanSource;
    private ActiveSpanContextSource activeSpanContextSource;
    private ClientSpanDecorator clientSpanDecorator;
    private ClientCloseDecorator clientCloseDecorator;

    /**
     * Creates a Builder using GlobalTracer to get tracer
     */
    public Builder() {
      this(GlobalTracer.get());
    }

    /**
     * @param tracer to use for this interceptor Creates a Builder with default configuration
     */
    public Builder(Tracer tracer) {
      this.tracer = tracer;
      this.operationNameConstructor = OperationNameConstructor.DEFAULT;
      this.streaming = false;
      this.verbose = false;
      this.tracedAttributes = new HashSet<>();
      this.activeSpanSource = ActiveSpanSource.GRPC_CONTEXT;
      this.clientSpanDecorator = null;
    }

    /**
     * @param operationNameConstructor to name all spans created by this interceptor
     * @return this Builder with configured operation name
     */
    public Builder withOperationName(OperationNameConstructor operationNameConstructor) {
      this.operationNameConstructor = operationNameConstructor;
      return this;
    }

    /**
     * Logs streaming events to client spans.
     *
     * @return this Builder configured to log streaming events
     */
    public Builder withStreaming() {
      this.streaming = true;
      return this;
    }

    /**
     * @param tracedAttributes to set as tags on client spans created by this interceptor
     * @return this Builder configured to trace attributes
     */
    public Builder withTracedAttributes(ClientRequestAttribute... tracedAttributes) {
      this.tracedAttributes = new HashSet<>(Arrays.asList(tracedAttributes));
      return this;
    }

    /**
     * Logs all request life-cycle events to client spans.
     *
     * @return this Builder configured to be verbose
     */
    public Builder withVerbosity() {
      this.verbose = true;
      return this;
    }

    /**
     * @param activeSpanSource that provides a method of getting the active span before the client
     * call
     * @return this Builder configured to start client span as children of the span returned by
     * activeSpanSource.getActiveSpan()
     */
    public Builder withActiveSpanSource(ActiveSpanSource activeSpanSource) {
      this.activeSpanSource = activeSpanSource;
      return this;
    }

    /**
     * @param activeSpanContextSource that provides a method of getting the active span context
     * before the client call
     * @return this Builder configured to start client span as children of the span context returned
     * by activeSpanContextSource.getActiveSpanContext()
     */
    public Builder withActiveSpanContextSource(ActiveSpanContextSource activeSpanContextSource) {
      this.activeSpanContextSource = activeSpanContextSource;
      return this;
    }

    /**
     * Decorates the client span with custom data
     *
     * @param clientSpanDecorator used to decorate the client span
     * @return this builder configured to decorate the client span
     */
    public Builder withClientSpanDecorator(ClientSpanDecorator clientSpanDecorator) {
      this.clientSpanDecorator = clientSpanDecorator;
      return this;
    }

    /**
     * Decorates the client span with custom data when the call is closed
     *
     * @param clientCloseDecorator used to decorate the client span when the call is closed
     * @return this builder configured to decorate the client span when the call is closed
     */
    public Builder withClientCloseDecorator(ClientCloseDecorator clientCloseDecorator) {
      this.clientCloseDecorator = clientCloseDecorator;
      return this;
    }

    /**
     * @return a ClientTracingInterceptor with this Builder's configuration
     */
    public ClientTracingInterceptor build() {
      return new ClientTracingInterceptor(this.tracer, this.operationNameConstructor,
          this.streaming, this.verbose, this.tracedAttributes, this.activeSpanSource,
          this.activeSpanContextSource, this.clientSpanDecorator,
          this.clientCloseDecorator);
    }
  }

  public enum ClientRequestAttribute {
    METHOD_TYPE,
    METHOD_NAME,
    DEADLINE,
    COMPRESSOR,
    AUTHORITY,
    ALL_CALL_OPTIONS,
    HEADERS
  }
}
