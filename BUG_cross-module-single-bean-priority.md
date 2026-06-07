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
constructor dependency is resolved eagerly against a partial bean set during module build.

## Environment

- `io.avaje:avaje-inject` 12.6 (reproduced on `master` at the 12.6 tag)
- JDK 21+, Maven 3.9.x

## Reproduction

`inject/src/test/java/io/avaje/inject/CrossModuleSecondaryPriorityTest.java` (added in the same commit)
reproduces it at the public `Builder` SPI level — no generator needed, modelling exactly what the
generated `<Bean>$DI.build` emits.

```
mvn -pl inject test -Dtest=CrossModuleSecondaryPriorityTest
```

`constructorInjectionPicksPrimaryOverSecondaryAcrossModules` fails on 12.6: the consumer's constructor
receives `"noop"` (the `@Secondary` fallback) instead of `"real"` (the primary), while
`scope.get(Greeter.class)` after build returns `"real"`. `orderIndependenceWithoutHardRequires` shows the
result flips with module insertion order when there is no hard `requires`, which is the clearest signal
this is an ordering bug rather than intended `@Secondary` behaviour.

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

Expected: the consumer resolves the primary in every case, matching `scope.get(Greeter)`.

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

## Candidate fix (directions, not implemented here)

This branch intentionally ships only the report and a failing reproducer; the fix is left to the
maintainers, since the viable approaches are a design choice and a complete fix is hard to validate
against the full generator / native-image / multi-scope matrix from the outside.

Two directions, mirroring the `List<T>` form:

- **Defer single-bean constructor resolution** for a `T` that has providers in more than one module
  until after all modules register, the way field/method injection is already deferred via
  `addInjector` / `runInjectors`. This is the only approach that fixes the hard-`requires` shape (rows
  1-2 of the table), where the consumer's module is force-ordered first and reordering cannot help.
- **Make module ordering priority-aware** so a module that provides only a non-primary `T` is ordered
  after modules that may contribute a higher-priority `T`. This fixes the order-dependent no-`requires`
  case (rows 3-4) but, on its own, not the hard-`requires` case.
