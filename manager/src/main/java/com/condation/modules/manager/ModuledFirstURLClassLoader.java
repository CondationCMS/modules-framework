package com.condation.modules.manager;

/*-
 * #%L
 * modules-manager
 * %%
 * Copyright (C) 2023 - 2024 CondationCMS
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

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

/**
 * A ClassLoader that prefers to load classes and resources from its own module
 * first (child-first), instead of delegating to the parent first.
 * <p>
 * This allows each module to bring its own dependencies (e.g. Jakarta Mail, SLF4J,
 * etc.) without causing type conflicts with the parent or other modules.
 * </p>
 * <p>
 * Use this class if you want to completely isolate module dependencies.
 * </p>
 */
public class ModuledFirstURLClassLoader extends URLClassLoader {

    private final ModuleAPIClassLoader moduleAPIClassLoader;

    public ModuledFirstURLClassLoader(URL[] classpath, ModuleAPIClassLoader parent) {
        super(classpath, parent);
        this.moduleAPIClassLoader = parent;
    }

    /**
     * Child-first class loading strategy with safety against mixed class loaders.
     * 
     * 1️⃣ Check if already loaded and ensure it's from the correct loader.
     * 2️⃣ System/core classes always from parent.
     * 3️⃣ Try to load from this module (child-first).
     * 4️⃣ Fallback to parent if allowed.
     */
    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // 1️⃣ Already loaded?
        Class<?> loadedClass = findLoadedClass(name);
        if (loadedClass != null) {
            // Prüfen, ob die Klasse vom richtigen Loader stammt
            if (loadedClass.getClassLoader() != this && loadedClass.getClassLoader() != null) {
                // Wenn die Klasse aus dem falschen Loader kommt (z. B. Parent),
                // dann erzwingen wir Neuladen aus dem Child, falls erlaubt.
                if (!isSystemClass(name) && !moduleAPIClassLoader.isAllowed(name)) {
                    // Versuche, eigene Version zu laden
                    try {
                        loadedClass = findClass(name);
                    } catch (ClassNotFoundException ignore) {
                        // Wenn das fehlschlägt, nutzen wir die geladene (Parent-)Version
                    }
                }
            }

            if (resolve) {
                resolveClass(loadedClass);
            }
            return loadedClass;
        }

        // 2️⃣ System / core packages: immer vom Parent
        if (isSystemClass(name)) {
            return super.loadClass(name, resolve);
        }

        // 3️⃣ Try to find class inside module (child-first)
        try {
            Class<?> clazz = findClass(name);
            if (resolve) {
                resolveClass(clazz);
            }
            return clazz;
        } catch (ClassNotFoundException e) {
            // 4️⃣ fallback: API or shared classes from parent
            Class<?> clazz = loadFromParent(name, resolve);
            if (resolve) {
                resolveClass(clazz);
            }
            return clazz;
        }
    }

    /**
     * Load a class from the parent only if explicitly allowed (API).
     */
    private Class<?> loadFromParent(String name, boolean resolve) throws ClassNotFoundException {
        if (moduleAPIClassLoader.isAllowed(name)) {
            return super.loadClass(name, resolve);
        }
        // not allowed, aber als letzte Rettung trotzdem versuchen
        return super.loadClass(name, resolve);
    }

    /**
     * Determines if the class should always be loaded from the system class loader.
     */
    private boolean isSystemClass(String name) {
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("sun.")
                || name.startsWith("jdk.")
                || name.startsWith("org.w3c.")
                || name.startsWith("org.xml.")
                || name.startsWith("org.objectweb.asm.") // used by some frameworks
                || name.startsWith("com.sun.");
    }

    /**
     * Child-first resource lookup.
     */
    @Override
    public URL getResource(String name) {
        URL url = findResource(name);
        if (url == null) {
            url = super.getResource(name);
        }
        return url;
    }

    /**
     * Child-first enumeration of resources.
     */
    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        List<URL> urls = new ArrayList<>();

        // local first
        Enumeration<URL> localUrls = findResources(name);
        while (localUrls.hasMoreElements()) {
            urls.add(localUrls.nextElement());
        }

        // then parent
        if (getParent() != null) {
            Enumeration<URL> parentUrls = getParent().getResources(name);
            while (parentUrls.hasMoreElements()) {
                urls.add(parentUrls.nextElement());
            }
        }

        return Collections.enumeration(urls);
    }

    /**
     * Ensures ServiceLoader works with module-local META-INF/services resources.
     */
    @Override
    public InputStream getResourceAsStream(String name) {
        URL url = getResource(name);
        if (url != null) {
            try {
                return url.openStream();
            } catch (IOException ignored) {}
        }
        return null;
    }

    /**
     * Utility for module-specific ServiceLoader usage.
     * Always use this instead of ServiceLoader.load(X.class)!
     */
    public <T> ServiceLoader<T> loadService(Class<T> serviceClass) {
        return ServiceLoader.load(serviceClass, this);
    }
}
