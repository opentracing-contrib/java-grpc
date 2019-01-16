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
import io.grpc.Grpc;
import io.grpc.Status;
import io.grpc.inprocess.InProcessSocketAddress;
import io.opentracing.Span;
import io.opentracing.tag.StringTag;
import io.opentracing.tag.Tags;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Package private utility methods for common gRPC tags.
 */
final class GrpcTags {
  private GrpcTags() {
  }

  /**
   * gRPC status code tag
   */
  static StringTag GRPC_STATUS = new StringTag("grpc.status");

  /**
   * peer.address tag
   */
  static StringTag PEER_ADDRESS = new StringTag("peer.address");

  /**
   * Value for {@link Tags#COMPONENT} for gRPC
   */
  static String COMPONENT_NAME = "java-grpc";

  /**
   * Sets {@code grpc.status} and {@code error} tags on span.
   *
   * @param span Span
   * @param status gRPC call status
   */
  static void setStatusTags(Span span, Status status) {
    GRPC_STATUS.set(span, status.getCode().name());
  }

  /**
   * Sets the {@code peer.address} tag on the Span from the given server or client call attributes.
   *
   * @param span span on which to set tag
   * @param attributes attributes from server or client call
   */
  static void setPeerAddressTag(Span span, Attributes attributes) {
    SocketAddress address = attributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR);
    if (address instanceof InProcessSocketAddress) {
      PEER_ADDRESS.set(span, ((InProcessSocketAddress) address).getName());
    } else if (address instanceof InetSocketAddress) {
      final InetSocketAddress inetAddress = (InetSocketAddress) address;
      PEER_ADDRESS.set(span, inetAddress.getHostString() + ':' + inetAddress.getPort());
    }
  }
}
