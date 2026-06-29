package com.condation.modules.manager;

/*-
 * #%L
 * modules-manager
 * %%
 * Copyright (C) 2023 - 2026 CondationCMS
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import com.condation.modules.api.Context;
import com.condation.modules.api.ModuleManager;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedAPIExtensionTest {

	@TempDir
	Path tempDir;

	@Test
	void skipsExtensionWhenRequiredModuleIsMissing() throws Exception {
		Path modulesDir = tempDir.resolve("modules");
		Files.createDirectories(modulesDir);
		unzip(testZip("module2"), modulesDir);

		try (ModuleManager manager = ModuleManagerImpl.create(modulesDir.toFile(), tempDir.resolve("data").toFile(), new TestContext())) {
			assertTrue(manager.activateModule("module2"));

			Class<?> extensionClass = Class.forName("com.condation.modules.example.api.ExampleExtension", false,
					((ModuleImpl) manager.module("module2")).classloader);

			assertTrue(manager.extensions((Class) extensionClass).isEmpty());
			assertTrue(manager.deactivateModule("module2"));
		}
	}

	@Test
	void loadsExtensionWithPayloadFromSharedAPIClassLoader() throws Exception {
		Path modulesDir = tempDir.resolve("modules");
		Files.createDirectories(modulesDir);
		unzip(testZip("module1"), modulesDir);
		unzip(testZip("module2"), modulesDir);

		try (ModuleManager manager = ModuleManagerImpl.create(modulesDir.toFile(), tempDir.resolve("data").toFile(), new TestContext())) {
			assertTrue(manager.activateModule("module1"));
			assertTrue(manager.activateModule("module2"));

			ClassLoader module2ClassLoader = ((ModuleImpl) manager.module("module2")).classloader;
			Class<?> extensionClass = Class.forName("com.condation.modules.example.api.ExampleExtension", false, module2ClassLoader);
			List<?> extensions = manager.extensions((Class) extensionClass);

			assertEquals(1, extensions.size());
			Method payloadMethod = extensionClass.getMethod("payload");
			Object payload = payloadMethod.invoke(extensions.get(0));
			Method valueMethod = payload.getClass().getMethod("value");

			assertEquals("module2", valueMethod.invoke(payload));
			assertEquals(extensionClass.getClassLoader(), payload.getClass().getClassLoader());
			assertTrue(manager.deactivateModule("module2"));
			assertTrue(manager.deactivateModule("module1"));
		}
	}

	@Test
	void throwsIOExceptionWhenLibsDirMissing() throws Exception {
		Path modulesDir = tempDir.resolve("modules");
		Files.createDirectories(modulesDir);
		// Unzip a real module, then delete its libs dir
		unzip(testZip("module1"), modulesDir);
		Path libsDir = modulesDir.resolve("module1").resolve("libs");
		// Remove all files in libs, then remove the dir itself
		if (Files.exists(libsDir)) {
			try (var stream = Files.walk(libsDir)) {
				stream.sorted(java.util.Comparator.reverseOrder())
					  .map(Path::toFile)
					  .forEach(java.io.File::delete);
			}
		}

		try (ModuleManager manager = ModuleManagerImpl.create(
				modulesDir.toFile(), tempDir.resolve("data").toFile(), new TestContext())) {
			org.junit.jupiter.api.Assertions.assertThrows(
				IOException.class,
				() -> manager.activateModule("module1")
			);
		}
	}

	private Path testZip(final String moduleId) {
		Path baseDir = Path.of(System.getProperty("user.dir"));
		Path repositoryRoot = baseDir.getFileName().toString().equals("manager") ? baseDir.getParent() : baseDir;
		return repositoryRoot.resolve("tests").resolve(moduleId).resolve("target").resolve(moduleId + "-bin.zip");
	}

	private void unzip(final Path zip, final Path targetDir) throws IOException {
		try (InputStream input = Files.newInputStream(zip); ZipInputStream zipInput = new ZipInputStream(input)) {
			ZipEntry entry;
			while ((entry = zipInput.getNextEntry()) != null) {
				Path target = targetDir.resolve(entry.getName()).normalize();
				if (!target.startsWith(targetDir)) {
					throw new IOException("Invalid zip entry " + entry.getName());
				}
				if (entry.isDirectory()) {
					Files.createDirectories(target);
				} else {
					Files.createDirectories(target.getParent());
					Files.copy(zipInput, target);
				}
			}
		}
	}

	private static class TestContext implements Context {
	}
}
