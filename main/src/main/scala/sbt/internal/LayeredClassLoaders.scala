/*
 * sbt
 * Copyright 2011 - 2018, Lightbend, Inc.
 * Copyright 2008 - 2010, Mark Harrah
 * Licensed under Apache License 2.0 (see LICENSE)
 */

package sbt.internal

import java.io.File
import java.net.{ URL, URLClassLoader }
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }

import sbt.io.IO
import sbt.util.Logger

import scala.collection.JavaConverters._

/**
 * A simple ClassLoader that copies native libraries to a temporary directory before loading them.
 * Otherwise the same as a normal URLClassLoader.
 * @param classpath the classpath of the url
 * @param parent the parent loader
 * @param tempDir the directory into which native libraries are copied before loading
 */
private[internal] class LayeredClassLoaderImpl(
    classpath: Seq[File],
    parent: ClassLoader,
    tempDir: File,
    allowZombies: Boolean,
    logger: Logger
) extends ManagedClassLoader(classpath.toArray.map(_.toURI.toURL), parent, allowZombies, logger) {
  setTempDir(tempDir)
}

/**
 * This classloader doesn't load any classes. It is able to create a two layer bundled ClassLoader
 * that is able to load the full project classpath. The top layer is able to directly load the
 * project dependencies. The bottom layer can load the full classpath of the run or test task.
 * If the top layer needs to load a class from the bottom layer via java reflection, we facilitate
 * that with the `ReverseLookupClassLoader`.
 *
 *
 * This holder caches the ReverseLookupClassLoader, which is the top loader in this hierarchy. The
 * checkout method will get the RevereLookupClassLoader from the cache or make a new one if
 * none is available. It will only cache at most one so if multiple concurrently tasks have the
 * same dependency classpath, multiple instances of ReverseLookupClassLoader will be created for
 * the classpath. If the ReverseLookupClassLoader makes a lookup in the BottomClassLoader, it
 * invalidates itself and will not be cached when it is returned.
 *
 * The reason it is a ClassLoader even though it can't load any classes is so its
 * lifecycle -- and therefore the lifecycle of its cache entry -- is managed by the
 * ClassLoaderCache, allowing the cache entry to be evicted under memory pressure.
 *
 * @param classpath the dependency classpath of the managed loader
 * @param parent the parent ClassLoader of the managed loader
 */
private[internal] final class ReverseLookupClassLoaderHolder(
    val classpath: Seq[File],
    val parent: ClassLoader,
    val allowZombies: Boolean,
    val logger: Logger
) extends URLClassLoader(Array.empty, null) {
  private[this] val cached: AtomicReference[ReverseLookupClassLoader] = new AtomicReference
  private[this] val closed = new AtomicBoolean(false)
  private[this] val urls = classpath.map(_.toURI.toURL).toArray

  /**
   * Get a classloader. If there is a loader available in the cache, it will use that loader,
   * otherwise it makes a new classloader.
   *
   * @return a ClassLoader
   */
  def checkout(fullClasspath: Seq[File], tempDir: File): ClassLoader = {
    if (closed.get()) {
      val msg = "Tried to extract class loader from closed ReverseLookupClassLoaderHolder. " +
        "Try running the `clearCaches` command and re-trying."
      throw new IllegalStateException(msg)
    }
    val reverseLookupClassLoader = cached.getAndSet(null) match {
      case null => new ReverseLookupClassLoader
      case c    => c
    }
    reverseLookupClassLoader.setup(tempDir, fullClasspath)
    new BottomClassLoader(fullClasspath, reverseLookupClassLoader, tempDir)
  }

  private def checkin(reverseLookupClassLoader: ReverseLookupClassLoader): Unit = {
    if (reverseLookupClassLoader.isDirty) reverseLookupClassLoader.close()
    else {
      if (closed.get()) reverseLookupClassLoader.close()
      else
        cached.getAndSet(reverseLookupClassLoader) match {
          case null => if (closed.get) reverseLookupClassLoader.close()
          case c    => c.close()
        }
    }
  }
  override def close(): Unit = {
    closed.set(true)
    cached.get() match {
      case null =>
      case c    => c.close()
    }
  }

  private class ClassLoadingLock {
    private[this] val locks = new ConcurrentHashMap[String, AnyRef]()
    def withLock[R](name: String)(f: => R): R = {
      val newLock = new AnyRef
      val lock = locks.synchronized(locks.put(name, newLock) match {
        case null => newLock
        case l    => l
      })
      try lock.synchronized(f)
      finally locks.synchronized {
        locks.remove(name)
        ()
      }
    }
  }

  /**
   * A ClassLoader for the dependency layer of a run or test task. It is almost a normal
   * URLClassLoader except that it has the ability to look one level down the classloading
   * hierarchy to load a class via reflection that is not directly available to it. The ClassLoader
   * that is below it in the hierarchy must be registered via setDescendant. If it ever loads a
   * class from its descendant, then it cannot be used in a subsequent run because it will not be
   * possible to reload that class.
   *
   * The descendant classloader checks it in and out via [[checkout]] and [[checkin]]. When it
   * returns the loader via [[checkin]], if the loader is dirty, we close it. Otherwise we
   * cache it if there is no existing cache entry.
   *
   * Performance degrades if loadClass is constantly looking back up to the provided
   * BottomClassLoader so we provide an alternate loadClass definition that takes a reverseLookup
   * boolean parameter. Because the [[BottomClassLoader]] knows what loader is calling into, when
   * it delegates its search to the ReverseLookupClassLoader, it passes false for the reverseLookup
   * flag. By default the flag is true. Most of the time, the default loadClass will only be
   * invoked by java reflection calls. Even then, there's some chance that the class being loaded
   * by java reflection is _not_ available on the bottom classpath so it is not guaranteed that
   * performing the reverse lookup will invalidate this loader.
   *
   */
  private class ReverseLookupClassLoader
      extends ManagedClassLoader(urls, parent, allowZombies, logger)
      with NativeLoader {
    override def getURLs: Array[URL] = urls
    private[this] val directDescendant: AtomicReference[BottomClassLoader] =
      new AtomicReference
    private[this] val dirty = new AtomicBoolean(false)
    private[this] val classLoadingLock = new ClassLoadingLock
    def isDirty: Boolean = dirty.get()
    def setDescendant(classLoader: BottomClassLoader): Unit = directDescendant.set(classLoader)
    private[this] val resourceLoader = new AtomicReference[ResourceLoader](null)
    private class ResourceLoader(cp: Seq[File])
        extends URLClassLoader(cp.map(_.toURI.toURL).toArray, parent) {
      def lookup(name: String): URL = findResource(name)
    }
    private[ReverseLookupClassLoaderHolder] def setup(tmpDir: File, cp: Seq[File]): Unit = {
      setTempDir(tmpDir)
      resourceLoader.set(new ResourceLoader(cp))
    }
    override def findResource(name: String): URL = resourceLoader.get() match {
      case null => null
      case l    => l.lookup(name)
    }
    def loadClass(name: String, resolve: Boolean, reverseLookup: Boolean): Class[_] = {
      classLoadingLock.withLock(name) {
        try super.loadClass(name, resolve)
        catch {
          case e: ClassNotFoundException if reverseLookup =>
            directDescendant.get match {
              case null => throw e
              case cl =>
                val res = cl.lookupClass(name)
                dirty.set(true)
                res
            }
        }
      }
    }
    override def loadClass(name: String, resolve: Boolean): Class[_] =
      loadClass(name, resolve, reverseLookup = true)
  }

  /**
   * The bottom most layer in our layering hierarchy. This layer should never be cached. The
   * dependency layer may need access to classes only available at this layer using java
   * reflection. To make this work, we register this loader with the parent in its
   * constructor. We also add the lookupClass method which gives ReverseLookupClassLoader
   * a public interface to findClass.
   *
   * To improve performance, when loading classes from the parent, we call the loadClass
   * method with the reverseLookup flag set to false. This prevents the ReverseLookupClassLoader
   * from trying to call back into this loader when it can't find a particular class.
   *
   * @param dynamicClasspath the classpath for the run or test task excluding the dependency jars
   * @param parent the ReverseLookupClassLoader with which this loader needs to register itself
   *               so that reverse lookups required by java reflection will work
   * @param tempDir the temp directory to copy native libraries
   */
  private class BottomClassLoader(
      dynamicClasspath: Seq[File],
      parent: ReverseLookupClassLoader,
      tempDir: File
  ) extends ManagedClassLoader(
        dynamicClasspath.map(_.toURI.toURL).toArray,
        parent,
        allowZombies,
        logger
      )
      with NativeLoader {
    parent.setDescendant(this)
    setTempDir(tempDir)
    val classLoadingLock = new ClassLoadingLock

    final def lookupClass(name: String): Class[_] = findClass(name)

    override def findClass(name: String): Class[_] = {
      findLoadedClass(name) match {
        case null =>
          classLoadingLock.withLock(name) {
            findLoadedClass(name) match {
              case null => super.findClass(name)
              case c    => c
            }
          }
        case c => c
      }
    }
    override def loadClass(name: String, resolve: Boolean): Class[_] = {
      val clazz = findLoadedClass(name) match {
        case null =>
          val c = try parent.loadClass(name, resolve = false, reverseLookup = false)
          catch { case _: ClassNotFoundException => findClass(name) }
          if (resolve) resolveClass(c)
          c
        case c => c
      }
      if (resolve) resolveClass(clazz)
      clazz
    }
    override def close(): Unit = {
      checkin(parent)
      super.close()
    }
  }
}

/**
 * This is more or less copied from the NativeCopyLoader in zinc. It differs from the zinc
 * NativeCopyLoader in that it doesn't allow any explicit mappings and it allows the tempDir
 * to be dynamically reset. The explicit mappings feature isn't used by sbt. The dynamic
 * temp directory use case is needed in some layered class loading scenarios.
 */
private trait NativeLoader extends ClassLoader with AutoCloseable {
  private[this] val mapped = new ConcurrentHashMap[String, String]
  private[this] val searchPaths =
    sys.props.get("java.library.path").map(IO.parseClasspath).getOrElse(Nil)
  private[this] val tempDir = new AtomicReference(new File("/dev/null"))

  abstract override def close(): Unit = {
    setTempDir(new File("/dev/null"))
    super.close()
  }
  override protected def findLibrary(name: String): String = synchronized {
    mapped.get(name) match {
      case null =>
        findLibrary0(name) match {
          case null => null
          case n =>
            mapped.put(name, n)
            NativeLibs.addNativeLib(n)
            n
        }
      case n => n
    }
  }
  private[internal] def setTempDir(file: File): Unit = {
    deleteNativeLibs()
    tempDir.set(file)
  }
  private[this] def deleteNativeLibs(): Unit = {
    mapped.values().forEach(NativeLibs.delete)
    mapped.clear()
  }
  private[this] def findLibrary0(name: String): String = {
    val mappedName = System.mapLibraryName(name)
    val search = searchPaths.toStream flatMap relativeLibrary(mappedName)
    search.headOption.map(copy).orNull
  }
  private[this] def relativeLibrary(mappedName: String)(base: File): Seq[File] = {
    val f = new File(base, mappedName)
    if (f.isFile) f :: Nil else Nil
  }
  private[this] def copy(f: File): String = {
    val target = new File(tempDir.get(), f.getName)
    IO.copyFile(f, target)
    target.getAbsolutePath
  }
}

private[internal] object NativeLibs {
  private[this] val nativeLibs = new java.util.HashSet[File].asScala
  ShutdownHooks.add(() => {
    nativeLibs.foreach(IO.delete)
    IO.deleteIfEmpty(nativeLibs.map(_.getParentFile).toSet)
    nativeLibs.clear()
  })
  def addNativeLib(lib: String): Unit = {
    nativeLibs.add(new File(lib))
    ()
  }
  def delete(lib: String): Unit = {
    val file = new File(lib)
    nativeLibs.remove(file)
    file.delete()
    ()
  }
}

private sealed abstract class ManagedClassLoader(
    urls: Array[URL],
    parent: ClassLoader,
    allowZombies: Boolean,
    logger: Logger
) extends URLClassLoader(urls, parent)
    with NativeLoader {
  private[this] val closed = new AtomicBoolean(false)
  private[this] val printedWarning = new AtomicBoolean(false)
  private[this] val zombieLoader = new AtomicReference[ZombieClassLoader]
  private class ZombieClassLoader extends URLClassLoader(urls, this) {
    def lookupClass(name: String): Class[_] =
      try findClass(name)
      catch {
        case e: ClassNotFoundException =>
          val deleted = urls.flatMap { u =>
            val f = new File(u.getPath)
            if (f.exists) None else Some(f)
          }
          if (deleted.toSeq.nonEmpty) {
            // TODO - add doc link
            val msg = s"Couldn't load class $name. " +
              s"The following urls on the classpath do not exist:\n${deleted mkString "\n"}\n" +
              "This may be due to shutdown hooks added during an invocation of `run`."
            // logging may be shutdown at this point so we need to print directly to System.err.
            System.err.println(msg)
          }
          throw e
      }
  }
  private def getZombieLoader(name: String): ZombieClassLoader = {
    if (printedWarning.compareAndSet(false, true) && !allowZombies) {
      // TODO - Need to add link to documentation in website
      val thread = Thread.currentThread
      val msg =
        s"$thread loading $name after test or run has completed. This is a likely resource leak."
      logger.warn(msg)
    }
    zombieLoader.get match {
      case null =>
        val zb = new ZombieClassLoader
        zombieLoader.set(zb)
        zb
      case zb => zb
    }
  }
  override def findResource(name: String): URL = {
    if (closed.get) getZombieLoader(name).findResource(name)
    else super.findResource(name)
  }
  override def findClass(name: String): Class[_] = {
    if (closed.get) getZombieLoader(name).lookupClass(name)
    else super.findClass(name)
  }
  override def close(): Unit = {
    closed.set(true)
    Option(zombieLoader.getAndSet(null)).foreach(_.close())
    super.close()
  }
}
