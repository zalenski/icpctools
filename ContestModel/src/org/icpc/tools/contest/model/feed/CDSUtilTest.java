package org.icpc.tools.contest.model.feed;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CDSUtilTest {
	@Test
	public void classLoadedFromDirectoryIsDev() throws Exception {
		File dir = Files.createTempDirectory("cds-dev").toFile();
		try {
			compile(dir, "DirProbe");
			File decoy = new File(dir, "decoy.jar");
			writeJar(decoy, null, null, "9.9.9", "8.8.8");
			URL[] classpath = new URL[] { decoy.toURI().toURL(), dir.toURI().toURL() };
			try (URLClassLoader loader = new URLClassLoader(classpath, null)) {
				URL manifest = loader.getResource("META-INF/MANIFEST.MF");
				assertNotNull(manifest);
				assertTrue(manifest.toString().contains("decoy.jar"));
				Class<?> c = loader.loadClass("DirProbe");
				assertEquals("dev", CDSUtil.getVersion(c));
			}
		} finally {
			delete(dir);
		}
	}

	@Test
	public void classLoadedFromItsOwnJarUsesThatManifest() throws Exception {
		File dir = Files.createTempDirectory("cds-jar").toFile();
		try {
			compile(dir, "JarProbe");
			File classFile = new File(dir, "JarProbe.class");
			File decoy = new File(dir, "decoy.jar");
			File own = new File(dir, "own.jar");
			writeJar(decoy, null, null, "9.9.9", "8.8.8");
			writeJar(own, classFile, "JarProbe.class", "2.7", "1472");
			URL[] classpath = new URL[] { decoy.toURI().toURL(), own.toURI().toURL() };
			try (URLClassLoader loader = new URLClassLoader(classpath, null)) {
				URL manifest = loader.getResource("META-INF/MANIFEST.MF");
				assertNotNull(manifest);
				assertTrue(manifest.toString().contains("decoy.jar"));
				Class<?> c = loader.loadClass("JarProbe");
				URL classUrl = c.getResource("JarProbe.class");
				assertNotNull(classUrl);
				assertTrue(classUrl.toString().contains("own.jar"));
				assertEquals("2.7.1472", CDSUtil.getVersion(c));
			}
		} finally {
			delete(dir);
		}
	}

	@Test
	public void jarWithoutVersionAttributesIsDev() throws Exception {
		File dir = Files.createTempDirectory("cds-nover").toFile();
		try {
			compile(dir, "PlainProbe");
			File classFile = new File(dir, "PlainProbe.class");
			File decoy = new File(dir, "decoy.jar");
			File own = new File(dir, "own.jar");
			writeJar(decoy, null, null, "9.9.9", "8.8.8");
			writeJar(own, classFile, "PlainProbe.class", null, null);
			URL[] classpath = new URL[] { decoy.toURI().toURL(), own.toURI().toURL() };
			try (URLClassLoader loader = new URLClassLoader(classpath, null)) {
				Class<?> c = loader.loadClass("PlainProbe");
				assertEquals("dev", CDSUtil.getVersion(c));
			}
		} finally {
			delete(dir);
		}
	}

	private static void compile(File dir, String className) throws IOException {
		File source = new File(dir, className + ".java");
		Files.writeString(source.toPath(), "public class " + className + " {}\n");
		JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
		assertNotNull("JDK compiler is required", compiler);
		int exit = compiler.run(null, null, null, "-d", dir.getAbsolutePath(), source.getAbsolutePath());
		assertEquals(0, exit);
	}

	private static void writeJar(File jar, File classFile, String entryName, String spec, String impl)
			throws IOException {
		Manifest manifest = new Manifest();
		Attributes attributes = manifest.getMainAttributes();
		attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
		if (spec != null) {
			attributes.put(Attributes.Name.SPECIFICATION_VERSION, spec);
		}
		if (impl != null) {
			attributes.put(Attributes.Name.IMPLEMENTATION_VERSION, impl);
		}
		try (JarOutputStream out = new JarOutputStream(new FileOutputStream(jar), manifest)) {
			if (classFile != null) {
				out.putNextEntry(new JarEntry(entryName));
				Files.copy(classFile.toPath(), out);
				out.closeEntry();
			}
		}
	}

	private static void delete(File file) {
		File[] children = file.listFiles();
		if (children != null) {
			for (File child : children) {
				delete(child);
			}
		}
		file.delete();
	}
}
