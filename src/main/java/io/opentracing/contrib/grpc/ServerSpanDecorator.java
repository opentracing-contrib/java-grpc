package io.opentracing.contrib.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.opentracing.Span;

/**
 * An interface for adding custom span tags to the spans created by
 * {@link ServerTracingInterceptor}
 */
public interface ServerSpanDecorator {

  /**
   * The method of the implementation is executed inside
   * {@link ServerTracingInterceptor#interceptCall(ServerCall, Metadata, ServerCallHandler)}
   * @param span The span created by {@link ServerTracingInterceptor}
   * @param call The {@link ServerCall} parameter of
   * {@link ServerTracingInterceptor#interceptCall(ServerCall, Metadata, ServerCallHandler)}
   * @param headers The {@link Metadata} parameter of
   * {@link ServerTracingInterceptor#interceptCall(ServerCall, Metadata, ServerCallHandler)}
   */
  void interceptCall(Span span, ServerCall call, Metadata headers);
}
