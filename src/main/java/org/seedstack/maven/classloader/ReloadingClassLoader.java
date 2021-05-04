/*
 * Copyright © 2013-2021, The SeedStack authors <http://seedstack.org>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.seedstack.maven.classloader;

import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ReloadingClassLoader extends URLClassLoader {
    private final AccessControlContext acc = AccessController.getContext();
    private final Map<String, DisposableClassLoader> classLoaders = new HashMap<>();
    private final Log log;
    private final List<String> sourceRoots;

    public ReloadingClassLoader(Log log, URL[] urls, List<String> sourceRoots) {
        super(urls);
        this.log = log;
        this.sourceRoots = sourceRoots;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (isClassInSourceRoots(name)) {
            DisposableClassLoader disposableClassLoader;
            synchronized (classLoaders) {
                String strippedName = stripInnerClass(name);
                disposableClassLoader = classLoaders.get(strippedName);
                if (disposableClassLoader == null) {
                    classLoaders.put(strippedName, disposableClassLoader = createDisposableClassLoader(name));
                } else {
                    disposableClassLoader.addName(name);
                }
            }
            return disposableClassLoader.loadClass(name, resolve);
        } else {
            return super.loadClass(name, resolve);
        }
    }

    private boolean isClassInSourceRoots(String name) {
        String sourceFile = stripInnerClass(name);
        sourceFile = sourceFile.replace('.', '/') + ".java";
        for (String sourceRoot : sourceRoots) {
            if (new File(sourceRoot, sourceFile).exists()) {
                return true;
            }
        }
        return false;
    }

    public void invalidateClasses(Set<String> classNamesToInvalidate) {
        synchronized (classLoaders) {
            for (String classNameToInvalidate : classNamesToInvalidate) {
                String normalizedClassName = classNameToInvalidate.replace('/', '.');
                normalizedClassName = stripInnerClass(normalizedClassName);
                if (classLoaders.remove(normalizedClassName) != null) {
                    log.debug("Class " + classNameToInvalidate + " will be reloaded on next access");
                }
            }
        }
    }

    public void invalidateClassesFromPackage(String aPackage) {
        synchronized (classLoaders) {
            Iterator<String> iterator = classLoaders.keySet().iterator();
            while (iterator.hasNext()) {
                String className = iterator.next();
                if (className.startsWith(aPackage)) {
                    iterator.remove();
                    log.debug("Class " + className + " will be reloaded on next access");
                }
            }
        }
    }

    public void invalidateAllClasses() {
        synchronized (classLoaders) {
            classLoaders.clear();
            log.debug("All classes will be reloaded on next access");
        }
    }

    private DisposableClassLoader createDisposableClassLoader(final String name) throws ClassNotFoundException {
        final DisposableClassLoader result;
        try {
            result = AccessController.doPrivileged(
                    new PrivilegedExceptionAction<DisposableClassLoader>() {
                        public DisposableClassLoader run() {
                            log.debug("Creating a disposable class loader for " + name);
                            // TODO: could be optimized by avoid giving all URLs to the disposable classloader
                            // (only the one which effectively contains the class)
                            DisposableClassLoader disposableClassLoader = new DisposableClassLoader(ReloadingClassLoader.this, getURLs());
                            disposableClassLoader.addName(name);
                            return disposableClassLoader;
                        }
                    }, acc);
        } catch (java.security.PrivilegedActionException pae) {
            throw (ClassNotFoundException) pae.getException();
        }
        if (result == null) {
            throw new ClassNotFoundException(name);
        }
        return result;
    }

    private String stripInnerClass(String name) {
        String sourceFile;
        int innerClassIndex = name.indexOf("$");
        if (innerClassIndex != -1) {
            sourceFile = name.substring(0, innerClassIndex);
        } else {
            sourceFile = name;
        }
        return sourceFile;
    }
}
