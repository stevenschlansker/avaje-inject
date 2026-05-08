package io.avaje.inject.example.supplied;

import io.avaje.inject.BeanScope;

import org.junit.jupiter.api.Test;
import org.other.one.OneModule;
import org.other.one.interweave.IFromLocal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-generator-output reproduction of the supplied-bean module-ordering bug, using two
 * AvajeModules produced by the avaje-inject annotation processor at build time:
 *
 * <ul>
 *   <li>{@code SuppliedModule} (this maven module, see
 *       {@code target/generated-sources/annotations/.../SuppliedModule.java}) — provides
 *       {@link AppBean} and requires {@code BeanRequiresLocal} from the dependency
 *       module.</li>
 *   <li>{@link OneModule} (from the {@code blackbox-other} dependency) — provides
 *       {@code BeanRequiresLocal} and requires {@link IFromLocal}.</li>
 * </ul>
 *
 * <p>{@link IFromLocal} is supplied externally via
 * {@link io.avaje.inject.BeanScopeBuilder#bean(Class, Object)}, modeling the common
 * test pattern of injecting a fake/mock for a type provided by an unloaded module.
 *
 * <h2>Why interweave does not cover this</h2>
 *
 * <p>The runtime path that consults the generated {@code CompiledOrder} (the runtime
 * artifact of {@code @InjectModule(interweave = true)}) is gated in
 * {@code DBeanScopeBuilder.build()}:
 *
 * <pre>{@code
 *   if (includeModules.isEmpty()) {
 *     factoryOrder = serviceLoader.moduleOrdering()
 *         .filter(o -> o.supportsExpected(modules))
 *         .orElse(factoryOrder);
 *   }
 * }</pre>
 *
 * <p>Any caller that passes modules to
 * {@link io.avaje.inject.BeanScopeBuilder#modules} skips the {@code CompiledOrder}
 * branch unconditionally, regardless of whether the project has interweave enabled.
 * That includes:
 *
 * <ul>
 *   <li>Tests that compose a deliberate subset of modules and supply mocks via
 *       {@link io.avaje.inject.BeanScopeBuilder#bean(Class, Object)} or
 *       {@code BeanScopeBuilder#mock(Class)}.</li>
 *   <li>Library code that builds a child scope from a curated module list.</li>
 *   <li>Custom scopes — only {@code Type.DEFAULT} scopes get
 *       {@code interweaved() == true} from the generator.</li>
 *   <li>Applications whose dependency modules were compiled before c0c63dbc and
 *       therefore lack the public static {@code build_*} methods that interweave
 *       requires (the generator errors out at build time on those).</li>
 * </ul>
 *
 * <h2>Bug behavior without the fix</h2>
 *
 * <p>With the modules added in the order {@code [SuppliedModule, OneModule]}, the strict
 * topological pass in {@code FactoryOrder} stalls: {@code SuppliedModule}'s
 * {@code BeanRequiresLocal} requirement has provider {@code OneModule} (not pushed),
 * and {@code OneModule}'s {@link IFromLocal} requirement has no module-level provider
 * at all (it is supplied externally via {@code .bean()}). The supplied-beans fallback
 * then dumps the queue in insertion order, runs {@code SuppliedModule.build()} first,
 * and fails when {@link AppBean}'s constructor cannot find {@code BeanRequiresLocal}:
 *
 * <pre>
 *   IllegalStateException: Injecting null for org.other.one.interweave.BeanRequiresLocal
 *   when creating class io.avaje.inject.example.supplied.AppBean - potential beans to
 *   inject: []
 * </pre>
 */
class SuppliedBeanOrderingExampleTest {

  @Test
  void suppliedExternalBean_withExplicitModulesInDependencyReverseOrder_wires() {
    IFromLocal fakeFromLocal = new IFromLocal() {};

    // Order [SuppliedModule, OneModule] is the bug-triggering order: the consumer is
    // added before the producer, the strict topological pass stalls, and the fallback
    // path is exercised.
    try (BeanScope scope = BeanScope.builder()
        .bean(IFromLocal.class, fakeFromLocal)
        .modules(new SuppliedModule(), new OneModule())
        .build()) {

      AppBean appBean = scope.get(AppBean.class);
      assertThat(appBean).isNotNull();
      assertThat(appBean.libraryBean()).isNotNull();
      assertThat(appBean.libraryBean().iFromLocal()).isSameAs(fakeFromLocal);
    }
  }
}
