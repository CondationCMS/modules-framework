package com.condation.modules.manager;

/*-
 * #%L
 * modules-manager
 * %%
 * Copyright (C) 2023 - 2025 CondationCMS
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
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.attribute.MethodAttributeAppender;

public class ClassLoaderInterceptor {

	private final ClassLoader moduleClassLoader;

	public ClassLoaderInterceptor(ClassLoader moduleClassLoader) {
		this.moduleClassLoader = moduleClassLoader;
	}

	/**
	 * Dynamisch Proxy erzeugen, das den ClassLoader für alle Methodenaufrufe
	 * setzt.
	 */
	public <T> T createProxy(Class<T> extensionClass, ClassLoader moduleClassLoader, T targetInstance) throws Exception {
		ByteBuddy buddy = new ByteBuddy();

		InvocationHandler handler = new InvocationHandler() {
			@Override
			public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
				// Handle Object methods directly without ClassLoader switching
				if (method.getDeclaringClass() == Object.class) {
					return method.invoke(targetInstance, args);
				}

				ClassLoader original = Thread.currentThread().getContextClassLoader();
				try {
					Thread.currentThread().setContextClassLoader(moduleClassLoader);
					return method.invoke(targetInstance, args);
				} finally {
					Thread.currentThread().setContextClassLoader(original);
				}
			}
		};

		Class<?> targetClass = targetInstance.getClass();

		Class<? extends T> dynamicType;
		dynamicType = (Class<? extends T>) new ByteBuddy().subclass(targetClass)
				// Zusätzlich implementieren wir alle Interfaces, die die targetClass selbst implementiert.
				// Das ist meist optional, aber stellt maximale Kompatibilität sicher.
				.implement(targetClass.getInterfaces())
				// Matcher: Fängt alle Methoden außer denen von Object.class und dem Konstruktor ab.
				.method(ElementMatchers.not(ElementMatchers.isDeclaredBy(Object.class).or(ElementMatchers.isConstructor())))
				// Intercept: Leitet den Aufruf an den InvocationHandler weiter.
				.intercept(InvocationHandlerAdapter.of(handler))
				.attribute(MethodAttributeAppender.ForInstrumentedMethod.INCLUDING_RECEIVER)
				.make()
				.load(moduleClassLoader, ClassLoadingStrategy.Default.CHILD_FIRST)
				.getLoaded();
		
		return dynamicType.getConstructor().newInstance();
	}
}
