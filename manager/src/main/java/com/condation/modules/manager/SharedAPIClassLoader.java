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
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

class SharedAPIClassLoader extends URLClassLoader {

	private final List<String> apiPackages;

	SharedAPIClassLoader(final URL[] urls, final ClassLoader parent, final List<String> apiPackages) {
		super(urls, parent);
		this.apiPackages = new ArrayList<>();
		if (apiPackages != null) {
			apiPackages.stream()
					.filter(packageName -> packageName != null && !packageName.isBlank())
					.map(String::trim)
					.map(packageName -> !packageName.endsWith(".") ? packageName + "." : packageName)
					.forEach(this.apiPackages::add);
		}
	}

	boolean isAllowed(final String name) {
		for (String packageName : apiPackages) {
			if (name.startsWith(packageName)) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
		Class<?> loadedClass = findLoadedClass(name);
		if (loadedClass != null) {
			if (resolve) {
				resolveClass(loadedClass);
			}
			return loadedClass;
		}

		if (!isAllowed(name)) {
			return super.loadClass(name, resolve);
		}

		try {
			Class<?> clazz = findClass(name);
			if (resolve) {
				resolveClass(clazz);
			}
			return clazz;
		} catch (ClassNotFoundException ex) {
			return super.loadClass(name, resolve);
		}
	}
}
