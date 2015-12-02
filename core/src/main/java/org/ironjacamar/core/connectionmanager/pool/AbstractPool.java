/*
 * IronJacamar, a Java EE Connector Architecture implementation
 * Copyright 2015, Red Hat Inc, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the Eclipse Public License 1.0 as
 * published by the Free Software Foundation.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the Eclipse
 * Public License for more details.
 *
 * You should have received a copy of the Eclipse Public License 
 * along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.ironjacamar.core.connectionmanager.pool;

import org.ironjacamar.core.api.connectionmanager.pool.PoolConfiguration;
import org.ironjacamar.core.connectionmanager.ConnectionManager;
import org.ironjacamar.core.connectionmanager.Credential;
import org.ironjacamar.core.connectionmanager.TransactionalConnectionManager;
import org.ironjacamar.core.connectionmanager.listener.ConnectionListener;
import org.ironjacamar.core.spi.transaction.TxUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import javax.resource.ResourceException;
import javax.resource.spi.TransactionSupport.TransactionSupportLevel;
import javax.transaction.Transaction;

/**
 * The base class for all pool implementations
 * @author <a href="jesper.pedersen@ironjacamar.org">Jesper Pedersen</a>
 */
public abstract class AbstractPool implements Pool
{
   /** The connection manager */
   protected ConnectionManager cm;

   /** The pool configuration */
   protected PoolConfiguration poolConfiguration;

   /** The pools */
   protected ConcurrentHashMap<Credential, ManagedConnectionPool> pools;
   
   /** The transaction map */
   protected ConcurrentHashMap<Object, Map<ManagedConnectionPool, ConnectionListener>> transactionMap;
   
   /** The semaphore */
   protected Semaphore semaphore;
   
   /**
    * Constructor
    * @param cm The connection manager
    * @param pc The pool configuration
    */
   public AbstractPool(ConnectionManager cm, PoolConfiguration pc)
   {
      this.cm = cm;
      this.poolConfiguration = pc;
      this.pools = new ConcurrentHashMap<Credential, ManagedConnectionPool>();
      this.transactionMap = new ConcurrentHashMap<Object, Map<ManagedConnectionPool, ConnectionListener>>();
      this.semaphore = new Semaphore(poolConfiguration.getMaxSize());
   }

   /**
    * {@inheritDoc}
    */
   public PoolConfiguration getConfiguration()
   {
      return poolConfiguration;
   }

   /**
    * {@inheritDoc}
    */
   public ConnectionListener getConnectionListener(Credential credential)
      throws ResourceException
   {
      ConnectionListener cl = null;
      ManagedConnectionPool mcp = pools.get(credential);

      if (mcp == null)
      {
         synchronized (this)
         {
            mcp = pools.get(credential);

            if (mcp == null)
            {
               ManagedConnectionPool newMcp = createManagedConnectionPool(credential);
               mcp = pools.putIfAbsent(credential, newMcp);
               if (mcp == null)
               {
                  mcp = newMcp;
               }
               else
               {
                  newMcp.shutdown();
               }
            }
         }
      }

      if (cm.getTransactionSupport() == TransactionSupportLevel.LocalTransaction ||
          cm.getTransactionSupport() == TransactionSupportLevel.XATransaction)
      {
         try
         {
            TransactionalConnectionManager txCM = (TransactionalConnectionManager)cm;
            Transaction tx = txCM.getTransactionIntegration().getTransactionManager().getTransaction();

            if (TxUtils.isUncommitted(tx))
            {
               Object id = txCM.getTransactionIntegration().getTransactionSynchronizationRegistry().getTransactionKey();

               Map<ManagedConnectionPool, ConnectionListener> currentMap = transactionMap.get(id);

               if (currentMap == null)
               {
                  Map<ManagedConnectionPool, ConnectionListener> map =
                     new HashMap<ManagedConnectionPool, ConnectionListener>();

                  currentMap = transactionMap.putIfAbsent(id, map);
                  if (currentMap == null)
                  {
                     currentMap = map;
                  }
               }

               cl = currentMap.get(mcp);

               if (cl == null)
               {
                  if (TxUtils.isActive(tx))
                  {
                     cl = mcp.getConnectionListener();

                     currentMap.put(mcp, cl);

                     txCM.getTransactionIntegration().getTransactionSynchronizationRegistry().
                        registerInterposedSynchronization(new TransactionMapCleanup(id, transactionMap));
                  }
                  else
                  {
                     throw new ResourceException();
                  }
               }
            }
         }
         catch (ResourceException re)
         {
            throw re;
         }
         catch (Exception e)
         {
            throw new ResourceException(e);
         }
      }

      if (cl == null)
         cl = mcp.getConnectionListener();

      return cl;
   }

   /**
    * {@inheritDoc}
    */
   public void returnConnectionListener(ConnectionListener cl, boolean kill) throws ResourceException
   {
      ManagedConnectionPool mcp = pools.get(cl.getCredential());
      mcp.returnConnectionListener(cl, kill);
   }

   /**
    * {@inheritDoc}
    */
   public boolean isFull()
   {
      return semaphore.availablePermits() == 0;
   }

   /**
    * {@inheritDoc}
    */
   public synchronized void shutdown()
   {
      for (ManagedConnectionPool mcp : pools.values())
         mcp.shutdown();

      pools.clear();
   }

   /**
    * Create a connection listener
    * @param credential The credential
    * @return The connection listener
    * @exception ResourceException Thrown if the connection listener cannot be created
    */
   protected abstract ConnectionListener createConnectionListener(Credential credential) throws ResourceException;

   /**
    * Destroy a connection listener
    * @param cl The connection listener
    * @exception ResourceException Thrown if the connection listener cannot be destroed
    */
   protected abstract void destroyConnectionListener(ConnectionListener cl) throws ResourceException;

   /**
    * Create a new managed connection pool instance
    * @param credential The credential
    * @return The instance
    */
   protected abstract ManagedConnectionPool createManagedConnectionPool(Credential credential);
}
