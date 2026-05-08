package io.avaje.inject.example.supplied;

import io.avaje.inject.Component;
import io.avaje.inject.External;

import org.other.one.interweave.BeanRequiresLocal;

/**
 * Application-side bean that requires {@link BeanRequiresLocal}, which is provided by
 * {@code OneModule} in the {@code blackbox-other} maven module. The cross-module
 * dependency forces the runtime to order {@code OneModule} before this module's
 * generated module when wiring.
 */
@Component
public class AppBean {

  private final BeanRequiresLocal libraryBean;

  public AppBean(@External BeanRequiresLocal libraryBean) {
    this.libraryBean = libraryBean;
  }

  public BeanRequiresLocal libraryBean() {
    return libraryBean;
  }
}
