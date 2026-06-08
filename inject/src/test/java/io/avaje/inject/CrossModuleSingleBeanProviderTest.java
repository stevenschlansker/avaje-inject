package io.avaje.inject;

import static org.assertj.core.api.Assertions.assertThat;

import io.avaje.inject.spi.AvajeModule;
import io.avaje.inject.spi.Builder;
import jakarta.inject.Provider;
import org.junit.jupiter.api.Test;

/**
 * Confirms the supported escape hatch for cross-module single-bean priority: a constructor that
 * takes {@code Provider<T>} resolves after wiring (via the deferred ProviderPromise) and therefore
 * sees the primary, even in the hard-{@code requires} shape that module reordering cannot fix.
 *
 * <p>This is the single-bean analogue of the lazy collection injection: the plain {@code T}
 * constructor parameter is captured in a final field and cannot be corrected post-build, but
 * {@code Provider<T>} defers resolution to first use.
 */
class CrossModuleSingleBeanProviderTest {

  interface Greeter {
    String greeting();
  }

  /** Takes Provider<Greeter> rather than Greeter, so resolution is deferred past wiring. */
  static final class GreeterUser {
    private final Provider<Greeter> greeter;

    GreeterUser(Provider<Greeter> greeter) {
      this.greeter = greeter;
    }

    String greeting() {
      return greeter.get().greeting();
    }
  }

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
      if (builder.isBeanAbsent(Greeter.class)) {
        builder.asSecondary().register((Greeter) () -> "noop");
      }
      if (builder.isBeanAbsent(GreeterUser.class)) {
        builder.register(new GreeterUser(builder.getProvider(Greeter.class)));
      }
    }
  }

  static final class ServerModule implements AvajeModule {
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
  void providerInjectionGetsPrimaryAcrossModules() {
    try (BeanScope scope =
        BeanScope.builder().modules(new UtilModule(), new ServerModule()).build()) {

      assertThat(scope.get(Greeter.class).greeting()).isEqualTo("real");
      assertThat(scope.get(GreeterUser.class).greeting())
          .as("Provider<Greeter> must resolve the primary, not the @Secondary fallback")
          .isEqualTo("real");
    }
  }
}
