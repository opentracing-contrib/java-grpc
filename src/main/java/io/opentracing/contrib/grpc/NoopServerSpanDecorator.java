package io.opentracing.contrib.grpc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.opentracing.Span;

public class NoopServerSpanDecorator implements ServerSpanDecorator {
  @Override
  public void interceptCall(Span span, ServerCall call, Metadata headers) {}
}
