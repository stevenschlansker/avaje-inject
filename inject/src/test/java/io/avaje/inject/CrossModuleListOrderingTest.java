package io.avaje.inject;

import static org.assertj.core.api.Assertions.assertThat;

import io.avaje.inject.spi.AvajeModule;
import io.avaje.inject.spi.Builder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Cross-module {@code List<T>} aggregation must include contributions from every module.
 *
 * <p>These hand-written {@link AvajeModule} implementations use only the public builder SPI and
 * mirror what the annotation generator emits for a {@code @Singleton} that takes a
 * {@code List<Marker>} constructor parameter: the list is resolved eagerly via
 * {@link Builder#list(Class)} while the consuming module is being built. The consuming module
 * declares the element type via {@link AvajeModule#aggregateBeans()}, so module ordering builds it
 * after every {@code Marker} provider and the eager snapshot sees all contributions, regardless of
 * the order modules were added. Without that hint the snapshot was insertion-order dependent and
 * silently dropped other modules' beans.
 *
 * <p>Field/method injection is deferred via {@link Builder#addInjector(java.util.function.Consumer)}
 * and aggregates correctly without any ordering hint; it is the reference behaviour here.
 */
class CrossModuleListOrderingTest {

  interface Marker {
    String name();
  }

  /** A consumer bean that snapshots the list at construction time (mirrors generated DI). */
  static final class Consumer {
    final List<Marker> markers;

    Consumer(List<Marker> markers) {
      this.markers = markers;
    }
  }

  private static List<String> names(List<Marker> markers) {
    List<String> out = new ArrayList<>();
    for (Marker m : markers) {
      out.add(m.name());
    }
    out.sort(null);
    return out;
  }

  /**
   * Minimal module that registers named {@link Marker} beans and, optionally, a {@link Consumer}
   * whose {@code List<Marker>} is resolved eagerly during the build (constructor injection) or via a
   * deferred injector (field/method injection), selected by {@code deferConsumer}.
   */
  static final class TestModule implements AvajeModule {
    private final String name;
    private final String[] provides;
    private final String[] requires;
    private String[] aggregates = EMPTY_STRINGS;
    private final List<String> markerNames = new ArrayList<>();
    private boolean registersConsumer;
    private boolean deferConsumer;

    TestModule(String name, String[] provides, String[] requires) {
      this.name = name;
      this.provides = provides;
      this.requires = requires;
    }

    TestModule aggregates(String... aggregated) {
      this.aggregates = aggregated;
      return this;
    }

    TestModule marker(String markerName) {
      markerNames.add(markerName);
      return this;
    }

    /** Register a Consumer that resolves its list eagerly, as constructor injection does. */
    TestModule eagerConsumer() {
      this.registersConsumer = true;
      this.deferConsumer = false;
      return this;
    }

    /** Register a Consumer that resolves its list in a deferred injector, as field injection does. */
    TestModule deferredConsumer() {
      this.registersConsumer = true;
      this.deferConsumer = true;
      return this;
    }

    @Override
    public String toString() {
      return name;
    }

    @Override
    public Class<?>[] classes() {
      return new Class<?>[0];
    }

    @Override
    public String[] providesBeans() {
      return provides;
    }

    @Override
    public String[] requiresBeans() {
      return requires;
    }

    @Override
    public String[] aggregateBeans() {
      return aggregates;
    }

    @Override
    public void build(Builder builder) {
      for (String markerName : markerNames) {
        if (builder.isBeanAbsent(markerName, Marker.class)) {
          builder.register((Marker) () -> markerName);
        }
      }
      if (registersConsumer && builder.isBeanAbsent(Consumer.class)) {
        if (deferConsumer) {
          Consumer bean = new Consumer(new ArrayList<>());
          builder.register(bean);
          builder.addInjector(b -> bean.markers.addAll(b.list(Marker.class)));
        } else {
          builder.register(new Consumer(builder.list(Marker.class)));
        }
      }
    }
  }

  private static TestModule consumerModule(boolean deferred) {
    TestModule m =
        new TestModule(
                "consumer",
                new String[] {
                  "io.avaje.inject.CrossModuleListOrderingTest$Consumer",
                  "io.avaje.inject.CrossModuleListOrderingTest$Marker"
                },
                new String[0])
            .aggregates("io.avaje.inject.CrossModuleListOrderingTest$Marker")
            .marker("A");
    return deferred ? m.deferredConsumer() : m.eagerConsumer();
  }

  private static TestModule providerModule() {
    return new TestModule(
            "provider",
            new String[] {"io.avaje.inject.CrossModuleListOrderingTest$Marker"},
            new String[0])
        .marker("B");
  }

  /**
   * Constructor (eager) list injection with the consumer module added before the provider module:
   * the aggregation hint orders the consumer after the provider, so the eager snapshot includes both
   * contributions. Before the hint this order dropped the provider's bean.
   */
  @Test
  void constructorListInjection_aggregatesOtherModule_whenConsumerAddedFirst() {
    AvajeModule consumer = consumerModule(false);
    AvajeModule provider = providerModule();

    try (BeanScope scope = BeanScope.builder().modules(consumer, provider).build()) {
      assertThat(names(scope.list(Marker.class))).containsExactly("A", "B");
      assertThat(names(scope.get(Consumer.class).markers))
          .describedAs("constructor List<Marker> must aggregate both modules' contributions")
          .containsExactly("A", "B");
    }
  }

  /** The other insertion order also aggregates both contributions. */
  @Test
  void constructorListInjection_aggregatesOtherModule_whenProviderAddedFirst() {
    AvajeModule consumer = consumerModule(false);
    AvajeModule provider = providerModule();

    try (BeanScope scope = BeanScope.builder().modules(provider, consumer).build()) {
      assertThat(names(scope.get(Consumer.class).markers)).containsExactly("A", "B");
    }
  }

  /**
   * Field/method (deferred) list injection aggregates correctly in either module order: the
   * generator resolves it after every module has built, independent of the ordering hint. This is the
   * reference behaviour the constructor path matches.
   */
  @Test
  void deferredListInjection_correct_inBothModuleOrders() {
    try (BeanScope scope =
        BeanScope.builder().modules(consumerModule(true), providerModule()).build()) {
      assertThat(names(scope.get(Consumer.class).markers)).containsExactly("A", "B");
    }
    try (BeanScope scope =
        BeanScope.builder().modules(providerModule(), consumerModule(true)).build()) {
      assertThat(names(scope.get(Consumer.class).markers)).containsExactly("A", "B");
    }
  }

  /**
   * Genuine conflict: the consumer aggregates Marker (so it wants to build after every Marker
   * provider) yet the provider hard-{@code requires} the consumer's bean (so it must build after the
   * consumer). No ordering satisfies both. The aggregation hint is best-effort, so the build still
   * completes rather than throwing, but the eager constructor snapshot cannot include the provider's
   * bean. A consumer that must see contributions from a module depending on it should use deferred
   * (field) injection.
   */
  @Test
  void aggregationConflictWithHardRequiresStillBuilds_snapshotIncomplete() {
    AvajeModule consumer = consumerModule(false); // aggregates Marker, self-provides "A"
    AvajeModule provider =
        new TestModule(
                "provider",
                new String[] {"io.avaje.inject.CrossModuleListOrderingTest$Marker"},
                new String[] {"io.avaje.inject.CrossModuleListOrderingTest$Consumer"})
            .marker("B");

    try (BeanScope scope = BeanScope.builder().modules(consumer, provider).build()) {
      // scope.list always reports every contribution after the build completes.
      assertThat(names(scope.list(Marker.class))).containsExactly("A", "B");
      // The eager constructor snapshot cannot include "B": "B" comes from the module that requires
      // this consumer, so the consumer is necessarily built first.
      assertThat(names(scope.get(Consumer.class).markers)).containsExactly("A");
    }
  }
}
