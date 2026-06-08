package io.avaje.inject.spi;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Lazy {@link List}/{@link Set}/{@link Map} views used for constructor injection of collections.
 *
 * <p>A constructor parameter is resolved <em>eagerly</em> while the owning module's {@code build()}
 * runs, which is before all modules have registered their beans. For a collection that means the
 * constructor would only see contributions from already-built modules, silently dropping
 * contributions from modules built later. Field, method and {@code Provider} injection avoid this
 * because they resolve in {@code runInjectors()}, after every module has registered.
 *
 * <p>These views give a constructor parameter the same late-resolution guarantee: the underlying
 * collection is resolved once, on first access, against the fully-populated bean map. The common
 * pattern of capturing the collection in a field and reading it later (use, {@code @PostConstruct})
 * therefore sees every module's contributions.
 *
 * <p>Accessing the collection <em>during wiring</em> (for example inside the constructor that
 * received it) cannot return a correct result, because the beans it would aggregate do not exist
 * yet. Such access throws {@link IllegalStateException}, mirroring {@link ProviderPromise#get()}.
 * The resolved collection is unmodifiable: the injection contract is {@code List<T>} (etc.), not a
 * mutable collection the caller owns.
 */
final class LazyCollections {

  private LazyCollections() {}

  private static IllegalStateException wiringAccess(String collectionType) {
    return new IllegalStateException(
        "Illegal to access injected "
            + collectionType
            + " during DI wiring; the full set of beans is not yet available. "
            + "Capture it in a field and access it from a @PostConstruct method instead.");
  }

  static <T> List<T> list(BooleanSupplier wired, Supplier<List<T>> resolve) {
    return new LazyList<>(wired, () -> Collections.unmodifiableList(resolve.get()));
  }

  static <T> Set<T> set(BooleanSupplier wired, Supplier<Set<T>> resolve) {
    return new LazySet<>(wired, () -> Collections.unmodifiableSet(resolve.get()));
  }

  static <T> Map<String, T> map(BooleanSupplier wired, Supplier<Map<String, T>> resolve) {
    return new LazyMap<>(wired, () -> Collections.unmodifiableMap(resolve.get()));
  }

  /** Resolve once, guarding against access before wiring has completed. */
  private static final class Once<C> {
    private final BooleanSupplier wired;
    private final Supplier<C> resolve;
    private final String collectionType;
    private C value;

    Once(BooleanSupplier wired, Supplier<C> resolve, String collectionType) {
      this.wired = wired;
      this.resolve = resolve;
      this.collectionType = collectionType;
    }

    C get() {
      if (value == null) {
        if (!wired.getAsBoolean()) {
          throw wiringAccess(collectionType);
        }
        value = resolve.get();
      }
      return value;
    }
  }

  private static final class LazyList<T> extends AbstractList<T> {
    private final Once<List<T>> once;

    LazyList(BooleanSupplier wired, Supplier<List<T>> resolve) {
      this.once = new Once<>(wired, resolve, "List");
    }

    @Override
    public T get(int index) {
      return once.get().get(index);
    }

    @Override
    public int size() {
      return once.get().size();
    }
  }

  private static final class LazySet<T> extends AbstractSet<T> {
    private final Once<Set<T>> once;

    LazySet(BooleanSupplier wired, Supplier<Set<T>> resolve) {
      this.once = new Once<>(wired, resolve, "Set");
    }

    @Override
    public Iterator<T> iterator() {
      return once.get().iterator();
    }

    @Override
    public int size() {
      return once.get().size();
    }
  }

  private static final class LazyMap<T> extends AbstractMap<String, T> {
    private final Once<Map<String, T>> once;

    LazyMap(BooleanSupplier wired, Supplier<Map<String, T>> resolve) {
      this.once = new Once<>(wired, resolve, "Map");
    }

    @Override
    public Set<Entry<String, T>> entrySet() {
      return once.get().entrySet();
    }
  }
}
