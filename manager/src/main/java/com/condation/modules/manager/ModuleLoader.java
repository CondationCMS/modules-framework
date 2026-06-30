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



import com.condation.modules.api.Context;
import com.condation.modules.api.ManagerConfiguration;
import com.condation.modules.api.ModuleLifeCycleExtension;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 * @author marx
 */
public class ModuleLoader {

	private final ManagerConfiguration configuration;

	final Map<String, ModuleImpl> activeModules = new ConcurrentHashMap<>();

	final File modulesPath;
	final File modulesDataPath;

	final ModuleAPIClassLoader globalClassLoader;
	SharedAPIRegistry sharedAPIRegistry;

	final Context context;
	final ModuleInjector injector;
	
	protected ModuleLoader(final ManagerConfiguration configuration, final File modulesPath, final File modulesDataPath, 
			final ModuleAPIClassLoader globalClassLoader, final Context context, final ModuleInjector injector) {
		this.configuration = configuration;
		this.modulesPath = modulesPath;
		this.modulesDataPath = modulesDataPath;
		this.globalClassLoader = globalClassLoader;
		this.context = context;
		this.injector = injector;
	}

	protected Map<String, ModuleImpl> activeModules() {
		return activeModules;
	}

	protected void setSharedAPIRegistry(final SharedAPIRegistry sharedAPIRegistry) {
		this.sharedAPIRegistry = sharedAPIRegistry;
	}

	protected boolean deactivateModule(final String moduleId) throws IOException {

		deactivateModuleLifecycle(moduleId);
		closeModule(moduleId);

		return true;
	}

	protected void deactivateModuleLifecycle(final String moduleId) {
		ModuleImpl module = activeModules().get(moduleId);
		if (module == null) {
			return;
		}
		module.extensions(ModuleLifeCycleExtension.class).stream().forEach((ModuleLifeCycleExtension mle) -> {
			mle.setContext(context);
			mle.deactivate();
		});
	}

	protected void closeModule(final String moduleId) throws IOException {
		ModuleImpl module = activeModules().get(moduleId);
		if (module == null) {
			return;
		}

		module.close();
		activeModules().remove(moduleId);

		if (sharedAPIRegistry != null) {
			sharedAPIRegistry.removeModule(moduleId);
		}
	}

	protected boolean activateModule(final String moduleId) throws IOException {
		
		File moduleDir = new File(modulesPath, configuration.get(moduleId).getModuleDir());
		File moduleData = modulesDataPath;
//		File moduleData = activeModules().get(moduleId).getModulesDataDir();

		ModuleImpl module = new ModuleImpl(moduleDir, moduleData, this.context, this.injector);

		if (areDependencyFulfilled(module)) {
			ManagerConfiguration.ModuleConfig config = configuration.get(moduleId);
			if (config == null) {
				config = new ManagerConfiguration.ModuleConfig(moduleId);
			}

			module.init(this.globalClassLoader, this.sharedAPIRegistry, activeModules::containsKey);

			config.setActive(true);
			module.extensions(ModuleLifeCycleExtension.class).stream().forEach((ModuleLifeCycleExtension mle) -> {
				mle.setContext(context);
				mle.activate();
			});
			configuration.add(config);

			activeModules().put(module.getId(), module);
			return true;
		}
		return false;
	}
	
	protected void tryToLoadModules(final List<ModuleImpl> modules) {
		// sort modules by dependency count low to max
		Collections.sort(modules, new ModuleComparator());

		int tryCount = 0;
		int oldSize = modules.size();
		while (!modules.isEmpty()) {
			if (tryCount > 2) {
				break;
			}
			loadFulfilledModules(modules);
			if (modules.size() == oldSize) {
				tryCount++;
			} else {
				oldSize = modules.size();
			}
		}
	}

	private void loadFulfilledModules(final List<ModuleImpl> modules) {
		for (final ModuleImpl module : modules) {
			if (areDependencyFulfilled(module) && configuration.get(module.getId()).isActive()) {
				try {
					module.init(this.globalClassLoader, this.sharedAPIRegistry, activeModules::containsKey);
					activeModules.put(module.getId(), module);
				} catch (IOException ex) {
					throw new RuntimeException(ex);
				}
			}
		}
		modules.removeAll(activeModules.values());
	}

	private boolean areDependencyFulfilled(final ModuleImpl module) {
		return module.getDependencies().stream().noneMatch((dependency) -> (!activeModules.containsKey(dependency.id())));
	}
}
