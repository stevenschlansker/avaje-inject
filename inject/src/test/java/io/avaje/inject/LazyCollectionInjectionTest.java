package io.avaje.inject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.avaje.inject.spi.AvajeModule;
import io.avaje.inject.spi.Builder;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Exercises the lazy collection injection path ({@code builder.listLazy}) at the public Builder SPI
 * level, modelling what the generator emits for a {@code List<T>} constructor parameter.
 *
 * <p>The "consumer" module registers one contribution and a bean whose constructor takes the full
 * {@code List<Contribution>}; the "provider" module (built later, because it requires the consumer)
 * registers a second contribution. With eager resolution the consumer would capture only its own
 * contribution; with lazy resolution it sees both.
 */
class LazyCollectionInjectionTest {

  interface Contribution {
    String name();
  }

  static final class ConsumerContribution implements Contribution {
    @Override
    public String name() {
      return "consumer";
    }
  }

  static final class ProviderContribution implements Contribution {
    @Override
    public String name() {
      return "provider";
    }
  }

  /** Captures the injected list but does not read it during construction. */
  static final class Aggregator {
    final List<Contribution> contributions;

    Aggregator(List<Contribution> contributions) {
      this.contributions = contributions;
    }

    List<String> names() {
      return contributions.stream().map(Contribution::name).sorted().collect(Collectors.toList());
    }
  }

  /** Reads the injected list during construction - which cannot be correct yet. */
  static final class EagerReader {
    final int countAtConstruction;

    EagerReader(List<Contribution> contributions) {
      this.countAtConstruction = contributions.size();
    }
  }

  static final class ConsumerModule implements AvajeModule {
    private final boolean readDuringConstruction;

    ConsumerModule(boolean readDuringConstruction) {
      this.readDuringConstruction = readDuringConstruction;
    }

    @Override
    public Class<?>[] classes() {
      return new Class<?>[] {ConsumerContribution.class, Aggregator.class, EagerReader.class};
    }

    @Override
    public String[] providesBeans() {
      return new String[] {
        Contribution.class.getTypeName(),
        Aggregator.class.getTypeName(),
        EagerReader.class.getTypeName()
      };
    }

    @Override
    public void build(Builder builder) {
      if (builder.isBeanAbsent(ConsumerContribution.class, Contribution.class)) {
        builder.register(new ConsumerContribution());
      }
      if (readDuringConstruction) {
        if (builder.isBeanAbsent(EagerReader.class)) {
          builder.register(new EagerReader(builder.listLazy(Contribution.class, null)));
        }
      } else {
        if (builder.isBeanAbsent(Aggregator.class)) {
          builder.register(new Aggregator(builder.listLazy(Contribution.class, null)));
        }
      }
    }
  }

  static final class ProviderModule implements AvajeModule {
    @Override
    public Class<?>[] classes() {
      return new Class<?>[] {ProviderContribution.class};
    }

    @Override
    public String[] providesBeans() {
      return new String[] {Contribution.class.getTypeName()};
    }

    @Override
    public String[] requiresBeans() {
      // forces this module to build AFTER the consumer
      return new String[] {Aggregator.class.getTypeName(), EagerReader.class.getTypeName()};
    }

    @Override
    public void build(Builder builder) {
      if (builder.isBeanAbsent(ProviderContribution.class, Contribution.class)) {
        builder.register(new ProviderContribution());
      }
    }
  }

  @Test
  void lazyListSeesContributionsFromAllModules() {
    try (BeanScope scope =
        BeanScope.builder().modules(new ConsumerModule(false), new ProviderModule()).build()) {

      assertThat(scope.get(Aggregator.class).names())
          .as("constructor-injected List<Contribution> must include later modules' contributions")
          .containsExactly("consumer", "provider");
    }
  }

  @Test
  void readingTheLazyListDuringWiringThrows() {
    assertThatThrownBy(
            () ->
                BeanScope.builder()
                    .modules(new ConsumerModule(true), new ProviderModule())
                    .build())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("during DI wiring")
        .hasMessageContaining("@PostConstruct");
  }

  @Test
  void resolvedListIsUnmodifiable() {
    try (BeanScope scope =
        BeanScope.builder().modules(new ConsumerModule(false), new ProviderModule()).build()) {

      List<Contribution> list = scope.get(Aggregator.class).contributions;
      assertThatThrownBy(() -> list.add(new ProviderContribution()))
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }
}
