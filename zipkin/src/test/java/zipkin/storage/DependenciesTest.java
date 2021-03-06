/**
 * Copyright 2015-2016 The OpenZipkin Authors
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
package zipkin.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import zipkin.Annotation;
import zipkin.BinaryAnnotation;
import zipkin.Constants;
import zipkin.DependencyLink;
import zipkin.Endpoint;
import zipkin.Span;
import zipkin.internal.ApplyTimestampAndDuration;
import zipkin.internal.CallbackCaptor;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;
import static org.assertj.core.api.Assertions.assertThat;
import static zipkin.Constants.CLIENT_ADDR;
import static zipkin.Constants.CLIENT_RECV;
import static zipkin.Constants.CLIENT_SEND;
import static zipkin.Constants.SERVER_ADDR;
import static zipkin.Constants.SERVER_RECV;
import static zipkin.Constants.SERVER_SEND;
import static zipkin.TestObjects.APP_ENDPOINT;
import static zipkin.TestObjects.DAY;
import static zipkin.TestObjects.DB_ENDPOINT;
import static zipkin.TestObjects.DEPENDENCIES;
import static zipkin.TestObjects.LINKS;
import static zipkin.TestObjects.TODAY;
import static zipkin.TestObjects.TRACE;
import static zipkin.TestObjects.WEB_ENDPOINT;

/**
 * Base test for {@link SpanStore} implementations that support dependency aggregation. Subtypes
 * should create a connection to a real backend, even if that backend is in-process.
 *
 * <p>This is a replacement for {@code com.twitter.zipkin.storage.DependencyStoreSpec}.
 */
public abstract class DependenciesTest {

  /** Should maintain state between multiple calls within a test. */
  protected abstract StorageComponent storage();

  SpanStore store() {
    return storage().spanStore();
  }

  /** Clears store between tests. */
  @Before
  public abstract void clear() throws IOException;

  /**
   * Override if dependency processing is a separate job: it should complete before returning from
   * this method.
   */
  protected void processDependencies(List<Span> spans) {
    // Blocks until the callback completes to allow read-your-writes consistency during tests.
    CallbackCaptor<Void> captor = new CallbackCaptor<>();
    storage().asyncSpanConsumer().accept(spans, captor);
    captor.get(); // block on result
  }

  /**
   * Normally, the root-span is where trace id == span id and parent id == null. The default is to
   * look back one day from today.
   */
  @Test
  public void getDependencies() {
    processDependencies(TRACE);

    assertThat(store().getDependencies(TODAY + 1000L, null))
        .containsOnlyElementsOf(LINKS);
  }

  /**
   * This tests that dependency linking ignores the high-bits of the trace ID when grouping spans
   * for dependency links. This allows environments with 64-bit instrumentation to participate in
   * the same trace as 128-bit instrumentation.
   */
  @Test
  public void getDependencies_mixedTraceIdLength() {
    List<Span> mixedTrace = new ArrayList<>(TRACE);
    mixedTrace.set(1, TRACE.get(1).toBuilder().traceIdHigh(2).build());
    processDependencies(mixedTrace);

    assertThat(store().getDependencies(TODAY + 1000L, null))
        .containsOnlyElementsOf(LINKS);
  }

  /** It should be safe to run dependency link jobs twice */
  @Test
  public void replayOverwrites() {
    processDependencies(TRACE);
    processDependencies(TRACE);

    assertThat(store().getDependencies(TODAY + 1000L, null))
        .containsOnlyElementsOf(LINKS);
  }

  /** Edge-case when there are no spans, or instrumentation isn't logging annotations properly. */
  @Test
  public void empty() {
    assertThat(store().getDependencies(TODAY + 1000L, null))
        .isEmpty();
  }

  /**
   * Trace id is not required to be a span id. For example, some instrumentation may create separate
   * trace ids to help with collisions, or to encode information about the origin. This test makes
   * sure we don't rely on the trace id = root span id convention.
   */
  @Test
  public void traceIdIsOpaque() {
    List<Span> differentTraceId = TRACE.stream()
        .map(s -> s.toBuilder().traceId(Long.MAX_VALUE).build())
        .collect(toList());
    processDependencies(differentTraceId);

    assertThat(store().getDependencies(TODAY + 1000L, null))
        .containsOnlyElementsOf(LINKS);
  }

  /**
   * When all servers are instrumented, they all log a {@link Constants#SERVER_RECV ("sr")}
   * annotation, indicating the service.
   */
  @Test
  public void getDependenciesAllInstrumented() {
    Endpoint one = Endpoint.create("trace-producer-one", 127 << 24 | 1);
    Endpoint onePort3001 = one.toBuilder().port((short) 3001).build();
    Endpoint two = Endpoint.create("trace-producer-two", 127 << 24 | 2);
    Endpoint twoPort3002 = two.toBuilder().port((short) 3002).build();
    Endpoint three = Endpoint.create("trace-producer-three", 127 << 24 | 3);

    List<Span> trace = asList(
        Span.builder().traceId(10L).id(10L).name("get")
            .timestamp(1445136539256150L).duration(1152579L)
            .addAnnotation(Annotation.create(1445136539256150L, SERVER_RECV, one))
            .addAnnotation(Annotation.create(1445136540408729L, SERVER_SEND, one))
            .build(),
        Span.builder().traceId(10L).parentId(10L).id(20L).name("get")
            .timestamp(1445136539764798L).duration(639337L)
            .addAnnotation(Annotation.create(1445136539764798L, CLIENT_SEND, onePort3001))
            .addAnnotation(Annotation.create(1445136539816432L, SERVER_RECV, two))
            .addAnnotation(Annotation.create(1445136540401414L, SERVER_SEND, two))
            .addAnnotation(Annotation.create(1445136540404135L, CLIENT_RECV, onePort3001))
            .build(),
        Span.builder().traceId(10L).parentId(20L).id(30L).name("get")
            .timestamp(1445136540025751L).duration(371298L)
            .addAnnotation(Annotation.create(1445136540025751L, CLIENT_SEND, twoPort3002))
            .addAnnotation(Annotation.create(1445136540072846L, SERVER_RECV, three))
            .addAnnotation(Annotation.create(1445136540394644L, SERVER_SEND, three))
            .addAnnotation(Annotation.create(1445136540397049L, CLIENT_RECV, twoPort3002))
            .build()
    );
    processDependencies(trace);

    long traceDuration = trace.get(0).duration;

    assertThat(
        store().getDependencies((trace.get(0).timestamp + traceDuration) / 1000, traceDuration / 1000)
    ).containsOnly(
        DependencyLink.create("trace-producer-one", "trace-producer-two", 1),
        DependencyLink.create("trace-producer-two", "trace-producer-three", 1)
    );
  }

  /**
   * Legacy instrumentation don't set Span.timestamp or duration. Make sure dependencies still work.
   */
  @Test
  public void getDependencies_noTimestamps() {
    Endpoint one = Endpoint.create("trace-producer-one", 127 << 24 | 1);
    Endpoint onePort3001 = one.toBuilder().port((short) 3001).build();
    Endpoint two = Endpoint.create("trace-producer-two", 127 << 24 | 2);
    Endpoint twoPort3002 = two.toBuilder().port((short) 3002).build();
    Endpoint three = Endpoint.create("trace-producer-three", 127 << 24 | 3);

    List<Span> trace = asList(
        Span.builder().traceId(10L).id(10L).name("get")
            .addAnnotation(Annotation.create(1445136539256150L, SERVER_RECV, one))
            .addAnnotation(Annotation.create(1445136540408729L, SERVER_SEND, one))
            .build(),
        Span.builder().traceId(10L).parentId(10L).id(20L).name("get")
            .addAnnotation(Annotation.create(1445136539764798L, CLIENT_SEND, onePort3001))
            .addAnnotation(Annotation.create(1445136539816432L, SERVER_RECV, two))
            .addAnnotation(Annotation.create(1445136540401414L, SERVER_SEND, two))
            .addAnnotation(Annotation.create(1445136540404135L, CLIENT_RECV, onePort3001))
            .build(),
        Span.builder().traceId(10L).parentId(20L).id(30L).name("get")
            .addAnnotation(Annotation.create(1445136540025751L, CLIENT_SEND, twoPort3002))
            .addAnnotation(Annotation.create(1445136540072846L, SERVER_RECV, three))
            .addAnnotation(Annotation.create(1445136540394644L, SERVER_SEND, three))
            .addAnnotation(Annotation.create(1445136540397049L, CLIENT_RECV, twoPort3002))
            .build()
    );
    processDependencies(trace);

    long traceDuration = ApplyTimestampAndDuration.apply(trace.get(0)).duration;

    assertThat(
        store().getDependencies((trace.get(0).annotations.get(0).timestamp + traceDuration) / 1000, traceDuration / 1000)
    ).containsOnly(
        DependencyLink.create("trace-producer-one", "trace-producer-two", 1),
        DependencyLink.create("trace-producer-two", "trace-producer-three", 1)
    );
  }

  /**
   * The primary annotation used in the dependency graph is [[Constants.SERVER_RECV]]
   */
  @Test
  public void getDependenciesMultiLevel() {
    processDependencies(TRACE);

    assertThat(store().getDependencies(TODAY + 1000L, null))
        .containsOnlyElementsOf(LINKS);
  }

  @Test
  public void dependencies_loopback() {
    List<Span> traceWithLoopback = asList(
        TRACE.get(0),
        TRACE.get(1).toBuilder()
            .annotations(TRACE.get(1).annotations.stream()
                .map(a -> Annotation.create(a.timestamp, a.value, WEB_ENDPOINT)).collect(toList()))
            .binaryAnnotations(asList())
            .build());

    processDependencies(traceWithLoopback);

    assertThat(store().getDependencies(TODAY + 1000L, null))
        .containsOnly(DependencyLink.create("web", "web", 1));
  }

  /**
   * Some systems log a different trace id than the root span. This seems "headless", as we won't
   * see a span whose id is the same as the trace id.
   */
  @Test
  public void dependencies_headlessTrace() {
    processDependencies(asList(TRACE.get(1), TRACE.get(2)));

    assertThat(store().getDependencies(TODAY + 1000L, null))
        .containsOnlyElementsOf(LINKS);
  }

  @Test
  public void looksBackIndefinitely() {
    processDependencies(TRACE);

    assertThat(store().getDependencies(TODAY + 1000L, null))
        .containsOnlyElementsOf(LINKS);
  }

  @Test
  public void insideTheInterval() {
    processDependencies(TRACE);

    assertThat(store().getDependencies(DEPENDENCIES.endTs, DEPENDENCIES.endTs - DEPENDENCIES.startTs))
        .containsOnlyElementsOf(LINKS);
  }

  @Test
  public void endTimeBeforeData() {
    processDependencies(TRACE);

    assertThat(store().getDependencies(TODAY - DAY, null))
        .isEmpty();
  }

  @Test
  public void lookbackAfterData() {
    processDependencies(TRACE);

    assertThat(store().getDependencies(TODAY + 2 * DAY, DAY))
        .isEmpty();
  }

  /**
   * This test confirms that the span store can detect dependency indicated by SERVER_ADDR and
   * CLIENT_ADDR. In some cases an RPC call is made where one of the two services is not
   * instrumented. However, if the other service is able to emit "sa" or "ca" annotation with a
   * service name, the link can still be constructed.
   *
   * span1: CA SR SS: Dependency 1 by a not-instrumented client span2: intermediate call span3: CS
   * CR SA: Dependency 2 to a not-instrumented server
   */
  @Test
  public void notInstrumentedClientAndServer() {
    Endpoint someClient = Endpoint.create("some-client", 172 << 24 | 17 << 16 | 4);

    List<Span> trace = asList(
        Span.builder().traceId(20L).id(20L).name("get")
            .timestamp(TODAY * 1000).duration(350L * 1000)
            .addAnnotation(Annotation.create(TODAY * 1000, SERVER_RECV, WEB_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 350) * 1000, SERVER_SEND, WEB_ENDPOINT))
            .addBinaryAnnotation(BinaryAnnotation.address(CLIENT_ADDR, someClient))
            .build(),
        Span.builder().traceId(20L).parentId(20L).id(21L).name("get")
            .timestamp((TODAY + 50L) * 1000).duration(250L * 1000)
            .addAnnotation(Annotation.create((TODAY + 50) * 1000, CLIENT_SEND, WEB_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 100) * 1000, SERVER_RECV, APP_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 250) * 1000, SERVER_SEND, APP_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 300) * 1000, CLIENT_RECV, WEB_ENDPOINT))
            .build(),
        Span.builder().traceId(20L).parentId(21L).id(22L).name("get")
            .timestamp((TODAY + 150L) * 1000).duration(50L * 1000)
            .addAnnotation(Annotation.create((TODAY + 150) * 1000, CLIENT_SEND, APP_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 200) * 1000, CLIENT_RECV, APP_ENDPOINT))
            .addBinaryAnnotation(BinaryAnnotation.address(CLIENT_ADDR, APP_ENDPOINT))
            .addBinaryAnnotation(BinaryAnnotation.address(SERVER_ADDR, DB_ENDPOINT))
            .build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(TODAY + 1000L, null)).containsOnly(
        DependencyLink.create("some-client", "web", 1),
        DependencyLink.create("web", "app", 1),
        DependencyLink.create("app", "db", 1)
    );
  }

  @Test
  public void instrumentedClientAndServer() {
    List<Span> trace = asList(
        Span.builder().traceId(10L).id(10L).name("get")
            .timestamp((TODAY + 50L) * 1000).duration(250L * 1000)
            .addAnnotation(Annotation.create((TODAY + 50) * 1000, CLIENT_SEND, WEB_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 100) * 1000, SERVER_RECV, APP_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 250) * 1000, SERVER_SEND, APP_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 300) * 1000, CLIENT_RECV, WEB_ENDPOINT))
            .build(),
        Span.builder().traceId(10L).parentId(10L).id(11L).name("get")
            .timestamp((TODAY + 150L) * 1000).duration(50L * 1000)
            .addAnnotation(Annotation.create((TODAY + 150) * 1000, CLIENT_SEND, APP_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 200) * 1000, CLIENT_RECV, APP_ENDPOINT))
            .addBinaryAnnotation(BinaryAnnotation.address(CLIENT_ADDR, APP_ENDPOINT))
            .addBinaryAnnotation(BinaryAnnotation.address(SERVER_ADDR, DB_ENDPOINT))
            .build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(TODAY + 1000L, null)).containsOnly(
        DependencyLink.create("web", "app", 1),
        DependencyLink.create("app", "db", 1)
    );
  }

  /**
   * This test confirms that the span store can detect dependency indicated by SERVER_RECV or
   * SERVER_ADDR only. Some of implementations such as finagle don't send CLIENT_SEND and
   * CLIENT_ADDR annotations as desired. However, if there is a SERVER_RECV or SERVER_ADDR
   * annotation in the trace tree, the link can still be constructed.
   *
   * span1: SR SS: parent service span2: SA: Dependency 1
   *
   * Currently, the standard implentation can't detect a link with intermediate spans that should be
   * detected.
   *
   * span1: SR SS: parent service span2: intermediate call span3: SR SS: Dependency 1 not detectable
   * in the implementation
   */
  @Test
  public void noClientSendAddrAnnotations() {
    List<Span> trace = asList(
        Span.builder().traceId(20L).id(20L).name("get")
            .timestamp(TODAY * 1000).duration(350L * 1000)
            .addAnnotation(Annotation.create(TODAY * 1000, SERVER_RECV, WEB_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 350) * 1000, SERVER_SEND, WEB_ENDPOINT))
            .binaryAnnotations(asList( // finagle also sends SA/CA itself
                BinaryAnnotation.address(SERVER_ADDR, WEB_ENDPOINT),
                BinaryAnnotation.address(CLIENT_ADDR, WEB_ENDPOINT)))
            .build(),
        Span.builder().traceId(20L).parentId(20L).id(21L).name("get")
            .timestamp((TODAY + 150L) * 1000).duration(50L * 1000)
            .addAnnotation(Annotation.create((TODAY + 150) * 1000, CLIENT_SEND, APP_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 200) * 1000, CLIENT_RECV, APP_ENDPOINT))
            .binaryAnnotations(asList( // finagle also no SR on some condition and CA with itself
                BinaryAnnotation.address(SERVER_ADDR, APP_ENDPOINT),
                BinaryAnnotation.address(CLIENT_ADDR, APP_ENDPOINT)))
            .build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(TODAY + 1000L, null))
        .containsOnly(DependencyLink.create("web", "app", 1));
  }

  /**
   * This test shows that dependency links can be filtered at daily granularity. This allows the UI
   * to look for dependency intervals besides TODAY.
   */
  @Test
  public void canSearchForIntervalsBesidesToday() {
    // Let's pretend we have two days of data processed
    //  - Note: calling this twice allows test implementations to consider timestamps
    processDependencies(subtractDay(TRACE));
    processDependencies(TRACE);

    // A user looks at today's links.
    //  - Note: Using the smallest lookback avoids bumping into implementation around windowing.
    assertThat(store().getDependencies(DEPENDENCIES.endTs, DEPENDENCIES.endTs - DEPENDENCIES.startTs))
        .containsOnlyElementsOf(LINKS);

    // A user compares the links from those a day ago.
    assertThat(store().getDependencies(DEPENDENCIES.endTs - DAY, DEPENDENCIES.endTs - DEPENDENCIES.startTs))
        .containsOnlyElementsOf(LINKS);

    // A user looks at all links since data started
    assertThat(store().getDependencies(DEPENDENCIES.endTs, null)).containsOnly(
        DependencyLink.create("web", "app", 2),
        DependencyLink.create("app", "db", 2)
    );
  }

  /** This test confirms that core ("sr", "cs", "cr", "ss") annotations are not required. */
  @Test
  public void noCoreAnnotations() {
    Endpoint someClient = Endpoint.create("some-client", 172 << 24 | 17 << 16 | 4);
    List<Span> trace = asList(
        Span.builder().traceId(20L).id(20L).name("get")
            .timestamp(TODAY * 1000).duration(350L * 1000)
            .addBinaryAnnotation(BinaryAnnotation.address(CLIENT_ADDR, someClient))
            .addBinaryAnnotation(BinaryAnnotation.address(SERVER_ADDR, WEB_ENDPOINT)).build(),
        Span.builder().traceId(20L).parentId(20L).id(21L).name("get")
            .timestamp((TODAY + 50) * 1000).duration(250L * 1000)
            .addBinaryAnnotation(BinaryAnnotation.address(CLIENT_ADDR, WEB_ENDPOINT))
            .addBinaryAnnotation(BinaryAnnotation.address(SERVER_ADDR, APP_ENDPOINT)).build(),
        Span.builder().traceId(20L).parentId(21L).id(22L).name("get")
            .timestamp((TODAY + 150) * 1000).duration(50L * 1000)
            .addBinaryAnnotation(BinaryAnnotation.address(CLIENT_ADDR, APP_ENDPOINT))
            .addBinaryAnnotation(BinaryAnnotation.address(SERVER_ADDR, DB_ENDPOINT)).build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(TODAY + 1000, null)).containsOnly(
        DependencyLink.create("some-client", "web", 1),
        DependencyLink.create("web", "app", 1),
        DependencyLink.create("app", "db", 1)
    );
  }

  /**
   * This test confirms that the span store can process trace with intermediate spans like the below
   * properly.
   *
   * span1: SR SS span2: intermediate call span3: CS SR SS CR: Dependency 1
   */
  @Test
  public void intermediateSpans() {
    List<Span> trace = asList(
        Span.builder().traceId(20L).id(20L).name("get")
            .timestamp(TODAY * 1000).duration(350L * 1000)
            .addAnnotation(Annotation.create(TODAY * 1000, SERVER_RECV, WEB_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 350) * 1000, SERVER_SEND, WEB_ENDPOINT)).build(),
        Span.builder().traceId(20L).parentId(20L).id(21L).name("call")
            .timestamp((TODAY + 25) * 1000).duration(325L * 1000)
            .addBinaryAnnotation(
                BinaryAnnotation.create(Constants.LOCAL_COMPONENT, "depth2", WEB_ENDPOINT)).build(),
        Span.builder().traceId(20L).parentId(21L).id(22L).name("get")
            .timestamp((TODAY + 50) * 1000).duration(250L * 1000)
            .addAnnotation(Annotation.create((TODAY + 50) * 1000, CLIENT_SEND, WEB_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 100) * 1000, SERVER_RECV, APP_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 250) * 1000, SERVER_SEND, APP_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 300) * 1000, CLIENT_RECV, WEB_ENDPOINT)).build(),
        Span.builder().traceId(20L).parentId(22L).id(23L).name("call")
            .timestamp((TODAY + 110) * 1000).duration(130L * 1000)
            .addBinaryAnnotation(
                BinaryAnnotation.create(Constants.LOCAL_COMPONENT, "depth4", APP_ENDPOINT)).build(),
        Span.builder().traceId(20L).parentId(23L).id(24L).name("call")
            .timestamp((TODAY + 125) * 1000).duration(105L * 1000)
            .addBinaryAnnotation(
                BinaryAnnotation.create(Constants.LOCAL_COMPONENT, "depth5", APP_ENDPOINT)).build(),
        Span.builder().traceId(20L).parentId(24L).id(25L).name("get")
            .timestamp((TODAY + 150) * 1000).duration(50L * 1000)
            .addAnnotation(Annotation.create((TODAY + 150) * 1000, CLIENT_SEND, APP_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 200) * 1000, CLIENT_RECV, APP_ENDPOINT))
            .addBinaryAnnotation(BinaryAnnotation.address(SERVER_ADDR, DB_ENDPOINT)).build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(TODAY + 1000, null)).containsOnly(
        DependencyLink.create("web", "app", 1),
        DependencyLink.create("app", "db", 1)
    );
  }

  /**
   * This test confirms that the span store can process trace with intermediate spans like the below
   * properly.
   *
   * span1: SR SS span2: intermediate call span3: CS SR SS CR: Dependency 1
   */
  @Test
  public void duplicateAddress() {
    List<Span> trace = asList(
        Span.builder().traceId(20L).id(20L).name("get")
            .timestamp(TODAY * 1000).duration(350L * 1000)
            .addAnnotation(Annotation.create(TODAY * 1000, SERVER_RECV, WEB_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 350) * 1000, SERVER_SEND, WEB_ENDPOINT))
            .addBinaryAnnotation(BinaryAnnotation.address(CLIENT_ADDR, WEB_ENDPOINT))
            .addBinaryAnnotation(BinaryAnnotation.address(SERVER_ADDR, WEB_ENDPOINT)).build(),
        Span.builder().traceId(20L).parentId(21L).id(22L).name("get")
            .timestamp((TODAY + 50) * 1000).duration(250L * 1000)
            .addAnnotation(Annotation.create((TODAY + 50) * 1000, CLIENT_SEND, WEB_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 300) * 1000, CLIENT_RECV, WEB_ENDPOINT))
            .addBinaryAnnotation(BinaryAnnotation.address(CLIENT_ADDR, APP_ENDPOINT))
            .addBinaryAnnotation(BinaryAnnotation.address(SERVER_ADDR, APP_ENDPOINT)).build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(TODAY + 1000, null)).containsOnly(
        DependencyLink.create("web", "app", 1)
    );
  }

  @Test
  public void unmergedSpans() {
    List<Span> trace = asList(
        Span.builder().traceId(1L).parentId(1L).id(2L).name("get").timestamp((TODAY + 100) * 1000)
            .addAnnotation(Annotation.create((TODAY + 100) * 1000, SERVER_RECV, APP_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 250) * 1000, SERVER_SEND, APP_ENDPOINT))
            .addBinaryAnnotation(BinaryAnnotation.address(CLIENT_ADDR, WEB_ENDPOINT))
            .build(),
        Span.builder().traceId(1L).parentId(1L).id(2L).name("get").timestamp((TODAY + 50) * 1000)
            .addAnnotation(Annotation.create((TODAY + 50) * 1000, CLIENT_SEND, WEB_ENDPOINT))
            .addAnnotation(Annotation.create((TODAY + 300) * 1000, CLIENT_RECV, WEB_ENDPOINT))
            .addBinaryAnnotation(BinaryAnnotation.address(SERVER_ADDR, APP_ENDPOINT))
            .build()
    );

    processDependencies(trace);

    assertThat(store().getDependencies(TODAY + 1000, null)).containsOnly(
        DependencyLink.create("web", "app", 1)
    );
  }

  /** rebases a trace backwards a day with different trace and span id. */
  List<Span> subtractDay(List<Span> trace) {
    return trace.stream()
        .map(s -> s.toBuilder()
            .traceId(s.traceId + 100)
            .parentId(s.parentId != null ? s.parentId + 100 : null)
            .id(s.id + 100)
            .timestamp(s.timestamp != null ? s.timestamp - (DAY * 1000) : null)
            .annotations(s.annotations.stream()
                .map(a -> Annotation.create(a.timestamp - (DAY * 1000), a.value, a.endpoint))
                .collect(toList()))
            .build()
        ).collect(toList());
  }
}
