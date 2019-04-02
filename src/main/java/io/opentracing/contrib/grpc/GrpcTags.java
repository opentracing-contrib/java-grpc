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

import io.grpc.Attributes;
import io.grpc.CallOptions;
import io.grpc.Deadline;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.inprocess.InProcessSocketAddress;
import io.opentracing.Span;
import io.opentracing.tag.AbstractTag;
import io.opentracing.tag.Tag;
import io.opentracing.tag.Tags;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

/**
 * Package private utility methods for common gRPC tags.
 */
final class GrpcTags {

  /**
   * grpc.authority tag.
   */
  static final NullableTag<String> GRPC_AUTHORITY = new NullableTag<>("grpc.authority");

  /**
   * grpc.call_attributes tag.
   */
  static final NullableTag<Attributes> GRPC_CALL_ATTRIBUTES = new NullableTag<>("grpc.call_attributes");

  /**
   * grpc.call_options tag.
   */
  static final NullableTag<CallOptions> GRPC_CALL_OPTIONS = new NullableTag<>("grpc.call_options");

  /**
   * grpc.compressor tag.
   */
  static final NullableTag<String> GRPC_COMPRESSOR = new NullableTag<>("grpc.compressor");

  /**
   * grpc.deadline_millis tag.
   */
  static final Tag<Deadline> GRPC_DEADLINE = new AbstractTag<Deadline>("grpc.deadline_millis") {
    @Override
    public void set(Span span, Deadline deadline) {
      if (deadline != null) {
        span.setTag(super.key, String.valueOf(deadline.timeRemaining(TimeUnit.MILLISECONDS)));
      }
    }
  };

  /**
   * grpc.headers tag.
   */
  static final NullableTag<Metadata> GRPC_HEADERS = new NullableTag<>("grpc.headers");

  /**
   * grpc.method_name tag.
   */
  static final Tag<MethodDescriptor> GRPC_METHOD_NAME = new AbstractTag<MethodDescriptor>("grpc.method_name") {
    @Override
    public void set(Span span, MethodDescriptor method) {
      if (method != null) {
        span.setTag(super.key, method.getFullMethodName());
      }
    }
  };

  /**
   * grpc.method_type tag.
   */
  static final Tag<MethodDescriptor> GRPC_METHOD_TYPE = new AbstractTag<MethodDescriptor>("grpc.method_type") {
    @Override
    public void set(Span span, MethodDescriptor method) {
      if (method != null) {
        span.setTag(super.key, method.getType().toString());
      }
    }
  };

  /**
   * grpc.status tag.
   */
  static final Tag<Status> GRPC_STATUS = new AbstractTag<Status>("grpc.status") {
    @Override
    public void set(Span span, Status status) {
      if (status != null) {
        span.setTag(super.key, status.getCode().name());
      }
    }
  };

  /**
   * peer.address tag.
   */
  static final Tag<Attributes> PEER_ADDRESS = new AbstractTag<Attributes>("peer.address") {
    @Override
    public void set(Span span, Attributes attributes) {
      SocketAddress address = attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
      if (address instanceof InProcessSocketAddress) {
        span.setTag(super.key, ((InProcessSocketAddress) address).getName());
      } else if (address instanceof InetSocketAddress) {
        final InetSocketAddress inetAddress = (InetSocketAddress) address;
        span.setTag(super.key, inetAddress.getHostString() + ':' + inetAddress.getPort());
      }
    }
  };

  /**
   * Value for {@link Tags#COMPONENT} for gRPC.
   */
  static final String COMPONENT_NAME = "java-grpc";

  static class NullableTag<T> extends AbstractTag<T> {

    NullableTag(String tagKey) {
      super(tagKey);
    }

    @Override
    public void set(Span span, T tagValue) {
      if (tagValue != null) {
        span.setTag(super.key, tagValue.toString());
      }
    }
  }
}
