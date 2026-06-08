package io.avaje.inject;

import static org.assertj.core.api.Assertions.assertThat;

import io.avaje.inject.spi.AvajeModule;
import io.avaje.inject.spi.Builder;
import org.junit.jupiter.api.Test;

/**
 * Pins the cross-module resolution behaviour of a single-{@code T} constructor parameter.
 *
 * <p>A constructor parameter of type {@code T} is resolved eagerly while its own module's {@code
 * build()} runs, so it sees only the {@code T} providers registered by then. When a {@code
 * @Secondary} fallback is registered in (or before) the consuming module and the primary registers
 * in a later-built module, the constructor captures the {@code @Secondary} bean, even though {@code
 * BeanScope.get(T.class)} after the scope is built returns the primary. Unlike collection
 * injection (see {@code LazyCollectionInjectionTest}), a plain {@code T} parameter cannot be
 * resolved lazily — it is a value captured in a final field, not a container that can be filled
 * later — so the supported way to obtain the fully-resolved bean is to inject {@code Provider<T>}
 * (see {@link CrossModuleSingleBeanProviderTest}).
 *
 * <p>These tests assert that documented behaviour at the public {@link Builder} SPI level, mirroring
 * what the generator emits: the "util" module registers a {@code @Secondary} {@code Greeter} and
 * eagerly resolves {@code builder.get(Greeter.class)} to construct its consumer; the "server" module
 * registers the primary and {@code requires} the consumer, so it is wired afterwards.
 */
class CrossModuleSecondaryPriorityTest {

  interface Greeter {
    String greeting();
  }

  /** Holds the {@link Greeter} that was injected into its constructor at build time. */
  static final class GreeterUser {
    final Greeter greeter;

    GreeterUser(Greeter greeter) {
      this.greeter = greeter;
    }
  }

  /** "util" module: registers the @Secondary fallback, then eagerly builds the consumer. */
  static final class UtilModule implements AvajeModule {
    @Override
    public Class<?>[] classes() {
      return new Class<?>[] {Greeter.class, GreeterUser.class};
    }

    @Override
    public String[] providesBeans() {
      return new String[] {Greeter.class.getTypeName(), GreeterUser.class.getTypeName()};
    }

    @Override
    public void build(Builder builder) {
      // @Secondary fallback Greeter (e.g. a no-op), provided by the util module.
      if (builder.isBeanAbsent(Greeter.class)) {
        builder.asSecondary().register((Greeter) () -> "noop");
      }
      // Consumer constructed eagerly during this module's build, exactly as the generated
      // <Bean>$DI.build does for a single-T constructor parameter: builder.get(Greeter.class).
      if (builder.isBeanAbsent(GreeterUser.class)) {
        builder.register(new GreeterUser(builder.get(Greeter.class)));
      }
    }
  }

  /** "server" module: registers the primary Greeter; requires the util consumer so it builds later. */
  static class ServerModule implements AvajeModule {
    @Override
    public Class<?>[] classes() {
      return new Class<?>[] {Greeter.class};
    }

    @Override
    public String[] providesBeans() {
      return new String[] {Greeter.class.getTypeName()};
    }

    @Override
    public String[] requiresBeans() {
      return new String[] {GreeterUser.class.getTypeName()};
    }

    @Override
    public void build(Builder builder) {
      if (builder.isBeanAbsent(Greeter.class)) {
        builder.register((Greeter) () -> "real");
      }
    }
  }

  @Test
  void constructorScopeGetDivergesAcrossModules() {
    try (BeanScope scope =
        BeanScope.builder().modules(new UtilModule(), new ServerModule()).build()) {

      // After the scope is built, priority resolution is correct: the primary wins.
      assertThat(scope.get(Greeter.class).greeting()).isEqualTo("real");

      // The eagerly-resolved constructor parameter, however, captured the @Secondary fallback
      // because the primary's module had not been built yet. Injecting Provider<Greeter> instead
      // resolves the primary (see CrossModuleSingleBeanProviderTest).
      assertThat(scope.get(GreeterUser.class).greeter.greeting())
          .as("plain-T constructor injection resolves eagerly against the partial bean set")
          .isEqualTo("noop");
    }
  }

  @Test
  void plainConstructorParamIsOrderDependentWithoutHardRequires() {
    // Same beans, but the server module does NOT require the consumer, so module order is free.
    // The eagerly-resolved plain-T parameter therefore depends on module insertion order.
    AvajeModule util = new UtilModule();
    AvajeModule server =
        new ServerModule() {
          @Override
          public String[] requiresBeans() {
            return new String[0];
          }
        };

    // consumer (util) built first: primary (server) not yet registered -> @Secondary captured
    assertThat(consumerGreeting(util, server)).as("modules=[util, server]").isEqualTo("noop");
    // provider (server) built first: primary already registered -> primary captured
    assertThat(consumerGreeting(server, util)).as("modules=[server, util]").isEqualTo("real");
  }

  private static String consumerGreeting(AvajeModule a, AvajeModule b) {
    try (BeanScope scope = BeanScope.builder().modules(a, b).build()) {
      return scope.get(GreeterUser.class).greeter.greeting();
    }
  }
}
