package io.avaje.inject;

import static org.assertj.core.api.Assertions.assertThat;

import io.avaje.inject.spi.AvajeModule;
import io.avaje.inject.spi.Builder;
import org.junit.jupiter.api.Test;

/**
 * Reproduces a cross-module priority bug: a bean whose constructor takes a single {@code T} is wired
 * against only the {@code T} providers registered before its own module's {@code build()} runs, so a
 * {@code @Secondary} provider in (or before) the consuming module is selected over a primary provider
 * that registers in a later-built module — violating the {@code @Secondary} contract.
 *
 * <p>This mirrors, at the public {@link Builder} SPI level, exactly what the generator emits: the
 * "util" module registers a {@code @Secondary} {@code Greeter} ({@code builder.asSecondary()}) and then
 * eagerly resolves {@code builder.get(Greeter.class)} to construct its consumer; the "server" module
 * registers the primary {@code Greeter}. The "server" module {@code requires} the consumer, so it is
 * ordered after "util" (as a server module that depends on a util module would be) — which means the
 * consumer is wired before the primary is ever registered.
 *
 * <p>Querying {@code BeanScope.get(Greeter.class)} after the scope is built correctly returns the
 * primary, which localises the defect to the moment the consumer's constructor runs.
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
  void constructorInjectionPicksPrimaryOverSecondaryAcrossModules() {
    try (BeanScope scope =
        BeanScope.builder().modules(new UtilModule(), new ServerModule()).build()) {

      // After the scope is built, priority resolution is correct: the primary wins.
      assertThat(scope.get(Greeter.class).greeting()).isEqualTo("real");

      // The consumer's constructor must also have received the primary, not the @Secondary fallback.
      assertThat(scope.get(GreeterUser.class).greeter.greeting())
          .as("constructor-injected Greeter must be the primary, not the @Secondary fallback")
          .isEqualTo("real");
    }
  }

  @Test
  void orderIndependenceWithoutHardRequires() {
    // Same beans, but the server module does NOT require the consumer, so module order is free.
    AvajeModule util = new UtilModule();
    AvajeModule server =
        new ServerModule() {
          @Override
          public String[] requiresBeans() {
            return new String[0];
          }
        };

    assertThat(consumerGreeting(util, server))
        .as("modules=[util, server]")
        .isEqualTo("real");
    assertThat(consumerGreeting(server, util))
        .as("modules=[server, util]")
        .isEqualTo("real");
  }

  private static String consumerGreeting(AvajeModule a, AvajeModule b) {
    try (BeanScope scope = BeanScope.builder().modules(a, b).build()) {
      return scope.get(GreeterUser.class).greeter.greeting();
    }
  }
}
