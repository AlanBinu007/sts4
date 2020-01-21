/*******************************************************************************
 * Copyright (c) 2017, 2019 Pivotal, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Pivotal, Inc. - initial API and implementation
 *******************************************************************************/
package org.springframework.ide.vscode.boot.java.utils;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.apache.commons.io.IOUtils;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.internal.compiler.batch.FileSystem.Classpath;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ide.vscode.commons.java.IClasspath;
import org.springframework.ide.vscode.commons.java.IClasspathUtil;
import org.springframework.ide.vscode.commons.java.IJavaProject;
import org.springframework.ide.vscode.commons.languageserver.java.JavaProjectFinder;
import org.springframework.ide.vscode.commons.languageserver.java.ProjectObserver;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleLanguageServer;
import org.springframework.ide.vscode.commons.languageserver.util.SimpleTextDocumentService;
import org.springframework.ide.vscode.commons.util.AsyncRunner;
import org.springframework.ide.vscode.commons.util.text.TextDocument;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import reactor.core.scheduler.Schedulers;

public final class CompilationUnitCache implements DocumentContentProvider {

	private static final Logger logger = LoggerFactory.getLogger(CompilationUnitCache.class);

	private static final long CU_ACCESS_EXPIRATION = 1;
	private JavaProjectFinder projectFinder;
	private ProjectObserver projectObserver;
	private Cache<URI, CompilationUnit> uriToCu;
	private Cache<IJavaProject, Set<URI>> projectToDocs;
	private Cache<IJavaProject, List<Classpath>> lookupEnvCache;
	private ProjectObserver.Listener projectListener;
	private SimpleTextDocumentService documents;
	private AsyncRunner async;

	private ClasspathLookupEnvironmentPool classpathLookupPool = new ClasspathLookupEnvironmentPool();

	private Object lock = new Object();;

	public CompilationUnitCache(JavaProjectFinder projectFinder, SimpleLanguageServer server, ProjectObserver projectObserver) {
		this.projectFinder = projectFinder;
		this.projectObserver = projectObserver;
		this.lookupEnvCache = CacheBuilder.newBuilder().build();
		
		// PT 154618835 - Avoid retaining the CU in the cache as it consumes memory if it hasn't been
		// accessed after some time
		uriToCu = CacheBuilder.newBuilder()
				.expireAfterWrite(CU_ACCESS_EXPIRATION, TimeUnit.MINUTES)
				.removalListener(removal -> invalidateCuForJavaFile(removal.getKey().toString()))
				.build();
		projectToDocs = CacheBuilder.newBuilder().build();

		this.documents = server == null ? null : server.getTextDocumentService();
		this.async = server == null ? new AsyncRunner(Schedulers.single()) : server.getAsync();

		if (documents != null) {
			documents.onDidChangeContent(doc -> {
				synchronized(lock) {
					invalidateCuForJavaFile(doc.getDocument().getId().getUri());
				}
			});
			documents.onDidClose(doc -> {
				synchronized(lock) {
					invalidateCuForJavaFile(doc.getId().getUri());
				}
			});
		}

//		async.execute(() -> {
//			writeLock.lock();
//			try {
//				for (IJavaProject project : projectFinder.all()) {
//					loadLookupEnvTuple(project);
//				}
//			} finally {
//				writeLock.unlock();
//			}
//		});

		projectListener = new ProjectObserver.Listener() {
			
			@Override
			public void deleted(IJavaProject project) {
				logger.info("CU Cache: deleted project {}", project.getElementName());
				async.execute(() -> {
					synchronized(lock) {
						invalidateProject(project);
					}
				});
			}
			
			@Override
			public void created(IJavaProject project) {
				logger.info("CU Cache: created project {}", project.getElementName());
				async.execute(() -> {
					synchronized(lock) {
						invalidateProject(project);
					}
				});
			}
			
			@Override
			public void changed(IJavaProject project) {
				logger.info("CU Cache: changed project {}", project.getElementName());
				async.execute(() -> {
					synchronized(lock) {
						invalidateProject(project);
					}
				});
			}
		};

		if (this.projectObserver != null) {
			this.projectObserver.addListener(projectListener);
		}
		
	}

	public void dispose() {
		if (projectObserver != null) {
			projectObserver.removeListener(projectListener);
		}
	}

	/**
	 * Retrieves a CompiationUnitn AST from the cache and passes it to a requestor callback, applying
	 * proper thread synchronization around the requestor.
	 * <p>
	 * Warning: Callers should take care to do all AST processing inside of the requestor callback and
	 * not pass of AST nodes to helper functions that work aynchronously or store AST nodes or ITypeBindings
	 * for later use. The JDT ASTs are not thread safe!
	 */
	@Deprecated
	public <T> T withCompilationUnit(TextDocument document, Function<CompilationUnit, T> requestor) {
		IJavaProject project = projectFinder.find(document.getId()).orElse(null);
		URI uri = URI.create(document.getUri());
		return withCompilationUnit(project, uri, requestor);
	}

	public <T> T withCompilationUnit(IJavaProject project, URI uri, Function<CompilationUnit, T> requestor) {
		if (project != null) {

			CompilationUnit cu = null;

			synchronized(lock ) {
				try {
					cu = uriToCu.get(uri, () -> {
						List<Classpath> lookupEnv =lookupEnvCache.get(project, () -> {
							return createClasspath(project);
						});
						String utiStr = uri.toString();
						String unitName = utiStr.substring(utiStr.lastIndexOf("/"));
						CompilationUnit cUnit = parse2(fetchContent(uri).toCharArray(), utiStr, unitName, lookupEnv);
						
						projectToDocs.get(project, () -> new HashSet<>()).add(uri);
						return cUnit;
					});
					if (cu != null) {
						projectToDocs.get(project, () -> new HashSet<>()).add(uri);
					}
				} catch (Exception e) {
					logger.error("", e);
				}
			}

			if (cu != null) {
				try {
					synchronized (cu.getAST()) {
						return requestor.apply(cu);
					}
				}
				catch (Exception e) {
					logger.error("", e);
				}
			}
		}

		return requestor.apply(null);
	}


	private void invalidateCuForJavaFile(String uriStr) {
		URI uri = URI.create(uriStr);
		uriToCu.invalidate(uri);
		IJavaProject project = projectFinder.find(new TextDocumentIdentifier(uriStr)).orElse(null);
		if (project != null) {
			Set<URI> docUris = projectToDocs.getIfPresent(project);
			if (docUris != null) {
				docUris.remove(uri);
				if (docUris.isEmpty()) {
					invalidateProject(project);
				}
			}
		}
	}

//	public static CompilationUnit parse(TextDocument document, IJavaProject project) throws Exception {
//		String[] classpathEntries = getClasspathEntries(project);
//		String docURI = document.getUri();
//		String unitName = docURI.substring(docURI.lastIndexOf("/"));
//		char[] source = document.get(0, document.getLength()).toCharArray();
//		return parse(source, docURI, unitName, classpathEntries);
//	}
//
//	public CompilationUnit parse(String uri, char[] source, IJavaProject project) throws Exception {
//		String[] classpathEntries = getClasspathEntries(project);
//		String unitName = uri.substring(uri.lastIndexOf("/"));
//		return parse(source, uri, unitName, classpathEntries);
//	}
//
//	public static CompilationUnit parse(char[] source, String docURI, String unitName, String[] classpathEntries) throws Exception {
//		ASTParser parser = ASTParser.newParser(AST.JLS11);
//		Map<String, String> options = JavaCore.getOptions();
//		JavaCore.setComplianceOptions(JavaCore.VERSION_11, options);
//		parser.setCompilerOptions(options);
//		parser.setKind(ASTParser.K_COMPILATION_UNIT);
//		parser.setStatementsRecovery(true);
//		parser.setBindingsRecovery(true);
//		parser.setResolveBindings(true);
//
//		String[] sourceEntries = new String[] {};
//		parser.setEnvironment(classpathEntries, sourceEntries, null, false);
//
//		parser.setUnitName(unitName);
//		parser.setSource(source);
//
//		CompilationUnit cu = (CompilationUnit) parser.createAST(null);
//
//		return cu;
//	}
//	
//	public static CompilationUnit parse2(char[] source, String docURI, String unitName, IJavaProject project) throws Exception {
//		List<Classpath> classpaths = createClasspath(project);
//		return parse2(source, docURI, unitName, classpaths, getClasspathEntries(project));
//	}
	
	private static CompilationUnit parse2(char[] source, String docURI, String unitName, List<Classpath> classpaths) throws Exception {
		ASTParser parser = ASTParser.newParser(AST.JLS11);
		Map<String, String> options = JavaCore.getOptions();
		JavaCore.setComplianceOptions(JavaCore.VERSION_11, options);
		parser.setCompilerOptions(options);
		parser.setKind(ASTParser.K_COMPILATION_UNIT);
		parser.setStatementsRecovery(true);
		parser.setBindingsRecovery(true);
		parser.setResolveBindings(true);

//		parser.setEnvironment(entries, new String[0], null, false);
		parser.setEnvironment(classpaths);

		parser.setUnitName(unitName);
		parser.setSource(source);

		CompilationUnit cu = (CompilationUnit) parser.createAST(null);

		return cu;
		
//		Map<String, String> options = JavaCore.getOptions();
//		String apiLevel = JavaCore.VERSION_11;
//		JavaCore.setComplianceOptions(apiLevel, options);
//		if (environment == null) {
//			environment = CUResolver.createLookupEnvironment(classpaths.toArray(new Classpath[classpaths.size()]));
//		}
//		
//		BasicCompilationUnit sourceUnit = new BasicCompilationUnit(source, null, unitName, (IJavaElement) null);
//		
//		int flags = 0;
//		boolean needToResolveBindings = true;
//		flags |= ICompilationUnit.ENABLE_STATEMENTS_RECOVERY;
//		flags |= ICompilationUnit.ENABLE_BINDINGS_RECOVERY;
//		CompilationUnitDeclaration unit = null;
//		try {
//			unit = CUResolver.resolve(sourceUnit, classpaths, options, flags, environment);
//		} catch (Exception e) {
//			flags &= ~ICompilationUnit.ENABLE_BINDINGS_RECOVERY;
//			unit = CUResolver.parse(sourceUnit, options, flags);
//			needToResolveBindings = false;
//		}
//		
//		CompilationUnit cu = CUResolver.convert(unit, source, AST.JLS11, options, needToResolveBindings, DefaultWorkingCopyOwner.PRIMARY, flags);
//		
//		return cu;
	}
	
//	private static List<Classpath> createClasspath(String[] classpathEntries) {
//		ASTParser parser = ASTParser.newParser(AST.JLS11);
//		String[] sourceEntries = new String[] {};
//		parser.setEnvironment(classpathEntries, sourceEntries, null, false);
//		return CUResolver.getClasspath(parser);
//	}
//	
//	private List<Classpath> loadLookupEnvTuple(IJavaProject project) {
////		return createClasspath(project);
//		try {
//			return lookupEnvCache.get(project, () -> {
//				return createClasspath(project);
//			});
//		} catch (ExecutionException e) {
//			logger.error("{}", e);
//			return null;
//		}
//	}
//	
	private List<Classpath> createClasspath(IJavaProject project) {
		IClasspath cp = project.getClasspath();
		List<Classpath> allClasspaths = new ArrayList<>();
		for (File f : IClasspathUtil.getAllBinaryRoots(cp)) {
			allClasspaths.addAll(classpathLookupPool.acquire(f.getAbsolutePath()));
		}
		return allClasspaths;
		
//		ASTParser parser = ASTParser.newParser(AST.JLS11);
//		String[] sourceEntries = new String[] {};
//		try {
//			parser.setEnvironment(getClasspathEntries(project), sourceEntries, null, false);
//		} catch (Exception e) {
//			// TODO Auto-generated catch block
//			e.printStackTrace();
//		}
//		return CUResolver.getClasspath(parser);
	}
	
//	private static String[] getClasspathEntries(IJavaProject project) throws Exception {
//		if (project == null) {
//			return new String[0];
//		} else {
//			IClasspath classpath = project.getClasspath();
//			Stream<File> classpathEntries = IClasspathUtil.getAllBinaryRoots(classpath).stream();
//			return classpathEntries
//					.filter(file -> file.exists())
//					.map(file -> file.getAbsolutePath()).toArray(String[]::new);
//		}
//	}

	private void invalidateProject(IJavaProject project) {
		Set<URI> docUris = projectToDocs.getIfPresent(project);
		if (docUris != null) {
			uriToCu.invalidateAll(docUris);
			projectToDocs.invalidate(project);
		}
		List<Classpath> paths = lookupEnvCache.getIfPresent(project);
		if (paths != null) {
			for (File f : IClasspathUtil.getAllBinaryRoots(project.getClasspath())) {
				classpathLookupPool.release(f.getAbsolutePath());
			}
			lookupEnvCache.invalidate(project);
		}
	}

	@Override
	public String fetchContent(URI uri) throws Exception {
		if (documents != null) {
			TextDocument document = documents.get(uri.toString());
			if (document != null) {
				return document.get(0, document.getLength());
			}
		}
		return IOUtils.toString(uri);

	}
	
	private static class ClasspathLookupEnvironmentPool {
		
		private Map<String, List<Classpath>> classpathsMap = new HashMap<>();
		private Map<String, Integer> linksMap = new HashMap<>(); 
		
		List<Classpath> acquire(String path) {
			List<Classpath> cp = classpathsMap.get(path);
			if (cp == null) {
				cp = ASTParser.createClasspathEntries(path);
				classpathsMap.put(path, cp);
				linksMap.put(path, 0);
			}
			linksMap.put(path, linksMap.get(path) + 1);
			return cp;
		}
		
		void release(String path) {
//			if (!linksMap.containsKey(path) || !classpathsMap.containsKey(path)) {
//				throw new IllegalArgumentException("Path doesn't have classpath entries!");
//			}
			int links = linksMap.get(path);
			if (links <= 1) {
				linksMap.remove(path);
				List<Classpath> cp = classpathsMap.remove(path);
				if (cp != null) {
					for (Classpath e : cp) {
						e.reset();
					}
				}
			} else {
				if (linksMap.containsKey(path)) {
					linksMap.put(path, links - 1);
				}
			}
		}
	}

}
