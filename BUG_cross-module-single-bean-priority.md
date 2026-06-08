# Cross-module constructor injection ignores `@Primary`/`@Secondary` priority

## Summary

A bean whose constructor takes a single `T` is wired against only the `T` providers registered before
its own module's `build()` runs, rather than against the full set with `@Primary`/`@Secondary` priority
applied. When a `@Secondary` provider of `T` lives in (or before) the consuming bean's module, and the
primary provider of `T` lives in a module that builds later, the constructor captures the `@Secondary`
bean even though a primary exists — violating the `@Secondary` contract ("only used when no other
candidate is available").

`BeanScope.get(T.class)` after the scope is built correctly returns the primary, so the loss is specific
to the constructor parameter resolved during wiring.

This is the single-bean analogue of the cross-module `List<T>` aggregation issue: in both cases a
constructor dependency is resolved eagerly against a partial bean set during module build. See
"Resolution" below — the collection form is fixed transparently; the single-bean form is addressed by
injecting `Provider<T>`.

## Environment

- `io.avaje:avaje-inject` 12.6 (reproduced on `master` at commit `3b7ebb02`, the 12.6 release)
- JDK 21+, Maven 3.9.x

## Reproduction

`inject/src/test/java/io/avaje/inject/CrossModuleSecondaryPriorityTest.java` reproduces it at the
public `Builder` SPI level — no generator needed, modelling exactly what the generated
`<Bean>$DI.build` emits. (That test now pins the documented behaviour described under "Resolution"
below rather than asserting a fix.)

```
mvn -pl inject test -Dtest=CrossModuleSecondaryPriorityTest
```

On 12.6 the consumer's constructor receives `"noop"` (the `@Secondary` fallback) instead of `"real"`
(the primary), while `scope.get(Greeter.class)` after build returns `"real"`
(`constructorScopeGetDivergesAcrossModules`). The result also flips with module insertion order when
there is no hard `requires` (`plainConstructorParamIsOrderDependentWithoutHardRequires`), which is the
clearest signal this is eager-resolution order-dependence rather than intended `@Secondary` behaviour.

The shape:

```
module "util"  (provides Greeter, GreeterUser):
    build(builder):
        builder.asSecondary().register((Greeter) () -> "noop");   // @Secondary fallback
        builder.register(new GreeterUser(builder.get(Greeter.class)));  // eager single-T injection

module "server" (provides Greeter, requires GreeterUser):
    build(builder):
        builder.register((Greeter) () -> "real");                 // primary
```

`server` `requires` `GreeterUser`, so it is ordered after `util` (mirroring a server module that depends
on a util module). The consumer is therefore wired while only the `@Secondary` fallback is registered.

### Observed vs expected

| consumer + `@Secondary` in | primary in | module order | consumer gets | `scope.get(Greeter)` |
|---|---|---|---|---|
| util | server (`requires` util) | `[util, server]` | `noop` (wrong) | `real` |
| util | server (`requires` util) | `[server, util]` | `noop` (wrong) | `real` |
| util | server (no `requires`)   | `[util, server]` | `noop` (wrong) | `real` |
| util | server (no `requires`)   | `[server, util]` | `real` (correct) | `real` |

All four rows match `scope.get(Greeter)` once the consumer injects `Provider<Greeter>` and resolves it
after wiring (see "Resolution"). With a plain `Greeter` parameter the table above is the observed
eager-resolution behaviour.

## Root cause

1. For a single-`T` constructor parameter the generator emits an eager `builder.get(T.class, ...)` inside
   the consuming bean's `build()`. `DBuilder.getMaybe` -> `DBeanMap.get` -> `DContextEntry.get`
   (`EntryMatcher.match`) resolves priority only among the entries registered *so far*. If only the
   `@Secondary` candidate is registered at that point, it is returned; a higher-priority provider that
   registers in a later module is never considered.

2. Module ordering (`DBeanScopeBuilder.FactoryOrder`) uses `providesBeans()`/`requiresBeans()`. The util
   module's `providesBeans()` lists the `T` (its `@Secondary` provider), so it already counts as a
   complete `T` provider; nothing records that a higher-priority `T` exists elsewhere, so there is no
   ordering pressure to build the primary's module first.

3. `scope.get(T.class)` after build is correct because, by then, every provider is registered and
   `EntryMatcher` chooses the primary.

## Resolution

The shared root cause — a constructor dependency resolved eagerly against a partial bean set during
module build — is fixed for collections and handled with an opt-in for single beans.

**Collections (`List<T>`, `Set<T>`, `Map<String, T>`) are fixed transparently.** A collection
constructor parameter now resolves lazily, on first access, after every module has registered, so it
sees contributions from all modules regardless of build order. Existing code that captures the
collection and reads it later needs no change. Accessing the collection during wiring (inside the
receiving constructor) throws, because the full set of beans is not yet available. See
`LazyCollections`, `Builder.listLazy/setLazy/mapLazy`, and `LazyCollectionInjectionTest`.

**A single `T` cannot be fixed transparently, so inject `Provider<T>`.** A plain `T` parameter is a
value captured in a final field, not a container that can be filled later, so it cannot be corrected
after construction. Deferring the whole bean's construction cannot be done from the generator either:
it would have to defer every cross-module consumer of the deferred bean too, and the generator
processes each module in isolation so it cannot see them. Injecting `Provider<T>` (or `Supplier<T>`)
and calling `get()` after wiring resolves the primary, including in the hard-`requires` shape (rows
1-2 of the table). This is documented in `docs/guides/dependency-injection.md` and covered by
`CrossModuleSingleBeanProviderTest`.

```java
@Singleton
public class GreeterUser {
  private final Provider<Greeter> greeter;

  public GreeterUser(Provider<Greeter> greeter) {
    this.greeter = greeter; // do not call get() here - wiring is not complete
  }

  public String greeting() {
    return greeter.get().greeting(); // resolved after wiring, so the @Primary wins
  }
}
```
