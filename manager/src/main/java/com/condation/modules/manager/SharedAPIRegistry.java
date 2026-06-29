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
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

class SharedAPIRegistry implements Closeable {

	final Map<String, SharedAPIClassLoader> apiLoaders = new HashMap<>();
	private final Predicate<String> activeModulePredicate;

	SharedAPIRegistry(final ClassLoader parent, final List<ModuleImpl> modules, final Predicate<String> activeModulePredicate) throws IOException {
		this.activeModulePredicate = activeModulePredicate;
		for (ModuleImpl module : modules) {
			if (module.getApiExports().isEmpty() || module.getApiPackages().isEmpty()) {
				continue;
			}
			URL[] urls = module.getApiExports().stream()
					.map(export -> resolveExport(module.getModuleDir(), export))
					.filter(File::exists)
					.map(file -> {
						try {
							return file.toURI().toURL();
						} catch (IOException ex) {
							throw new RuntimeException(ex);
						}
					})
					.toArray(URL[]::new);
			if (urls.length > 0) {
				apiLoaders.put(module.getId(), new SharedAPIClassLoader(urls, parent, module.getApiPackages()));
			}
		}
	}

	Class<?> loadClass(final String requestingModuleId, final List<String> importedModules, final String className) throws ClassNotFoundException {
		SharedAPIClassLoader ownLoader = apiLoaders.get(requestingModuleId);
		if (ownLoader != null && ownLoader.isAllowed(className)) {
			return ownLoader.loadClass(className);
		}

		for (String importedModule : importedModules) {
			if (!activeModulePredicate.test(importedModule)) {
				continue;
			}
			SharedAPIClassLoader importedLoader = apiLoaders.get(importedModule);
			if (importedLoader != null && importedLoader.isAllowed(className)) {
				return importedLoader.loadClass(className);
			}
		}

		throw new ClassNotFoundException(className + " not visible through shared API imports");
	}

	void removeModule(final String moduleId) throws IOException {
		SharedAPIClassLoader loader = apiLoaders.remove(moduleId);
		if (loader != null) {
			loader.close();
		}
	}

	private File resolveExport(final File moduleDir, final String export) {
		File exportFile = new File(export);
		if (exportFile.isAbsolute()) {
			return exportFile;
		}
		if (export.contains("/") || export.contains("\\")) {
			return new File(moduleDir, export);
		}
		return new File(new File(moduleDir, "libs"), export);
	}

	@Override
	public void close() throws IOException {
		for (SharedAPIClassLoader loader : apiLoaders.values()) {
			loader.close();
		}
		apiLoaders.clear();
	}

	static List<String> parseList(final String value) {
		if (value == null || value.isBlank()) {
			return Collections.emptyList();
		}
		return Arrays.stream(value.split("[;,]"))
				.map(String::trim)
				.filter(entry -> !entry.isBlank())
				.collect(Collectors.toList());
	}
}
