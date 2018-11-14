/*
 * Copyright 2015-2018 The OpenZipkin Authors
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
package zipkin2.internal;

import java.util.List;
import org.junit.Test;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.Span.Kind;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

public class TraceTest {

  /**
   * Some don't propagate the server's parent ID which creates a race condition. Try to unwind it.
   *
   * <p>See https://github.com/openzipkin/zipkin/pull/1745
   */
  @Test public void backfillsMissingParentIdOnSharedSpan() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.SERVER, "frontend", null, false),
      span("a", "a", "b", Kind.CLIENT, "frontend", null, false),
      // below the parent ID is null as it wasn't propagated
      span("a", null, "b", Kind.SERVER, "backend", null, true)
    );

    assertThat(Trace.merge(trace)).usingFieldByFieldElementComparator().containsExactlyInAnyOrder(
      span("a", null, "a", Kind.SERVER, "frontend", null, false),
      span("a", "a", "b", Kind.CLIENT, "frontend", null, false),
      span("a", "a", "b", Kind.SERVER, "backend", null, true)
    );
  }

  /** Some truncate an incoming trace ID to 64-bits. */
  @Test public void choosesBestTraceId() {
    List<Span> trace = asList(
      span("7180c278b62e8f6a216a2aea45d08fc9", null, "a", Kind.SERVER, "frontend", null, false),
      span("7180c278b62e8f6a216a2aea45d08fc9", "a", "b", Kind.CLIENT, "frontend", null, false),
      span("216a2aea45d08fc9", "a", "b", Kind.SERVER, "backend", null, true)
    );

    assertThat(Trace.merge(trace)).flatExtracting(Span::traceId).containsExactly(
      "7180c278b62e8f6a216a2aea45d08fc9",
      "7180c278b62e8f6a216a2aea45d08fc9",
      "7180c278b62e8f6a216a2aea45d08fc9"
    );
  }

  /** Let's pretend people use crappy data, but only on the first hop. */
  @Test public void mergesWhenMissingEndpoints() {
    List<Span> trace = asList(
      Span.newBuilder()
        .traceId("a")
        .id("a")
        .putTag("service", "frontend")
        .putTag("span.kind", "SERVER")
        .build(),
      Span.newBuilder()
        .traceId("a")
        .parentId("a")
        .id("b")
        .putTag("service", "frontend")
        .putTag("span.kind", "CLIENT")
        .timestamp(1L)
        .build(),
      span("a", "a", "b", Kind.SERVER, "backend", null, true),
      Span.newBuilder().traceId("a").parentId("a").id("b").duration(10L).build()
    );

    assertThat(Trace.merge(trace)).usingFieldByFieldElementComparator().containsExactlyInAnyOrder(
      Span.newBuilder()
        .traceId("a")
        .id("a")
        .putTag("service", "frontend")
        .putTag("span.kind", "SERVER")
        .build(),
      Span.newBuilder()
        .traceId("a")
        .parentId("a")
        .id("b")
        .putTag("service", "frontend")
        .putTag("span.kind", "CLIENT")
        .timestamp(1L)
        .duration(10L)
        .build(),
      span("a", "a", "b", Kind.SERVER, "backend", null, true)
    );
  }

  /**
   * If a client request is proxied by something that does transparent retried. It can be the case
   * that two servers share the same ID (accidentally!)
   */
  @Test public void doesntMergeSharedSpansOnDifferentIPs() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.SERVER, "frontend", null, false),
      span("a", "a", "b", Kind.CLIENT, "frontend", null, false).toBuilder()
        .timestamp(1L).addAnnotation(3L, "brave.flush").build(),
      span("a", "a", "b", Kind.SERVER, "backend", "1.2.3.4", true),
      span("a", "a", "b", Kind.SERVER, "backend", "1.2.3.5", true),
      span("a", "a", "b", Kind.CLIENT, "frontend", null, false).toBuilder()
        .duration(10L).build()
    );

    assertThat(Trace.merge(trace)).usingFieldByFieldElementComparator().containsExactlyInAnyOrder(
      span("a", null, "a", Kind.SERVER, "frontend", null, false),
      span("a", "a", "b", Kind.CLIENT, "frontend", null, false).toBuilder()
        .timestamp(1L).duration(10L).addAnnotation(3L, "brave.flush").build(),
      span("a", "a", "b", Kind.SERVER, "backend", "1.2.3.4", true),
      span("a", "a", "b", Kind.SERVER, "backend", "1.2.3.5", true)
    );
  }

  @Test public void putsRandomDataOnFirstSpanWithEndpoint() {
    List<Span> trace = asList(
      span("a", null, "a", Kind.SERVER, "frontend", null, false),
      span("a", "a", "b", Kind.CLIENT, null, null, false),
      span("a", "a", "b", null, "frontend", null, false).toBuilder()
        .timestamp(1L).addAnnotation(3L, "brave.flush").build(),
      span("a", "a", "b", Kind.SERVER, "backend", "1.2.3.4", true),
      span("a", "a", "b", Kind.SERVER, "backend", "1.2.3.5", true),
      span("a", "a", "b", null, "frontend", null, false).toBuilder()
        .duration(10L).build()
    );

    assertThat(Trace.merge(trace)).usingFieldByFieldElementComparator().containsExactlyInAnyOrder(
      span("a", null, "a", Kind.SERVER, "frontend", null, false),
      span("a", "a", "b", Kind.CLIENT, "frontend", null, false).toBuilder()
        .timestamp(1L).duration(10L).addAnnotation(3L, "brave.flush").build(),
      span("a", "a", "b", Kind.SERVER, "backend", "1.2.3.4", true),
      span("a", "a", "b", Kind.SERVER, "backend", "1.2.3.5", true)
    );
  }

  @Test public void deletesSelfReferencingParentId() {
    List<Span> trace = asList(
      span("a", "a", "a", Kind.SERVER, "frontend", null, false),
      span("a", "a", "b", Kind.CLIENT, "frontend", null, false)
    );

    assertThat(Trace.merge(trace)).usingFieldByFieldElementComparator().containsExactlyInAnyOrder(
      span("a", null, "a", Kind.SERVER, "frontend", null, false),
      span("a", "a", "b", Kind.CLIENT, "frontend", null, false)
    );
  }

  @Test public void worksWhenMissingParentSpan() {
    String missingParentId = "a";
    List<Span> trace = asList(
      span("a", missingParentId, "b", Kind.SERVER, "backend", "1.2.3.4", false),
      span("a", missingParentId, "c", Kind.SERVER, "backend", null, false)
    );

    assertThat(Trace.merge(trace)).isSameAs(trace);
  }

  static Span span(String traceId, @Nullable String parentId, String id, @Nullable Kind kind,
    @Nullable String local, @Nullable String ip, boolean shared) {
    Span.Builder result = Span.newBuilder().traceId(traceId).parentId(parentId).id(id).kind(kind);
    if (local != null) {
      result.localEndpoint(Endpoint.newBuilder().serviceName(local).ip(ip).build());
    }
    return result.shared(shared).build();
  }
}