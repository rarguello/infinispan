package org.infinispan.executors;

import org.infinispan.commons.executors.ExecutorFactory;
import org.infinispan.commons.util.InfinispanCollections;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A delegating executor that lazily constructs and initializes the underlying executor, since unused JDK executors
 * are expensive.
 *
 * @author Manik Surtani
 * @since 5.1
 */
public final class LazyInitializingExecutorService implements ExecutorService {

   private volatile ExecutorService delegate;
   private final ExecutorFactory factory;
   private final Properties executorProperties;

   public LazyInitializingExecutorService(ExecutorFactory factory, Properties executorProperties) {
      this.factory = factory;
      this.executorProperties = executorProperties;
   }

   private void initIfNeeded() {
      if (delegate == null) {
         synchronized (this) {
            if (delegate == null) {
               delegate = factory.getExecutor(executorProperties);
            }
         }
      }
   }

   @Override
   public void shutdown() {
      if (delegate != null) delegate.shutdown(); 
   }

   @Override
   public List<Runnable> shutdownNow() {
      if (delegate == null)
         return InfinispanCollections.emptyList();
      else
         return delegate.shutdownNow();
   }

   @Override
   public boolean isShutdown() {
      return delegate == null || delegate.isShutdown();
   }

   @Override
   public boolean isTerminated() {
      return delegate == null || delegate.isTerminated();
   }

   @Override
   public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      if (delegate == null)
         return true;
      else
         return delegate.awaitTermination(timeout, unit);
   }

   @Override
   public <T> Future<T> submit(Callable<T> task) {
      initIfNeeded();
      return delegate.submit(task);
   }

   @Override
   public <T> Future<T> submit(Runnable task, T result) {
      initIfNeeded();
      return delegate.submit(task, result);
   }

   @Override
   public Future<?> submit(Runnable task) {
      initIfNeeded();
      return delegate.submit(task);
   }

   @Override
   public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
      initIfNeeded();
      return delegate.invokeAll(tasks);
   }

   @Override
   public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
      initIfNeeded();
      return delegate.invokeAll(tasks, timeout, unit);
   }

   @Override
   public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
      initIfNeeded();
      return delegate.invokeAny(tasks);
   }

   @Override
   public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
      initIfNeeded();
      return delegate.invokeAny(tasks, timeout, unit);
   }

   @Override
   public void execute(Runnable command) {
      initIfNeeded();
      delegate.execute(command);
   }
}
