package org.infinispan.commands.remote;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import org.infinispan.commands.CommandsFactory;
import org.infinispan.commands.control.LockControlCommand;
import org.infinispan.commands.read.GetCacheEntryCommand;
import org.infinispan.commons.equivalence.Equivalence;
import org.infinispan.commons.util.EnumUtil;
import org.infinispan.container.InternalEntryFactory;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.container.entries.InternalCacheValue;
import org.infinispan.container.entries.MVCCEntry;
import org.infinispan.context.Flag;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.InvocationContextFactory;
import org.infinispan.interceptors.InterceptorChain;
import org.infinispan.transaction.impl.TransactionTable;
import org.infinispan.transaction.xa.GlobalTransaction;
import org.infinispan.util.ByteString;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * Issues a remote get call.  This is not a {@link org.infinispan.commands.VisitableCommand} and hence not passed up the
 * {@link org.infinispan.interceptors.base.CommandInterceptor} chain.
 * <p/>
 *
 * @author Mircea.Markus@jboss.com
 * @since 4.0
 */
public class ClusteredGetCommand extends LocalFlagAffectedRpcCommand {

   public static final byte COMMAND_ID = 16;
   private static final Log log = LogFactory.getLog(ClusteredGetCommand.class);
   private static final boolean trace = log.isTraceEnabled();

   private Object key;

   private InvocationContextFactory icf;
   private CommandsFactory commandsFactory;
   private InterceptorChain invoker;
   private boolean acquireRemoteLock;
   private GlobalTransaction gtx;

   private TransactionTable txTable;
   private InternalEntryFactory entryFactory;
   private Equivalence keyEquivalence;
   //only used by extended statistics. this boolean is local.
   private boolean isWrite;

   private ClusteredGetCommand() {
      super(null, EnumUtil.EMPTY_BIT_SET); // For command id uniqueness test
   }

   public ClusteredGetCommand(ByteString cacheName) {
      super(cacheName, EnumUtil.EMPTY_BIT_SET);
   }

   public ClusteredGetCommand(Object key, ByteString cacheName, long flags,
                              boolean acquireRemoteLock, GlobalTransaction gtx, Equivalence keyEquivalence) {
      super(cacheName, flags);
      this.key = key;
      this.acquireRemoteLock = acquireRemoteLock;
      this.gtx = gtx;
      this.keyEquivalence = keyEquivalence;
      this.isWrite = false;
      if (acquireRemoteLock && (gtx == null))
         throw new IllegalArgumentException("Cannot have null tx if we need to acquire locks");
   }

   public void initialize(InvocationContextFactory icf, CommandsFactory commandsFactory, InternalEntryFactory entryFactory,
                          InterceptorChain interceptorChain, TransactionTable txTable,
                          Equivalence keyEquivalence) {
      this.icf = icf;
      this.commandsFactory = commandsFactory;
      this.invoker = interceptorChain;
      this.txTable = txTable;
      this.entryFactory = entryFactory;
      this.keyEquivalence = keyEquivalence;
   }

   /**
    * Invokes a logical "get(key)" on a remote cache and returns results.
    *
    * @param context invocation context, ignored.
    * @return returns an <code>CacheEntry</code> or null, if no entry is found.
    */
   @Override
   public InternalCacheValue perform(InvocationContext context) throws Throwable {
      acquireLocksIfNeeded();
      // make sure the get command doesn't perform a remote call
      // as our caller is already calling the ClusteredGetCommand on all the relevant nodes
      long flagBitSet = EnumUtil.bitSetOf(Flag.SKIP_REMOTE_LOOKUP, Flag.CACHE_MODE_LOCAL);
      GetCacheEntryCommand command = commandsFactory.buildGetCacheEntryCommand(key, EnumUtil.mergeBitSets(flagBitSet, getFlagsBitSet()));
      InvocationContext invocationContext = icf.createRemoteInvocationContextForCommand(command, getOrigin());
      CacheEntry cacheEntry = (CacheEntry) invoker.invoke(invocationContext, command);
      if (cacheEntry == null) {
         if (trace) log.trace("Did not find anything, returning null");
         return null;
      }
      //this might happen if the value was fetched from a cache loader
      if (cacheEntry instanceof MVCCEntry) {
         if (trace) log.trace("Handling an internal cache entry...");
         MVCCEntry mvccEntry = (MVCCEntry) cacheEntry;
         return entryFactory.createValue(mvccEntry);
      } else {
         InternalCacheEntry internalCacheEntry = (InternalCacheEntry) cacheEntry;
         return internalCacheEntry.toInternalCacheValue();
      }
   }

   public GlobalTransaction getGlobalTransaction() {
      return gtx;
   }

   private void acquireLocksIfNeeded() throws Throwable {
      if (acquireRemoteLock) {
         LockControlCommand lockControlCommand = commandsFactory.buildLockControlCommand(key, getFlagsBitSet(), gtx);
         lockControlCommand.init(invoker, icf, txTable);
         lockControlCommand.perform(null);
      }
   }

   @Override
   public byte getCommandId() {
      return COMMAND_ID;
   }

   @Override
   public void writeTo(ObjectOutput output) throws IOException {
      output.writeObject(key);
      output.writeLong(Flag.copyWithoutRemotableFlags(getFlagsBitSet()));
      output.writeBoolean(acquireRemoteLock);
      if (acquireRemoteLock) {
         output.writeObject(gtx);
      }
   }

   @Override
   public void readFrom(ObjectInput input) throws IOException, ClassNotFoundException {
      key = input.readObject();
      setFlagsBitSet(input.readLong());
      acquireRemoteLock = input.readBoolean();
      if (acquireRemoteLock) {
         gtx = (GlobalTransaction) input.readObject();
      }
   }

   @Override
   public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      ClusteredGetCommand that = (ClusteredGetCommand) o;

      return !(key != null ?
         !(keyEquivalence != null ? keyEquivalence.equals(key, that.key) : key.equals(that.key))
         : that.key != null);
   }

   @Override
   public int hashCode() {
      int result;
      result = (key != null
          ? (keyEquivalence != null ? keyEquivalence.hashCode(key) : key.hashCode())
          : 0);
      return result;
   }

   @Override
   public String toString() {
      return new StringBuilder()
         .append("ClusteredGetCommand{key=")
         .append(key)
         .append(", flags=").append(printFlags())
         .append("}")
         .toString();
   }

   public boolean isWrite() {
      return isWrite;
   }

   public void setWrite(boolean write) {
      isWrite = write;
   }

   public Object getKey() {
      return key;
   }

   @Override
   public boolean isReturnValueExpected() {
      return true;
   }

   @Override
   public boolean canBlock() {
      return false;
   }
}
