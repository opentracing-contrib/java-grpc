/*
 * Copyright 2017-2020 The OpenTracing Authors
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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.grpc.BindableService;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.ForwardingServerCall;
import io.grpc.ForwardingServerCallListener;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.log.Fields;
import io.opentracing.propagation.Format;
import io.opentracing.propagation.TextMapAdapter;
import io.opentracing.tag.Tags;
import io.opentracing.util.GlobalTracer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * An interceptor that applies tracing via OpenTracing to all requests to the server.
 */
public class TracingServerInterceptor implements ServerInterceptor {

  private final Tracer tracer;
  private final OperationNameConstructor operationNameConstructor;
  private final boolean streaming;
  private final boolean verbose;
  private final Set<ServerRequestAttribute> tracedAttributes;
  private final ImmutableList<ServerSpanDecorator> serverSpanDecorators;
  private final ImmutableList<ServerCloseDecorator> serverCloseDecorators;
  private final Set<Metadata.Key<?>> excludedHeaders;
  private final Set<Metadata.Key<?>> includedHeaders;

  private TracingServerInterceptor(Builder builder) {
    this.tracer = builder.tracer;
    this.operationNameConstructor = builder.operationNameConstructor;
    this.streaming = builder.streaming;
    this.verbose = builder.verbose;
    this.tracedAttributes = builder.tracedAttributes;
    this.serverSpanDecorators = ImmutableList.copyOf(builder.serverSpanDecorators.values());
    this.serverCloseDecorators = ImmutableList.copyOf(builder.serverCloseDecorators.values());
    if(builder.tracedAttributes.contains(ServerRequestAttribute.HEADERS) && builder.excludedHeaders.size() > 0 && builder.includedHeaders.size() > 0) {
      throw new IllegalArgumentException("Only one of excludedHeaders or includedHeaders can be defined at the same time.");
    }
    this.excludedHeaders = builder.excludedHeaders;
    this.includedHeaders = builder.includedHeaders;
  }

  /**
   * Creates a new {@link TracingServerInterceptor.Builder}.
   *
   * @return the tracing server interceptor builder
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Add tracing to all requests made to this service.
   *
   * @param serviceDef of the service to intercept
   * @return the serviceDef with a tracing interceptor
   */
  public ServerServiceDefinition intercept(ServerServiceDefinition serviceDef) {
    return ServerInterceptors.intercept(serviceDef, this);
  }

  /**
   * Add tracing to all requests made to this service.
   *
   * @param bindableService to intercept
   * @return the serviceDef with a tracing interceptor
   */
  public ServerServiceDefinition intercept(BindableService bindableService) {
    return ServerInterceptors.intercept(bindableService, this);
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

    final Set<String> headerKeys = headers.keys();
    Map<String, String> headerMap = new HashMap<>(headerKeys.size());
    for (String key : headerKeys) {
      if (!key.endsWith(Metadata.BINARY_HEADER_SUFFIX)) {
        String value = headers.get(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
        headerMap.put(key, value);
      }
    }

    final Span span =
        getSpanFromHeaders(
            headerMap, operationNameConstructor.constructOperationName(call.getMethodDescriptor()));

    try (Scope ignored = tracer.scopeManager().activate(span)) {

      for (ServerSpanDecorator serverSpanDecorator : serverSpanDecorators) {
        serverSpanDecorator.interceptCall(span, call, headers);
      }

      for (ServerRequestAttribute attr : this.tracedAttributes) {
        switch (attr) {
          case METHOD_TYPE:
            GrpcTags.GRPC_METHOD_TYPE.set(span, call.getMethodDescriptor());
            break;
          case METHOD_NAME:
            GrpcTags.GRPC_METHOD_NAME.set(span, call.getMethodDescriptor());
            break;
          case CALL_ATTRIBUTES:
            GrpcTags.GRPC_CALL_ATTRIBUTES.set(span, call.getAttributes());
            break;
          case HEADERS:
            GrpcTags.GRPC_HEADERS.set(span, massageHeaders(headers));
            break;
          case PEER_ADDRESS:
            GrpcTags.PEER_ADDRESS.set(span, call.getAttributes());
            break;
        }
      }

      Context ctxWithSpan =
          Context.current()
              .withValue(OpenTracingContextKey.getKey(), span)
              .withValue(OpenTracingContextKey.getSpanContextKey(), span.context());

      final ServerCall<ReqT, RespT> decoratedCall =
          new ForwardingServerCall.SimpleForwardingServerCall<ReqT, RespT>(call) {

            @Override
            public void sendHeaders(Metadata headers) {
              if (verbose) {
                span.log(
                    ImmutableMap.<String, Object>builder()
                        .put(Fields.EVENT, GrpcFields.SERVER_CALL_SEND_HEADERS)
                        .put(Fields.MESSAGE, "Server sent response headers")
                        .put(GrpcFields.HEADERS, headers.toString())
                        .build());
              }
              super.sendHeaders(headers);
            }

            @Override
            public void sendMessage(RespT message) {
              if (streaming || verbose) {
                span.log(
                    ImmutableMap.<String, Object>builder()
                        .put(Fields.EVENT, GrpcFields.SERVER_CALL_SEND_MESSAGE)
                        .put(Fields.MESSAGE, "Server sent response message")
                        .build());
              }

              try (Scope ignored = tracer.scopeManager().activate(span)) {
                super.sendMessage(message);
              }
            }

            @Override
            public void close(Status status, Metadata trailers) {
              if (verbose) {
                span.log(
                    ImmutableMap.<String, Object>builder()
                        .put(Fields.EVENT, GrpcFields.SERVER_CALL_CLOSE)
                        .put(Fields.MESSAGE, "Server call closed")
                        .build());
                if (!status.isOk()) {
                  GrpcFields.logServerCallError(span, status.getDescription(), status.getCause());
                }
              }
              GrpcTags.GRPC_STATUS.set(span, status);
              for (ServerCloseDecorator serverCloseDecorator : serverCloseDecorators) {
                serverCloseDecorator.close(span, status, trailers);
              }
              super.close(status, trailers);
            }
          };

      ServerCall.Listener<ReqT> listenerWithContext =
          Contexts.interceptCall(ctxWithSpan, decoratedCall, headers, next);

      return new ForwardingServerCallListener.SimpleForwardingServerCallListener<ReqT>(
          listenerWithContext) {

        @Override
        public void onReady() {
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            super.onReady();
          }
        }

        @Override
        public void onMessage(ReqT message) {
          if (streaming || verbose) {
            span.log(
                ImmutableMap.<String, Object>builder()
                    .put(Fields.EVENT, GrpcFields.SERVER_CALL_LISTENER_ON_MESSAGE)
                    .put(Fields.MESSAGE, "Server received message")
                    .build());
          }
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            super.onMessage(message);
          }
        }

        @Override
        public void onHalfClose() {
          if (streaming || verbose) {
            span.log(
                ImmutableMap.<String, Object>builder()
                    .put(Fields.EVENT, GrpcFields.SERVER_CALL_LISTENER_ON_HALF_CLOSE)
                    .put(Fields.MESSAGE, "Server received all messages")
                    .build());
          }
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            super.onHalfClose();
          }
        }

        @Override
        public void onCancel() {
          if (verbose) {
            span.log(
                ImmutableMap.<String, Object>builder()
                    .put(Fields.EVENT, GrpcFields.SERVER_CALL_LISTENER_ON_CANCEL)
                    .put(Fields.MESSAGE, "Server call cancelled")
                    .build());
          }
          GrpcTags.GRPC_STATUS.set(span, Status.CANCELLED);
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            super.onCancel();
          } finally {
            span.finish();
          }
        }

        @Override
        public void onComplete() {
          if (verbose) {
            span.log(
                ImmutableMap.<String, Object>builder()
                    .put(Fields.EVENT, GrpcFields.SERVER_CALL_LISTENER_ON_COMPLETE)
                    .put(Fields.MESSAGE, "Server call completed")
                    .build());
          }
          // Server span may complete with non-OK ServerCall.close(status).
          try (Scope ignored = tracer.scopeManager().activate(span)) {
            super.onComplete();
          } finally {
            span.finish();
          }
        }
      };
    }
  }

  @VisibleForTesting
  Span getSpanFromHeaders(Map<String, String> headers, String operationName) {
    Map<String, Object> fields = null;
    Tracer.SpanBuilder spanBuilder = tracer.buildSpan(operationName);
    try {
      SpanContext parentSpanCtx =
          tracer.extract(Format.Builtin.HTTP_HEADERS, new TextMapAdapter(headers));
      if (parentSpanCtx != null) {
        spanBuilder = spanBuilder.asChildOf(parentSpanCtx);
      }
    } catch (IllegalArgumentException iae) {
      spanBuilder = spanBuilder.withTag(Tags.ERROR, Boolean.TRUE);
      fields =
          ImmutableMap.<String, Object>builder()
              .put(Fields.EVENT, GrpcFields.ERROR)
              .put(
                  Fields.ERROR_OBJECT,
                  new RuntimeException("Parent span context extract failed", iae))
              .build();
    }
    Span span =
        spanBuilder
            .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_SERVER)
            .withTag(Tags.COMPONENT.getKey(), GrpcTags.COMPONENT_NAME)
            .start();
    if (fields != null) {
      span.log(fields);
    }
    return span;
  }
  
  private Metadata massageHeaders(Metadata headers) {
    if(this.excludedHeaders.size() > 0) {
      Metadata massagedHeaders = new Metadata();
      massagedHeaders.merge(headers);
      for(Metadata.Key<?> excludedHeader : this.excludedHeaders) {
        massagedHeaders.removeAll(excludedHeader);
      }
      return massagedHeaders;
    } else if(this.includedHeaders.size() > 0) {
      Metadata massagedHeaders = new Metadata();
      massagedHeaders.merge(headers, this.includedHeaders);
      return massagedHeaders;
    }
    return headers;
  }

  /**
   * Builds the configuration of a TracingServerInterceptor.
   */
  public static class Builder {

    private Tracer tracer;
    private OperationNameConstructor operationNameConstructor;
    private boolean streaming;
    private boolean verbose;
    private Set<ServerRequestAttribute> tracedAttributes;
    private Map<Class<?>, ServerSpanDecorator> serverSpanDecorators;
    private Map<Class<?>, ServerCloseDecorator> serverCloseDecorators;
    private Set<Metadata.Key<?>> excludedHeaders;
    private Set<Metadata.Key<?>> includedHeaders;

    /**
     * Creates a Builder with GlobalTracer if present else NoopTracer.
     */
    private Builder() {
      this.tracer = GlobalTracer.get();
      this.operationNameConstructor = OperationNameConstructor.DEFAULT;
      this.streaming = false;
      this.verbose = false;
      this.tracedAttributes = new HashSet<>();
      this.serverSpanDecorators = new HashMap<>();
      this.serverCloseDecorators = new HashMap<>();
      this.excludedHeaders = new HashSet<>();
      this.includedHeaders = new HashSet<>();
    }

    /**
     * Provide the {@link Tracer}.
     *
     * @param tracer the tracer
     * @return this Builder with configured tracer
     */
    public Builder withTracer(Tracer tracer) {
      this.tracer = tracer;
      return this;
    }

    /**
     * Provide operation name.
     *
     * @param operationNameConstructor for all spans created by this interceptor
     * @return this Builder with configured operation name
     */
    public Builder withOperationName(OperationNameConstructor operationNameConstructor) {
      this.operationNameConstructor = operationNameConstructor;
      return this;
    }

    /**
     * Provide traced attributes.
     *
     * @param attributes to set as tags on server spans created by this interceptor
     * @return this Builder configured to trace request attributes
     */
    public Builder withTracedAttributes(ServerRequestAttribute... attributes) {
      this.tracedAttributes = new HashSet<>(Arrays.asList(attributes));
      return this;
    }
    
    /**
     * Provide keys for headers that should be excluded from the trace.  Only applicable
     * when {@link #withTracedAttributes(ServerRequestAttribute...)} contains {@link ServerRequestAttribute#HEADERS}.
     * 
     * Mutually exclusive with {@link #withIncludedHeaders(io.grpc.Metadata.Key...)}.
     * 
     * @param headerKeys
     * @return this Builder configured to exclude the header keys
     */
    public Builder withExcludedHeaders(Metadata.Key<?>... headerKeys) {
      this.excludedHeaders = new HashSet<>(Arrays.asList(headerKeys));
      return this;
    }
    
    /**
     * Provide keys for headers that should be included in the trace.  Headers with any other
     * key will be excluded.  Only applicable when {@link #withTracedAttributes(ServerRequestAttribute...)}
     * contains {@link ServerRequestAttribute#HEADERS}.
     * 
     * Mutually exclusive with {@link #withExcludedHeaders(io.grpc.Metadata.Key...)}.
     * 
     * @param headerKeys
     * @return this Builder configured to exclude the header keys
     */
    public Builder withIncludedHeaders(Metadata.Key<?>... headerKeys) {
      this.includedHeaders = new HashSet<>(Arrays.asList(headerKeys));
      return this;
    }

    /**
     * Logs streaming events to server spans.
     *
     * @return this Builder configured to log streaming events
     */
    public Builder withStreaming() {
      this.streaming = true;
      return this;
    }

    /**
     * Logs all request life-cycle events to server spans.
     *
     * @return this Builder configured to be verbose
     */
    public Builder withVerbosity() {
      this.verbose = true;
      return this;
    }

    /**
     * Decorates the server span with custom data.
     *
     * @param serverSpanDecorator used to decorate the server span
     * @return this Builder configured to decorate server span
     */
    public Builder withServerSpanDecorator(ServerSpanDecorator serverSpanDecorator) {
      this.serverSpanDecorators.put(serverSpanDecorator.getClass(), serverSpanDecorator);
      return this;
    }

    /**
     * Decorates the server span with custom data when the gRPC call is closed.
     *
     * @param serverCloseDecorator used to decorate the server span
     * @return this Builder configured to decorate server span when the gRPC call is closed
     */
    public Builder withServerCloseDecorator(ServerCloseDecorator serverCloseDecorator) {
      this.serverCloseDecorators.put(serverCloseDecorator.getClass(), serverCloseDecorator);
      return this;
    }

    /**
     * Build the TracingServerInterceptor.
     *
     * @return a TracingServerInterceptor with this Builder's configuration
     */
    public TracingServerInterceptor build() {
      return new TracingServerInterceptor(this);
    }
  }

  public enum ServerRequestAttribute {
    HEADERS,
    METHOD_TYPE,
    METHOD_NAME,
    CALL_ATTRIBUTES,
    PEER_ADDRESS
  }
}
