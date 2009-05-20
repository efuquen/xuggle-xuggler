/*
 * Copyright (c) 2008, 2009 by Xuggle Incorporated. All rights reserved.
 * 
 * This file is part of Xuggler.
 * 
 * You can redistribute Xuggler and/or modify it under the terms of the GNU
 * Affero General Public License as published by the Free Software Foundation,
 * either version 3 of the License, or (at your option) any later version.
 * 
 * Xuggler is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with Xuggler. If not, see <http://www.gnu.org/licenses/>.
 */

package com.xuggle.ferry;

import java.lang.ref.ReferenceQueue;
import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Internal Only.
 * <p>
 * This class is used by the JNI Memory system as the root for our own garbage
 * collector.
 * </p>
 * 
 * @author aclarke
 * 
 */
public class JNIMemoryManager
{
  static final private JNIMemoryManager mMgr = new JNIMemoryManager();
  
  /**
   * Get the global {@link JNIMemoryManager} being used.
   * @return the manager
   */
  static public JNIMemoryManager getMgr()
  {
    return mMgr;
  }
  
  /**
   * A convenience way to call {@link #getMgr()}.{@link #gc()}
   */
  static public void collect()
  {
    getMgr().gc();
  }
  
  private static final Logger log = LoggerFactory.getLogger(
      JNIMemoryManager.class);

  private final ReferenceQueue<Object> mRefQueue;
  private final Set<JNIReference> mRefList;

  private Thread mCollectionThread;

  /**
   * The constructor is package level so others can't create it.
   */
  JNIMemoryManager()
  {
    mRefQueue = new ReferenceQueue<Object>();
    mRefList = new HashSet<JNIReference>();
    mCollectionThread = null;
  }

  /**
   * Get the underlying queue we track references with.
   * 
   * @return The queue.
   */
  ReferenceQueue<Object> getQueue()
  {
    return mRefQueue;
  }

  /**
   * Get the number of Ferry's objects we believe are still pinned (i.e. in
   * use).
   * 
   * @return number of ferry objects in use.
   */
  public long getNumPinnedObjects()
  {
    long result;
    synchronized (mRefList)
    {
      result = mRefList.size();
    }
    return result;
  }

  /**
   * A finalizer for the memory manager itself. It just calls internal garbage
   * collections and then exists.
   * 
   * This may end up "leaking" some memory if all Ferry objects have not
   * otherwise been collected, but this is not a huge problem for most
   * applications.
   */
  public void finalize()
  {
    log.trace("destroying: {}", this);
    gc();
    synchronized (mRefList)
    {
      mRefList.clear();
    }
    gc();
  }

  /**
   * Add a reference to the set of references we'll collect.
   * 
   * @param ref
   *          The reference to collect.
   * @return true if already in list; false otherwise.
   */
  boolean addReference(JNIReference ref)
  {
    boolean result = false;
    synchronized (mRefList)
    {
      result = mRefList.add(ref);
    }
    return result;
  }

  /**
   * Remove this reference from the set of references we're tracking, and
   * collect it.
   * 
   * @param ref
   *          The reference to remove
   * @return true if the reference was in the queue; false if not.
   */
  boolean removeReference(JNIReference ref)
  {
    boolean result = false;
    synchronized (mRefList)
    {
      result = mRefList.remove(ref);
    }
    return result;
  }

  /**
   * Do a Xuggle Ferry Garbage Collection.
   * 
   * This takes all Ferry objects that are no longer reachable and deletes the
   * underlying native memory. It is called every time you allocate a new Ferry
   * object to ensure we're freeing up native objects as soon as possible
   * (rather than waiting for the potentially slow finalizer). It is also called
   * via a finalizer on an object that is referenced by the Ferry'ed object
   * (that way, the earlier of the next Ferry allocation, or the finalizer
   * thread, frees up unused native memory).
   */
  public void gc()
  {
    JNIReference ref = null;
    while ((ref = (JNIReference) mRefQueue.poll()) != null)
    {
      ref.delete();
    }
  }

  /**
   * Starts a new Ferry collection thread that will wake up whenever
   * a memory reference needs clean-up from native code.
   * <p>
   * This thread is not started by default as Xuggler calls
   * {@link #gc()} internally whenever a new Xuggler object
   * is allocated.  But if you're caching Xuggler objects
   * and hence avoiding new allocations, you may want to call this
   * to ensure all objects are promptly collected.
   * </p>
   * <p>
   * This call is ignored if the collection thread is already running.
   * </p>
   * <p>
   * The thread can be stopped by calling {@link #stopCollectionThread()},
   * and will also exit if interrupted by Java.
   * </p>
   */
  public void startCollectionThread()
  {
    synchronized (this)
    {
      if (mCollectionThread != null)
        throw new RuntimeException("Thread already running");

      mCollectionThread = new Thread(new Runnable()
      {

        public void run()
        {
          JNIReference ref = null;
          try
          {
            while (true)
            {
              ref = (JNIReference) mRefQueue.remove();
              if (ref != null)
                ref.delete();
            }
          }
          catch (InterruptedException ex)
          {
            synchronized (JNIMemoryManager.this)
            {
              mCollectionThread = null;
            }
            return;
          }

        }
      }, "Xuggle Ferry Collection Thread");
      mCollectionThread.setDaemon(true);
      mCollectionThread.start();
    }
  }
  
  /**
   * Stops the Ferry collection thread if running.  This does
   * nothing if no collection thread is running.
   */
  public void stopCollectionThread()
  {
    synchronized(this) {
      if (mCollectionThread != null)
        mCollectionThread.interrupt();
    }
  }
  

}
