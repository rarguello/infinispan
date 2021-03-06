package org.infinispan.interceptors.locking;

import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.metadata.Metadata;
import org.infinispan.commands.FlagAffectedCommand;
import org.infinispan.commands.tx.VersionedPrepareCommand;
import org.infinispan.commands.tx.totalorder.TotalOrderPrepareCommand;
import org.infinispan.commands.write.WriteCommand;
import org.infinispan.commons.util.Immutables;
import org.infinispan.commons.util.InfinispanCollections;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.container.DataContainer;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.versioning.EntryVersionsMap;
import org.infinispan.container.versioning.VersionGenerator;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.TxInvocationContext;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.scopes.Scope;
import org.infinispan.factories.scopes.Scopes;
import org.infinispan.notifications.cachelistener.CacheNotifier;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.statetransfer.StateTransferLock;
import org.infinispan.statetransfer.StateTransferManager;
import org.infinispan.topology.CacheTopology;
import org.infinispan.transaction.WriteSkewHelper;
import org.infinispan.transaction.xa.CacheTransaction;

import java.util.Collection;
import java.util.List;

import static org.infinispan.transaction.WriteSkewHelper.performTotalOrderWriteSkewCheckAndReturnNewVersions;
import static org.infinispan.transaction.WriteSkewHelper.performWriteSkewCheckAndReturnNewVersions;

/**
 * Abstractization for logic related to different clustering modes: replicated or distributed. This implements the <a
 * href="http://en.wikipedia.org/wiki/Bridge_pattern">Bridge</a> pattern as described by the GoF: this plays the role of
 * the <b>Implementor</b> and various LockingInterceptors are the <b>Abstraction</b>.
 *
 * @author Mircea Markus
 * @author Pedro Ruivo
 * @since 5.1
 */
@Scope(Scopes.NAMED_CACHE)
public interface ClusteringDependentLogic {

   boolean localNodeIsOwner(Object key);

   boolean localNodeIsPrimaryOwner(Object key);

   Address getPrimaryOwner(Object key);

   void commitEntry(CacheEntry entry, Metadata metadata, FlagAffectedCommand command, InvocationContext ctx);

   List<Address> getOwners(Collection<Object> keys);

   List<Address> getOwners(Object key);

   EntryVersionsMap createNewVersionsAndCheckForWriteSkews(VersionGenerator versionGenerator, TxInvocationContext context, VersionedPrepareCommand prepareCommand);
   
   Address getAddress();

   public static abstract class AbstractClusteringDependentLogic implements ClusteringDependentLogic {

      protected DataContainer dataContainer;

      protected CacheNotifier notifier;

      @Inject
      public void init(DataContainer dataContainer, CacheNotifier notifier) {
         this.dataContainer = dataContainer;
         this.notifier = notifier;
      }

      protected void notifyCommitEntry(boolean created, boolean removed,
            boolean evicted, CacheEntry entry, InvocationContext ctx,
            FlagAffectedCommand command) {
         // Eviction has no notion of pre/post event since 4.2.0.ALPHA4.
         // EvictionManagerImpl.onEntryEviction() triggers both pre and post events
         // with non-null values, so we should do the same here as an ugly workaround.
         if (removed && evicted) {
            notifier.notifyCacheEntryEvicted(
                  entry.getKey(), entry.getValue(), ctx, command);
         } else if (removed) {
            notifier.notifyCacheEntryRemoved(
                  entry.getKey(), null, entry.getValue(), false, ctx, command);
         } else {
            // TODO: We're not very consistent (will JSR-107 solve it?):
            // Current tests expect entry modified to be fired when entry
            // created but not when entry removed

            // Notify entry modified after container has been updated
            notifier.notifyCacheEntryModified(entry.getKey(),
                  entry.getValue(), created, false, ctx, command);

            // Notify entry created event after container has been updated
            if (created)
               notifier.notifyCacheEntryCreated(
                     entry.getKey(), entry.getValue(), false, ctx, command);
         }
      }

      protected final EntryVersionsMap totalOrderCreateNewVersionsAndCheckForWriteSkews(VersionGenerator versionGenerator, TxInvocationContext context,
                                                                                        VersionedPrepareCommand prepareCommand,
                                                                                        WriteSkewHelper.KeySpecificLogic keySpecificLogic) {
         if (context.isOriginLocal()) {
            throw new IllegalStateException("This must not be reached");
         }

         EntryVersionsMap updatedVersionMap = new EntryVersionsMap();

         if (!((TotalOrderPrepareCommand) prepareCommand).skipWriteSkewCheck()) {
            updatedVersionMap = performTotalOrderWriteSkewCheckAndReturnNewVersions(prepareCommand, dataContainer,
                                                                                    versionGenerator, context, keySpecificLogic);
         }

         for (WriteCommand c : prepareCommand.getModifications()) {
            for (Object k : c.getAffectedKeys()) {
               if (keySpecificLogic.performCheckOnKey(k)) {
                  if (!updatedVersionMap.containsKey(k)) {
                     updatedVersionMap.put(k, null);
                  }
               }
            }
         }

         context.getCacheTransaction().setUpdatedEntryVersions(updatedVersionMap);
         return updatedVersionMap;
      }

   }

   /**
    * This logic is used in local mode caches.
    */
   public static class LocalLogic extends AbstractClusteringDependentLogic {

      private EmbeddedCacheManager cacheManager;

      @Inject
      public void init(EmbeddedCacheManager cacheManager) {
         this.cacheManager = cacheManager;
      }

      @Override
      public boolean localNodeIsOwner(Object key) {
         return true;
      }

      @Override
      public boolean localNodeIsPrimaryOwner(Object key) {
         return true;
      }

      @Override
      public Address getPrimaryOwner(Object key) {
         throw new IllegalStateException("Cannot invoke this method for local caches");
      }

      @Override
      public List<Address> getOwners(Collection<Object> keys) {
         return null;
      }

      @Override
      public List<Address> getOwners(Object key) {
         return null;
      }

      @Override
      public Address getAddress() {
         Address address = cacheManager.getAddress();
         if (address == null) {
            address = LOCAL_MODE_ADDRESS;
         }
         return address;
      }

      @Override
      public void commitEntry(CacheEntry entry, Metadata metadata, FlagAffectedCommand command, InvocationContext ctx) {
         // Cache flags before they're reset
         // TODO: Can the reset be done after notification instead?
         boolean created = entry.isCreated();
         boolean removed = entry.isRemoved();
         boolean evicted = entry.isEvicted();

         entry.commit(dataContainer, metadata);

         // Notify after events if necessary
         notifyCommitEntry(created, removed, evicted, entry, ctx, command);
      }

      @Override
      public EntryVersionsMap createNewVersionsAndCheckForWriteSkews(VersionGenerator versionGenerator, TxInvocationContext context, VersionedPrepareCommand prepareCommand) {
         throw new IllegalStateException("Cannot invoke this method for local caches");
      }
   }

   static final Address LOCAL_MODE_ADDRESS = new Address() {
      @Override
      public String toString() {
         return "Local Address";
      }

      @Override
      public int compareTo(Address o) {
         return 0;
      }
   };

   /**
    * This logic is used in invalidation mode caches.
    */
   public static class InvalidationLogic extends AbstractClusteringDependentLogic {

      private StateTransferManager stateTransferManager;
      private RpcManager rpcManager;

      protected static final WriteSkewHelper.KeySpecificLogic keySpecificLogic = new WriteSkewHelper.KeySpecificLogic() {
         @Override
         public boolean performCheckOnKey(Object key) {
            return true;
         }
      };

      @Inject
      public void init(RpcManager rpcManager, StateTransferManager stateTransferManager) {
         this.rpcManager = rpcManager;
         this.stateTransferManager = stateTransferManager;
      }

      @Override
      public boolean localNodeIsOwner(Object key) {
         return stateTransferManager.getCacheTopology().getWriteConsistentHash().isKeyLocalToNode(rpcManager.getAddress(), key);
      }

      @Override
      public boolean localNodeIsPrimaryOwner(Object key) {
         return stateTransferManager.getCacheTopology().getWriteConsistentHash().locatePrimaryOwner(key).equals(rpcManager.getAddress());
      }

      @Override
      public Address getPrimaryOwner(Object key) {
         return stateTransferManager.getCacheTopology().getWriteConsistentHash().locatePrimaryOwner(key);
      }

      @Override
      public void commitEntry(CacheEntry entry, Metadata metadata,
            FlagAffectedCommand command, InvocationContext ctx) {
         // Cache flags before they're reset
         // TODO: Can the reset be done after notification instead?
         boolean created = entry.isCreated();
         boolean removed = entry.isRemoved();
         boolean evicted = entry.isEvicted();

         entry.commit(dataContainer, metadata);

         // Notify after events if necessary
         notifyCommitEntry(created, removed, evicted, entry, ctx, command);
      }

      @Override
      public List<Address> getOwners(Collection<Object> keys) {
         return null;    //todo [anistor] should I actually return this based on current CH?
      }

      @Override
      public List<Address> getOwners(Object key) {
         return null;
      }
      
      @Override
      public Address getAddress() {
         return rpcManager.getAddress();
      }

      @Override
      public EntryVersionsMap createNewVersionsAndCheckForWriteSkews(VersionGenerator versionGenerator, TxInvocationContext context, VersionedPrepareCommand prepareCommand) {
         // In REPL mode, this happens if we are the coordinator.
         CacheTopology cacheTopology = stateTransferManager.getCacheTopology();
         if (prepareCommand.getModifications().length == 0) {
            // For situations when there's a local-only put in the prepare,
            // simply add an empty entry version map. This works because when
            // a local-only put is executed, this is not added to the prepare
            // modification list.
            context.getCacheTransaction().setUpdatedEntryVersions(new EntryVersionsMap());
         } else {
            ConsistentHash readConsistentHash = cacheTopology.getReadConsistentHash();
            List<Address> members = readConsistentHash.getMembers();
            if (members.get(0).equals(rpcManager.getAddress())) {
               // Perform a write skew check on each entry.
               EntryVersionsMap uv = performWriteSkewCheckAndReturnNewVersions(
                     prepareCommand, dataContainer, versionGenerator, context,
                     keySpecificLogic);
               context.getCacheTransaction().setUpdatedEntryVersions(uv);
               return uv;
            }
         }
         return null;
      }
   }

   /**
    * This logic is used in replicated mode caches.
    */
   public static class ReplicationLogic extends InvalidationLogic {

      private StateTransferLock stateTransferLock;
      private Configuration configuration;

      @Inject
      public void init(StateTransferLock stateTransferLock, Configuration configuration) {
         this.stateTransferLock = stateTransferLock;
         this.configuration = configuration;
      }

      @Override
      public void commitEntry(CacheEntry entry, Metadata metadata, FlagAffectedCommand command, InvocationContext ctx) {
         stateTransferLock.acquireSharedTopologyLock();
         try {
            super.commitEntry(entry, metadata, command, ctx);
         } finally {
            stateTransferLock.releaseSharedTopologyLock();
         }
      }

      @Override
      public EntryVersionsMap createNewVersionsAndCheckForWriteSkews(VersionGenerator versionGenerator, TxInvocationContext context, VersionedPrepareCommand prepareCommand) {
         if (configuration.transaction().transactionProtocol().isTotalOrder()) {
            return totalOrderCreateNewVersionsAndCheckForWriteSkews(versionGenerator, context, prepareCommand, keySpecificLogic);
         } else {
            return super.createNewVersionsAndCheckForWriteSkews(versionGenerator, context, prepareCommand);
         }
      }
   }

   /**
    * This logic is used in distributed mode caches.
    */
   public static class DistributionLogic extends AbstractClusteringDependentLogic {

      private DistributionManager dm;
      private Configuration configuration;
      private RpcManager rpcManager;
      private StateTransferLock stateTransferLock;

      private final WriteSkewHelper.KeySpecificLogic keySpecificLogic = new WriteSkewHelper.KeySpecificLogic() {
         @Override
         public boolean performCheckOnKey(Object key) {
            return localNodeIsOwner(key);
         }
      };

      @Inject
      public void init(DistributionManager dm, Configuration configuration,
                       RpcManager rpcManager, StateTransferLock stateTransferLock) {
         this.dm = dm;
         this.configuration = configuration;
         this.rpcManager = rpcManager;
         this.stateTransferLock = stateTransferLock;
      }

      @Override
      public boolean localNodeIsOwner(Object key) {
         return dm.getLocality(key).isLocal();
      }

      @Override
      public Address getAddress() {
         return rpcManager.getAddress();
      }

      @Override
      public boolean localNodeIsPrimaryOwner(Object key) {
         final Address address = rpcManager.getAddress();
         return dm.getPrimaryLocation(key).equals(address);
      }

      @Override
      public Address getPrimaryOwner(Object key) {
         return dm.getPrimaryLocation(key);
      }

      @Override
      public void commitEntry(CacheEntry entry, Metadata metadata, FlagAffectedCommand command, InvocationContext ctx) {
         // Don't allow the CH to change (and state transfer to invalidate entries)
         // between the ownership check and the commit
         stateTransferLock.acquireSharedTopologyLock();
         try {
            boolean doCommit = true;
            // ignore locality for removals, even if skipOwnershipCheck is not true
            boolean skipOwnershipCheck = command != null &&
                  command.hasFlag(Flag.SKIP_OWNERSHIP_CHECK);

            boolean isForeignOwned = !skipOwnershipCheck && !localNodeIsOwner(entry.getKey());
            if (isForeignOwned && !entry.isRemoved()) {
               if (configuration.clustering().l1().enabled()) {
                  // transform for L1
                  if (entry.getLifespan() < 0 || entry.getLifespan() > configuration.clustering().l1().lifespan()) {
                     Metadata newMetadata = entry.getMetadata().builder()
                           .lifespan(configuration.clustering().l1().lifespan())
                           .build();
                     entry.setMetadata(newMetadata);
                  }
               } else {
                  doCommit = false;
               }
            }

            boolean created = false;
            boolean removed = false;
            boolean evicted = false;
            if (!isForeignOwned) {
               created = entry.isCreated();
               removed = entry.isRemoved();
               evicted = entry.isEvicted();
            }

            if (doCommit)
               entry.commit(dataContainer, metadata);
            else
               entry.rollback();

            if (!isForeignOwned) {
               notifyCommitEntry(created, removed, evicted, entry, ctx, command);
            }
         } finally {
            stateTransferLock.releaseSharedTopologyLock();
         }
      }

      @Override
      public List<Address> getOwners(Collection<Object> affectedKeys) {
         if (affectedKeys.isEmpty()) {
            return InfinispanCollections.emptyList();
         }
         return Immutables.immutableListConvert(dm.locateAll(affectedKeys));
      }

      @Override
      public List<Address> getOwners(Object key) {
         return Immutables.immutableListConvert(dm.locate(key));
      }

      @Override
      public EntryVersionsMap createNewVersionsAndCheckForWriteSkews(VersionGenerator versionGenerator, TxInvocationContext context, VersionedPrepareCommand prepareCommand) {
         if (configuration.transaction().transactionProtocol().isTotalOrder()) {
            return totalOrderCreateNewVersionsAndCheckForWriteSkews(versionGenerator, context, prepareCommand, keySpecificLogic);
         }
         // Perform a write skew check on mapped entries.
         EntryVersionsMap uv = performWriteSkewCheckAndReturnNewVersions(prepareCommand, dataContainer,
                                                                         versionGenerator, context,
                                                                         keySpecificLogic);

         CacheTransaction cacheTransaction = context.getCacheTransaction();
         EntryVersionsMap uvOld = cacheTransaction.getUpdatedEntryVersions();
         if (uvOld != null && !uvOld.isEmpty()) {
            uvOld.putAll(uv);
            uv = uvOld;
         }
         cacheTransaction.setUpdatedEntryVersions(uv);
         return (uv.isEmpty()) ? null : uv;
      }
   }
}
