package io.avaje.inject;

import static org.assertj.core.api.Assertions.assertThat;

import io.avaje.inject.spi.AvajeModule;
import io.avaje.inject.spi.Builder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Reproduces cross-module {@code List<T>} aggregation dropping contributions from other modules.
 *
 * <p>These hand-written {@link AvajeModule} implementations use only the public builder SPI and
 * mirror what the annotation generator emits for a {@code @Singleton} that takes a
 * {@code List<Marker>} constructor parameter: the list is resolved eagerly via
 * {@link Builder#list(Class)} while the consuming module is being built. If the consuming module is
 * built before a module that contributes additional {@code Marker} beans, those contributions are
 * not yet registered and are silently dropped from the injected list. The same beans are visible via
 * {@link BeanScope#list(Class)} after the scope is fully built.
 *
 * <p>Contrast with field/method injection, which the generator defers via
 * {@link Builder#addInjector(java.util.function.Consumer)} and therefore aggregates correctly.
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
    private final List<String> markerNames = new ArrayList<>();
    private boolean registersConsumer;
    private boolean deferConsumer;

    TestModule(String name, String[] provides, String[] requires) {
      this.name = name;
      this.provides = provides;
      this.requires = requires;
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
   * The bug: with constructor (eager) list injection and the consumer module added before the
   * provider module, the provider's contribution is dropped from the injected list, even though
   * {@code scope.list} sees both. The result is purely insertion-order dependent.
   */
  @Test
  void constructorListInjection_dropsOtherModuleContribution_whenConsumerBuiltFirst() {
    AvajeModule consumer = consumerModule(false);
    AvajeModule provider = providerModule();

    try (BeanScope scope = BeanScope.builder().modules(consumer, provider).build()) {
      assertThat(names(scope.list(Marker.class))).containsExactly("A", "B");
      assertThat(names(scope.get(Consumer.class).markers))
          .describedAs("constructor List<Marker> must aggregate both modules' contributions")
          .containsExactly("A", "B");
    }
  }

  /** Reversing module insertion order happens to produce the correct result, confirming the cause. */
  @Test
  void constructorListInjection_correct_whenProviderBuiltFirst() {
    AvajeModule consumer = consumerModule(false);
    AvajeModule provider = providerModule();

    try (BeanScope scope = BeanScope.builder().modules(provider, consumer).build()) {
      assertThat(names(scope.get(Consumer.class).markers)).containsExactly("A", "B");
    }
  }

  /**
   * Field/method (deferred) list injection aggregates correctly regardless of module order, because
   * the generator resolves it after every module has built. This is both the reference behaviour the
   * constructor path should match and the available workaround today.
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
}
