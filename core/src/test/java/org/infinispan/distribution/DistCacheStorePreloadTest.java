package org.infinispan.distribution;

import org.infinispan.Cache;
import org.infinispan.container.DataContainer;
import org.infinispan.loaders.CacheLoaderException;
import org.infinispan.loaders.CacheLoaderManager;
import org.infinispan.loaders.dummy.DummyInMemoryCacheStore;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.test.TestingUtil;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.Collections;

import static org.testng.AssertJUnit.assertEquals;

/**
 * Test preloading with a distributed cache.
 *
 * @author Dan Berindei &lt;dan@infinispan.org&gt;
 * @since 5.1
 */
@Test(groups = "functional", testName = "distribution.DistCacheStorePreloadTest")
public class DistCacheStorePreloadTest extends BaseDistCacheStoreTest {

   public static final int NUM_KEYS = 10;

   public DistCacheStorePreloadTest() {
      INIT_CLUSTER_SIZE = 1;
      sync = true;
      tx = false;
      testRetVals = true;
      shared = true;
      preload = true;
   }

   @AfterMethod
   public void clearStats() {
      for (Cache<?, ?> c: caches) {
         log.trace("Clearing stats for cache store on cache "+ c);
         DummyInMemoryCacheStore cs = (DummyInMemoryCacheStore) TestingUtil.extractComponent(c, CacheLoaderManager.class).getCacheStore();
         cs.clear();
         cs.clearStats();
      }
   }

   public void testPreloadOnStart() throws CacheLoaderException {
      for (int i = 0; i < NUM_KEYS; i++) {
         c1.put("k" + i, "v" + i);
      }
      DataContainer dc1 = c1.getAdvancedCache().getDataContainer();
      assert dc1.size() == NUM_KEYS;

      DummyInMemoryCacheStore cs = (DummyInMemoryCacheStore) TestingUtil.extractComponent(c1, CacheLoaderManager.class).getCacheStore();
      assert cs.loadAllKeys(Collections.emptySet()).size() == NUM_KEYS;

      addClusterEnabledCacheManager();
      EmbeddedCacheManager cm2 = cacheManagers.get(1);
      cm2.defineConfiguration(cacheName, buildConfiguration().build());
      c2 = cache(1, cacheName);
      waitForClusterToForm();

      DataContainer dc2 = c2.getAdvancedCache().getDataContainer();
      assertEquals("Expected all the cache store entries to be preloaded on the second cache", NUM_KEYS, dc2.size());

      for (int i = 0; i < NUM_KEYS; i++) {
         assertOwnershipAndNonOwnership("k" + i, true);
      }
   }
}
