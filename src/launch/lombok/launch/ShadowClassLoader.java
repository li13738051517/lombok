/*
 * Copyright (C) 2014 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.launch;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.WeakHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The shadow classloader serves to completely hide almost all classes in a given jar file by using a different file ending.
 * 
 * The shadow classloader also serves to link in a project as it is being developed (a 'bin' dir from an IDE for example).
 * <p>
 * Classes loaded by the shadowloader use ".SCL.<em>sclSuffix</em>" in addition to ".class". In other words, most of the class files in a given jar end in this suffix, which
 * serves to hide them from any tool that isn't aware of the suffix (such as IDEs generating auto-complete dialogs, and javac's classpath in general). Only shadowloader can actually
 * load these classes.
 * <p>
 * The shadowloader will pick up an alternate (priority) classpath, using normal class files, from the system property "<code>shadow.override.<em>sclSuffix</em></code>".
 * This shadow classpath looks just like a normal java classpath; the path separator is applied (semi-colon on windows, colon elsewhere), and entries can consist of directories,
 * jar files, or directories ending in "/*" to pick up all jars inside it.
 * <p>
 * Load order is as follows if at least one override is present:
 * <li>First, if the resource is found in one of the paths stated in the shadow classpath, find that.
 * <li>Next, ask the <code>parent</code> loader, which is passed during construction of the ShadowClassLoader.
 * <li>Notably, this jar's contents are always skipped! (The idea of the shadow classpath is that this jar only functions as a launcher, not as a source of your actual application).
 * </ul>
 * 
 * If no overrides are present, the load order is as follows:
 * <li>First, if the resource is found in our own jar (trying ".SCL.<em>sclSuffix</em>" first for any resource request ending in ".class"), return that.
 * <li>Next, ask the <code>parent</code> loader.
 * </ul>
 * 
 * Use ShadowClassLoader to accomplish the following things:<ul>
 * <li>Avoid contaminating the namespace of any project using an SCL-based jar. Autocompleters in IDEs will NOT suggest anything other than actual public API.
 * <li>Like jarjar, allows folding in dependencies such as ASM without foisting these dependencies on projects that use this jar. shadowloader obviates the need for jarjar.
 * <li>Allows an agent (which MUST be in jar form) to still load everything except this loader infrastructure from class files generated by the IDE, which should
 * considerably help debugging, as you can now rely on the IDE's built-in auto-recompile features instead of having to run a full build everytime, and it should help
 * with hot code replace and the like (this is what the {@code shadow.override} feature is for).
 * </ul>
 * 
 * Implementation note: {@code lombok.eclipse.agent.EclipseLoaderPatcher} <em>relies</em> on this class having no dependencies on any other class except the JVM boot class, notably
 * including any other classes in this package, <strong>including</strong> inner classes. So, don't write closures, anonymous inner class literals,
 * enums, or anything else that could cause the compilation of this file to produce more than 1 class file. In general, actually passing load control to this loader is a bit tricky
 * so ensure that this class has zero dependencies on anything except java core classes.
 */
class ShadowClassLoader extends ClassLoader {
	private static final String SELF_NAME = "lombok/launch/ShadowClassLoader.class";
	private final String SELF_BASE;
	private final File SELF_BASE_FILE;
	private final int SELF_BASE_LENGTH;
	
	private final List<File> override = new ArrayList<File>();
	private final String sclSuffix;
	private final List<String> parentExclusion = new ArrayList<String>();
	
	/**
	 * Calls the {@link ShadowClassLoader(ClassLoader, String, String, String[]) constructor with no exclusions and the source of this class as base.
	 */
	ShadowClassLoader(ClassLoader source, String sclSuffix) {
		this(source, sclSuffix, null);
	}
	
	/**
	 * @param source The 'parent' classloader.
	 * @param sclSuffix The suffix of the shadowed class files in our own jar. For example, if this is {@code lombok}, then the class files in your jar should be {@code foo/Bar.SCL.lombok} and not {@code foo/Bar.class}.
	 * @param selfBase The (preferably absolute) path to our own jar. This jar will be searched for class/SCL.sclSuffix files.
	 * @param parentExclusion For example {@code "lombok."}; upon invocation of loadClass of this loader, the parent loader ({@code source}) will NOT be invoked if the class to be loaded begins with anything in the parent exclusion list. No exclusion is applied for getResource(s).
	 */
	ShadowClassLoader(ClassLoader source, String sclSuffix, String selfBase, String... parentExclusion) {
		super(source);
		this.sclSuffix = sclSuffix;
		if (parentExclusion != null) for (String pe : parentExclusion) {
			pe = pe.replace(".", "/");
			if (!pe.endsWith("/")) pe = pe + "/";
			this.parentExclusion.add(pe);
		}
		
		if (selfBase != null) {
			SELF_BASE = selfBase;
			SELF_BASE_LENGTH = selfBase.length();
		} else {
			String sclClassUrl = ShadowClassLoader.class.getResource("ShadowClassLoader.class").toString();
			if (!sclClassUrl.endsWith(SELF_NAME)) throw new InternalError("ShadowLoader can't find itself.");
			SELF_BASE_LENGTH = sclClassUrl.length() - SELF_NAME.length();
			SELF_BASE = sclClassUrl.substring(0, SELF_BASE_LENGTH);
		}
		
		if (SELF_BASE.startsWith("jar:file:") && SELF_BASE.endsWith("!/")) SELF_BASE_FILE = new File(SELF_BASE.substring(9, SELF_BASE.length() - 2));
		else if (SELF_BASE.startsWith("file:")) SELF_BASE_FILE = new File(SELF_BASE.substring(5));
		else SELF_BASE_FILE = new File(SELF_BASE);
		String scl = System.getProperty("shadow.override." + sclSuffix);
		if (scl != null && !scl.isEmpty()) {
			for (String part : scl.split("\\s*" + (File.pathSeparatorChar == ';' ? ";" : ":") + "\\s*")) {
				if (part.endsWith("/*") || part.endsWith(File.separator + "*")) {
					addOverrideJarDir(part.substring(0, part.length() - 2));
				} else {
					addOverrideClasspathEntry(part);
				}
			}
		}
	}
	
	private static final String EMPTY_MARKER = new String("--EMPTY JAR--");
	private Map<String, Object> jarContentsCacheTrackers = new HashMap<String, Object>();
	private static WeakHashMap<Object, String> trackerCache = new WeakHashMap<Object, String>();
	private static WeakHashMap<Object, List<String>> jarContentsCache = new WeakHashMap<Object, List<String>>();
	
	/**
	 * This cache ensures that any given jar file is only opened once in order to determine the full contents of it.
	 * We use 'trackers' to make sure that the bulk of the memory taken up by this cache (the list of strings representing the content of a jar file)
	 * gets garbage collected if all ShadowClassLoaders that ever tried to request a listing of this jar file, are garbage collected.
	 */
	private List<String> getOrMakeJarListing(String absolutePathToJar) {
		List<String> list = retrieveFromCache(absolutePathToJar);
		synchronized (list) {
			if (list.isEmpty()) {
				try {
					JarFile jf = new JarFile(absolutePathToJar);
					try {
						Enumeration<JarEntry> entries = jf.entries();
						while (entries.hasMoreElements()) {
							JarEntry jarEntry = entries.nextElement();
							if (!jarEntry.isDirectory()) list.add(jarEntry.getName());
						}
					} finally {
						jf.close();
					}
				} catch (Exception ignore) {}
				if (list.isEmpty()) list.add(EMPTY_MARKER);
			}
		}
		
		if (list.size() == 1 && list.get(0) == EMPTY_MARKER) return Collections.emptyList();
		return list;
	}
	
	private List<String> retrieveFromCache(String absolutePathToJar) {
		synchronized (trackerCache) {
			Object tracker = jarContentsCacheTrackers.get(absolutePathToJar);
			if (tracker != null) return jarContentsCache.get(tracker);
			
			for (Map.Entry<Object, String> entry : trackerCache.entrySet()) {
				if (entry.getValue().equals(absolutePathToJar)) {
					tracker = entry.getKey();
					break;
				}
			}
			List<String> result = null;
			if (tracker != null) result = jarContentsCache.get(tracker);
			if (result != null) return result;
			
			tracker = new Object();
			List<String> list = new ArrayList<String>();
			jarContentsCache.put(tracker, list);
			trackerCache.put(tracker, absolutePathToJar);
			jarContentsCacheTrackers.put(absolutePathToJar, tracker);
			return list;
		}
	}
	
	/**
	 * Looks up {@code altName} in {@code location}, and if that isn't found, looks up {@code name}; {@code altName} can be null in which case it is skipped.
	 */
	private URL getResourceFromLocation(String name, String altName, File location) {
		if (location.isDirectory()) {
			try {
				if (altName != null) {
					File f = new File(location, altName);
					if (f.isFile() && f.canRead()) return f.toURI().toURL();
				}
				
				File f = new File(location, name);
				if (f.isFile() && f.canRead()) return f.toURI().toURL();
				return null;
			} catch (MalformedURLException e) {
				return null;
			}
		}
		
		if (!location.isFile() || !location.canRead()) return null;
		
		String absolutePath; {
			try {
				absolutePath = location.getCanonicalPath();
			} catch (Exception e) {
				absolutePath = location.getAbsolutePath();
			}
		}
		List<String> jarContents = getOrMakeJarListing(absolutePath);
		
		try {
			if (jarContents.contains(altName)) {
				return new URI("jar:file:" + absolutePath + "!/" + altName).toURL();
			}
		} catch (Exception e) {}
		
		try {
			if (jarContents.contains(name)) {
				return new URI("jar:file:" + absolutePath + "!/" + name).toURL();
			}
		} catch(Exception e) {}
		
		return null;
	}
	
	/**
	 * Checks if the stated item is located inside the same classpath root as the jar that hosts ShadowClassLoader.class. {@code item} and {@code name} refer to the same thing.
	 */
	private boolean inOwnBase(URL item, String name) {
		if (item == null) return false;
		String itemString = item.toString();
		return (itemString.length() == SELF_BASE_LENGTH + name.length()) && SELF_BASE.regionMatches(0, itemString, 0, SELF_BASE_LENGTH);
	}
	
	@Override public Enumeration<URL> getResources(String name) throws IOException {
		String altName = null;
		if (name.endsWith(".class")) altName = name.substring(0, name.length() - 6) + ".SCL." + sclSuffix;
		
		// Vector? Yes, we need one:
		// * We can NOT make inner classes here (this class is loaded with special voodoo magic in eclipse, as a one off, it's not a full loader.
		// * We need to return an enumeration.
		// * We can't make one on the fly.
		// * ArrayList can't make these.
		Vector<URL> vector = new Vector<URL>();
		
		for (File ce : override) {
			URL url = getResourceFromLocation(name, altName, ce);
			if (url != null) vector.add(url);
		}
		
		if (override.isEmpty()) {
			URL fromSelf = getResourceFromLocation(name, altName, SELF_BASE_FILE);
			if (fromSelf != null) vector.add(fromSelf);
		}
		
		Enumeration<URL> sec = super.getResources(name);
		while (sec.hasMoreElements()) {
			URL item = sec.nextElement();
			if (!inOwnBase(item, name)) vector.add(item);
		}
		
		if (altName != null) {
			Enumeration<URL> tern = super.getResources(altName);
			while (tern.hasMoreElements()) {
				URL item = tern.nextElement();
				if (!inOwnBase(item, altName)) vector.add(item);
			}
		}
		
		return vector.elements();
	}
	
	@Override public URL getResource(String name) {
		return getResource_(name, false);
	}
	
	private URL getResource_(String name, boolean noSuper) {
		String altName = null;
		if (name.endsWith(".class")) altName = name.substring(0, name.length() - 6) + ".SCL." + sclSuffix;
		for (File ce : override) {
			URL url = getResourceFromLocation(name, altName, ce);
			if (url != null) return url;
		}
		
		if (!override.isEmpty()) {
			if (noSuper) return null;
			if (altName != null) {
				try {
					URL res = getResourceSkippingSelf(altName);
					if (res != null) return res;
				} catch (IOException ignore) {}
			}
			
			try {
				return getResourceSkippingSelf(name);
			} catch (IOException e) {
				return null;
			}
		}
		
		URL url = getResourceFromLocation(name, altName, SELF_BASE_FILE);
		if (url != null) return url;
		
		if (altName != null) {
			URL res = super.getResource(altName);
			if (res != null && (!noSuper || inOwnBase(res, altName))) return res;
		}
		
		URL res = super.getResource(name);
		if (res != null && (!noSuper || inOwnBase(res, name))) return res;
		return null;
	}
	
	private boolean exclusionListMatch(String name) {
		for (String pe : parentExclusion) {
			if (name.startsWith(pe)) return true;
		}
		return false;
	}
	
	private URL getResourceSkippingSelf(String name) throws IOException {
		URL candidate = super.getResource(name);
		if (candidate == null) return null;
		if (!inOwnBase(candidate, name)) return candidate;
		
		Enumeration<URL> en = super.getResources(name);
		while (en.hasMoreElements()) {
			candidate = en.nextElement();
			if (!inOwnBase(candidate, name)) return candidate;
		}
		
		return null;
	}
	
	@Override public Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		{
			Class<?> alreadyLoaded = findLoadedClass(name);
			if (alreadyLoaded != null) return alreadyLoaded;
		}
		
		String fileNameOfClass = name.replace(".",  "/") + ".class";
		URL res = getResource_(fileNameOfClass, true);
		if (res == null) {
			if (!exclusionListMatch(fileNameOfClass)) return super.loadClass(name, resolve);
			throw new ClassNotFoundException(name);
		}
		
		byte[] b;
		int p = 0;
		try {
			InputStream in = res.openStream();
			
			try {
				b = new byte[65536];
				while (true) {
					int r = in.read(b, p, b.length - p);
					if (r == -1) break;
					p += r;
					if (p == b.length) {
						byte[] nb = new byte[b.length * 2];
						System.arraycopy(b, 0, nb, 0, p);
						b = nb;
					}
				}
			} finally {
				in.close();
			}
		} catch (IOException e) {
			throw new ClassNotFoundException("I/O exception reading class " + name, e);
		}
		
		Class<?> c = defineClass(name, b, 0, p);
		if (resolve) resolveClass(c);
		return c;
	}
	
	public void addOverrideJarDir(String dir) {
		File f = new File(dir);
		for (File j : f.listFiles()) {
			if (j.getName().toLowerCase().endsWith(".jar") && j.canRead() && j.isFile()) override.add(j);
		}
	}
	
	public void addOverrideClasspathEntry(String entry) {
		override.add(new File(entry));
	}
}
