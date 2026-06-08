# Dependency Injection with Avaje Inject

How to inject dependencies into beans.

## Constructor Injection

Inject through constructor (recommended):

```java
@Singleton
public class OrderService {
  private final UserService userService;
  private final PaymentService paymentService;

  public OrderService(UserService userService, PaymentService paymentService) {
    this.userService = userService;
    this.paymentService = paymentService;
  }
}
```

If a bean class has more than one constructor, annotate the constructor Avaje
Inject should use with `@Inject`. This is common when a class has a package-private
test constructor in addition to the normal DI constructor.

```java
@Singleton
class MetricsReporter {

  @Inject
  MetricsReporter(Configuration config, Optional<GraphiteReporter> reporter) {
    this(config, reporter.map(MetricsReporter::scheduledTask).orElse(null));
  }

  MetricsReporter(Configuration config, ScheduledTask task) {
    // test-friendly constructor
  }
}
```

## Multiple Implementations

Use `@Named` qualifier:

```java
public interface Logger { }

@Singleton
@Named("file")
public class FileLogger implements Logger { }

@Singleton
@Named("console")
public class ConsoleLogger implements Logger { }

@Singleton
public class Service {
  private final Logger fileLogger;

  public Service(@Named("file") Logger logger) {
    this.fileLogger = logger;
  }
}
```

## Cross-module priority and `Provider<T>`

When a `@Primary` or `@Secondary` bean and the constructor that depends on it live in different
modules, the bean a single-`T` constructor parameter receives depends on the order the modules are
wired. A constructor parameter of type `T` is resolved while the owning module is built, so it only
sees `T` providers from modules built earlier. If a `@Secondary` fallback is registered in (or
before) the consuming module and the `@Primary` is registered in a later-built module, the
constructor captures the `@Secondary` bean, while `BeanScope.get(T.class)` after the scope is built
returns the `@Primary`.

Constructor injection of a collection (`List<T>`, `Set<T>`, `Map<String, T>`) does not have this
problem: the collection is resolved after every module has registered, so it always includes
contributions from all modules.

To get the fully-resolved single bean, inject `Provider<T>` (or `Supplier<T>`) and call `get()` after
wiring has completed. Calling `get()` during wiring (in the constructor) throws, because the full
set of beans is not yet available; store the `Provider` and resolve it on use or from a
`@PostConstruct` method:

```java
@Singleton
public class GreeterUser {
  private final Provider<Greeter> greeter;

  public GreeterUser(Provider<Greeter> greeter) {
    this.greeter = greeter;
  }

  public String greeting() {
    // resolved after all modules register, so the @Primary wins
    return greeter.get().greeting();
  }
}
```

## Next Steps

- See [factory methods](factory-methods.md)
- Learn [lifecycle hooks](lifecycle-hooks.md)
