package io.avaje.inject.spi;

import io.avaje.inject.InjectModule;

/** A Module containing dependencies that will be included in BeanScope. */
public interface AvajeModule extends InjectExtension {

  /** Empty array of strings. */
  String[] EMPTY_STRINGS = {};

  /**
   * Return public classes of the beans that would be registered by this module.
   *
   * <p>This method allows code to use reflection to inspect the modules classes before the module
   * is wired. This method is not required for DI wiring.
   */
  Class<?>[] classes();

  /** Build all the beans. */
  void build(Builder builder);

  /** Return the type names of types this module explicitly provides to other modules. */
  default String[] providesBeans() {
    return EMPTY_STRINGS;
  }

  /** Return the type(s) of scopes that this module provides. */
  default String[] definesScopes() {
    return EMPTY_STRINGS;
  }

  /**
   * Return the type names of types this module needs to be provided externally or via other
   * modules.
   */
  default String[] requiresBeans() {
    return EMPTY_STRINGS;
  }

  /** Return the type names of packages this module needs to be provided via other modules. */
  default String[] requiresPackagesFromType() {
    return EMPTY_STRINGS;
  }

  /**
   * Return the element type names this module aggregates via collection injection
   * ({@code List<T>} or {@code Set<T>}) at construction time.
   *
   * <p>Unlike {@link #requiresBeans()} these are soft ordering hints, not hard requirements: a
   * module that aggregates {@code T} is built after modules that provide {@code T} so the aggregated
   * collection sees every contribution, but the ordering must not deadlock when no such ordering
   * exists (for example when the aggregating module also provides a {@code T} of its own, or when
   * there is a dependency cycle). Modules generated before this method existed return an empty
   * array, preserving the previous behaviour.
   */
  default String[] aggregateBeans() {
    return EMPTY_STRINGS;
  }

  /** Whether The Module is an {@link InjectModule#interweave} module */
  default boolean interweaved() {
    return false;
  }

  /** Marker for custom scoped modules. */
  interface Custom extends AvajeModule {}
}
