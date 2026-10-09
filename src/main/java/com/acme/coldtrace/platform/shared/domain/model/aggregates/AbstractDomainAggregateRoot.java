package com.acme.coldtrace.platform.shared.domain.model.aggregates;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Extracted domain event buffer, independent from Spring and JPA. */
public abstract class AbstractDomainAggregateRoot<T> {
  private final List<Object> events = new ArrayList<>();

  protected void registerDomainEvent(Object event) {
    events.add(event);
  }

  public Collection<Object> domainEvents() {
    return List.copyOf(events);
  }

  public void clearDomainEvents() {
    events.clear();
  }
}
