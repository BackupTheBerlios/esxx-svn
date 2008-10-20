/*
     ESXX - The friendly ECMAscript/XML Application Server
     Copyright (C) 2007-2008 Martin Blom <martin@blom.org>

     This program is free software: you can redistribute it and/or
     modify it under the terms of the GNU General Public License
     as published by the Free Software Foundation, either version 3
     of the License, or (at your option) any later version.

     This program is distributed in the hope that it will be useful,
     but WITHOUT ANY WARRANTY; without even the implied warranty of
     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
     GNU General Public License for more details.

     You should have received a copy of the GNU General Public License
     along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.esxx;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.transform.*;
import javax.xml.transform.stream.*;
import org.esxx.cache.*;
import org.esxx.saxon.*;
import org.esxx.util.SyslogHandler;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.WrapFactory;
import org.w3c.dom.*;
import org.w3c.dom.bootstrap.*;
import org.w3c.dom.ls.*;

import net.sf.saxon.s9api.*;
import net.sf.saxon.*;
import net.sf.saxon.functions.FunctionLibrary;
import net.sf.saxon.functions.FunctionLibraryList;


public class ESXX {
    /** A string that defines the ESXX XML namespace */
    public static final String NAMESPACE = "http://esxx.org/1.0/";

    private static ESXX esxx;

    public static ESXX getInstance() {
      return esxx;
    }

    public static ESXX initInstance(Properties p) {
      esxx = new ESXX(p);
      return esxx;
    }

    /** The constructor.
     *
     *  Will initialize the operating environment, start the worker
     *  threads and initialize the JavaScript contexts.
     *
     *  @param p A set of properties that can be used to tune the
     *  execution.
     *
     */

    private ESXX(Properties p) {
      settings = p;

      defaultTimeout = Integer.parseInt(settings.getProperty("esxx.app.timeout", "60")) * 1000;

      try {
	String[] path = settings.getProperty("esxx.app.include_path", "").split(File.pathSeparator);
	includePath = new URI[path.length];

	for (int i = 0; i < path.length; ++i) {
	  includePath[i] = new File(path[i]).toURI();
	}
      }
      catch (Exception ex) {
	throw new ESXXException("Illegal esxx.app.include_path value: " + ex.getMessage(), ex);
      }

      memoryCache = new MemoryCache(
	this,
	Integer.parseInt(settings.getProperty("esxx.cache.max_entries", "1024")),
	Long.parseLong(settings.getProperty("esxx.cache.max_size", "16")) * 1024 * 1024,
	Long.parseLong(settings.getProperty("esxx.cache.max_age", "3600")) * 1000);

      applicationCache = new LRUCache<String, Application>(
	Integer.parseInt(settings.getProperty("esxx.cache.apps.max_entries", "1024")),
	Long.parseLong(settings.getProperty("esxx.cache.apps.max_age", "3600")) * 1000);

      applicationCache.addListener(new LRUCache.LRUListener<String, Application>() {
	  public void entryAdded(String key, Application app) {
	    getLogger().logp(Level.CONFIG, null, null, app + " loaded.");
	  }

	  public void entryRemoved(String key, final Application app) {
	    getLogger().logp(Level.CONFIG, null, null, app + " unloading ...");

	    // In this function, we're single-threaded (per application URI)
	    app.terminate(defaultTimeout);

	    // Execute the exit handler in one of the worker threads
	    Workload workload = addContextAction(null, new ContextAction() {
		public Object run(Context cx) {
		  app.executeExitHandler(cx);
		  return null;
		}
	      }, -1 /* no timeout */);

	    try {
	      workload.future.get();
	    }
	    catch (InterruptedException ex) {
	      Thread.currentThread().interrupt();
	      ex.printStackTrace();
	    }
	    catch (Exception ex) {
	      ex.printStackTrace();
	    }
	    finally {
	      mxUnregister("Application", app.getAppFilename());

	      getLogger().logp(Level.CONFIG, null, null, app + " unloaded.");
	    }
	  }
	});

      parsers = new Parsers(this);

      // Custom CGI-to-HTTP translations
      cgiToHTTPMap = new HashMap<String,String>();
      cgiToHTTPMap.put("HTTP_SOAPACTION", "SOAPAction");
      cgiToHTTPMap.put("CONTENT_TYPE", "Content-Type");
      cgiToHTTPMap.put("CONTENT_LENGTH", "Content-Length");
      cgiToHTTPMap.put("Authorization", "Authorization"); // For mod_fastcgi

      contextFactory = new ContextFactory() {
	  @Override
	  public boolean hasFeature(Context cx, int feature) {
	    if (//feature == Context.FEATURE_DYNAMIC_SCOPE ||
		feature == Context.FEATURE_LOCATION_INFORMATION_IN_ERROR ||
		feature == Context.FEATURE_MEMBER_EXPR_AS_FUNCTION_NAME ||
		//feature == Context.FEATURE_WARNING_AS_ERROR ||
		feature == Context.FEATURE_STRICT_MODE) {
	      return true;
	    }
	    else {
	      return super.hasFeature(cx, feature);
	    }
	  }

	  @Override
	  public void observeInstructionCount(Context cx, int instruction_count) {
	    Workload workload = (Workload) cx.getThreadLocal(Workload.class);

	    if (workload == null) {
	      return;
	    }

	    synchronized (workload) {
	      if (workload.future != null && workload.future.isCancelled()) {
		throw new ESXXException.TimeOut();
	      }
	    }
	  }
	};

//       org.mozilla.javascript.tools.debugger.Main main = 
// 	new org.mozilla.javascript.tools.debugger.Main("ESXX Debugger");
//       main.doBreak();
//       main.attachTo(contextFactory);
//       main.pack();
//       main.setSize(800, 600);
//       main.setVisible(true);

      ThreadFactory tf = new ThreadFactory() {
	  public Thread newThread(final Runnable r) {
	    return new Thread() {
	      @Override
		public void run() {
		contextFactory.call(new ContextAction() {
		    public Object run(Context cx) {
		      // Enable all optimizations, but do count instructions
 		      cx.setOptimizationLevel(9);
//  		      cx.setOptimizationLevel(-1);
		      cx.setInstructionObserverThreshold((int) 100e6);
		      cx.setLanguageVersion(Context.VERSION_1_7);

		      // Provide a better mapping for primitive types on this context
		      WrapFactory wf = new WrapFactory() {
			  @Override public Object wrap(Context cx, Scriptable scope, 
					     Object obj, Class<?> static_type) {
			    if (obj instanceof char[]) {
			      return new String((char[]) obj);
			    }
			    else {
			      return super.wrap(cx, scope, obj, static_type);
			    }
			  }
			};
		      wf.setJavaPrimitiveWrap(false);
		      cx.setWrapFactory(wf);

		      // Now call the Runnable
		      r.run();

		      return null;
		    }
		  });
	      }
	    };
	  }
	};

      int worker_threads = Integer.parseInt(settings.getProperty("esxx.worker_threads", "0"));

      if (worker_threads == 0) {
	// Use an unbounded thread pool
	executorService = Executors.newCachedThreadPool(tf);
      }
      else {
	// When using a bounded thread pool, SynchronousQueue and
	// CallerRunsPolicy must be used in order to avoid deadlock
	executorService = new ThreadPoolExecutor(worker_threads, worker_threads,
						 0L, TimeUnit.MILLISECONDS,
						 new SynchronousQueue<Runnable>(),
						 tf, new ThreadPoolExecutor.CallerRunsPolicy());
      }

      workloadSet = new PriorityBlockingQueue<Workload>(16, new Comparator<Workload>() {
	  public int compare(Workload w1, Workload w2) {
	    return Long.signum(w1.expires - w2.expires);
	  }
	});

      // Start a thread that cancels exired Workloads and expunges Applications
      executorService.submit(new Runnable() {
	  public void run() {
	    main: while (true) {
	      try {
		Thread.sleep(1000);

		long now = System.currentTimeMillis();

		wl: while (true) {
		  Workload w = workloadSet.peek();

		  if (w == null) {
		    break wl;
		  }

		  if (w.expires < now) {
		    w.future.cancel(true);
		    workloadSet.poll();
		  }
		  else {
		    // No need to look futher, since the workloads are
		    // sorted by expiration time
		    break wl;
		  }
		}

		applicationCache.filterEntries(new LRUCache.EntryFilter<String, Application>() {
		    public boolean isStale(String key, Application app, long created) {
		      for (URI uri : app.getExternalURIs()) {
			try {
			  long last_modified = getLastModified(uri);
			  
			  if (last_modified > created) {
			    return true;
			  }
			}
			catch (IOException ex) {
			  // Ignore errors
			}
		      }

		      return false;
		    }

		    private long getLastModified(URI uri)
		      throws IOException {
		      URLConnection uc = uri.toURL().openConnection();
		      uc.setDoInput(true);
		      uc.setDoOutput(false);
		      uc.setUseCaches(true);
		      uc.setConnectTimeout(3000);
		      uc.setReadTimeout(3000);
		      uc.connect();

		      long last_modified =  uc.getLastModified();

		      uc.getInputStream().close();

		      return last_modified;
		    }
		  });
	      }
	      catch (InterruptedException ex) {
		// Preserve status and exit
		Thread.currentThread().interrupt();
		break main;
	      }
	    }
	  }
	});
    }


    /** Returns the settings Properties object
     *
     *  @returns A Properties object.
     */

    public Properties settings() {
      return settings;
    }


    /** Returns a global, non-application tied Logger.
     *
     *  @returns A Logger object (singleton).
     */

    public synchronized Logger getLogger() {
      if (logger == null) {
	logger = Logger.getLogger(ESXX.class.getName());

	if (logger.getHandlers().length == 0) {
	  try {
	    // No specific log handler configured in
	    // jre/lib/logging.properties -- log everything to syslog
	    logger.setLevel(Level.ALL);
	    logger.addHandler(new SyslogHandler("esxx"));
	  }
	  catch (UnsupportedOperationException ex) {
	    // Never mind
	  }
	}
      }

      return logger;
    }

    public String getHTMLHeader() {
      return htmlHeader.replaceAll("@RESOURCE_URI@", 
				   settings.getProperty("esxx.resource-uri", 
							"http://esxx.org/"));
    }

    public String getHTMLFooter() {
      return htmlFooter.replaceAll("@RESOURCE_URI@", 
				   settings.getProperty("esxx.resource-uri", 
							"http://esxx.org/"));
    }

    /** Adds a Request to the work queue.
     *
     *  Once the request has been executed, Request.finished will be
     *  called with an ignorable returncode and a set of HTTP headers.
     *
     *  @param request  The Request object that is to be executed.
     *  @param timeout  The timeout in milliseconds. Note that this is
     *                  the time from the time of submission, not from the
     *                  time the request actually starts processing.
     */

    public Workload addRequest(final Request request, final ResponseHandler rh, int timeout) {
      return addContextAction(null, new ContextAction() {
	  public Object run(Context cx) {
	    Worker worker = new Worker(ESXX.this);

	    try {
	      return rh.handleResponse(ESXX.this, cx, worker.handleRequest(cx, request));
	    }
	    catch (Throwable t) {
	      return rh.handleError(ESXX.this, cx, t);
	    }
	  }
	}, timeout);
    }

    public Workload addJSFunction(Context old_cx, final Scriptable scope, final Function func,
				  final Object[] args, int timeout) {
      return addContextAction(old_cx, new ContextAction() {
	  public Object run(Context cx) {
	    return func.call(cx, scope, scope, args);
	  }

	}, timeout);
    }

    public Workload addContextAction(Context old_cx, final ContextAction ca, int timeout) {
      if (timeout == -1) {
	timeout = Integer.MAX_VALUE;
      }
      else if (timeout == 0) {
	timeout = defaultTimeout;
      }

      long         expires = System.currentTimeMillis() + timeout;
      if (old_cx != null) {
	Workload old_work = (Workload) old_cx.getThreadLocal(Workload.class);

	if (old_work != null && old_work.expires < expires) {
	  // If we're already executing a workload, never extend the timeout
	  expires = old_work.expires;
	}
      }

      final Workload workload = new Workload(expires);

      workloadSet.add(workload);

      synchronized (workload) {
	workload.future = executorService.submit(new Callable<Object>() {
	    public Object call()
	      throws Exception {
	      Context new_cx = Context.getCurrentContext();

	      Object old_workload = new_cx.getThreadLocal(Workload.class);

	      new_cx.putThreadLocal(Workload.class, workload);

	      try {
		return ca.run(new_cx);
	      }
	      finally {
		if (old_workload != null) {
		  new_cx.putThreadLocal(Workload.class, old_workload);
		}
		else {
		  new_cx.removeThreadLocal(Workload.class);
		}

		workloadSet.remove(workload);
	      }
	    }
	  });
      }

      return workload;
    }


    /** Utility method that serializes a W3C DOM Node to a String.
     *
     *  @param node  The Node to be serialized.
     *
     *  @return A String containing the XML representation of the supplied Node.
     */


    public String serializeNode(org.w3c.dom.Node node) {
      try {
	LSSerializer ser = getDOMImplementationLS().createLSSerializer();

 	DOMConfiguration dc = ser.getDomConfig();
 	dc.setParameter("xml-declaration", false);

	return ser.writeToString(node);
      }
      catch (LSException ex) {
	// Should never happen
	ex.printStackTrace();
	throw new ESXXException("Unable to serialize DOM Node: " + ex.getMessage(), ex);
      }
    }

    /** Utility method that converts a W3C DOM Node into an E4X XML object.
     *
     *  @param node  The Node to be converted.
     *
     *  @param cx    The current JavaScript context.
     *
     *  @param scope The current JavaScript scope.
     *
     *  @return A Scriptable representing an E4X XML object.
     */

    public static Scriptable domToE4X(org.w3c.dom.Node node, Context cx, Scriptable scope) {
      if (node == null) {
	return null;
      }

      return cx.newObject(scope, "XML", new org.w3c.dom.Node[] { node });
    }


    /** Utility method that converts an E4X XML object into a W3C DOM Node.
     *
     *  @param node  The E4X XML node to be converted.
     *
     *  @return A W3C DOM Node.
     */

    public static org.w3c.dom.Node e4xToDOM(Scriptable node) {
      return org.mozilla.javascript.xmlimpl.XMLLibImpl.toDomNode(node);
    }


    /** Utility method to create a new W3C DOM document.
     *
     *  @param name  The name of the document element
     *
     *  @return A W3C DOM Document.
     */

    public Document createDocument(String name) {
      return getDOMImplementation().createDocument(null, name, null);
    }


    /** Utility method that translates the name of a CGI environment
     *  variable into it's original HTTP header name.
     *
     *  @param name The name of a CGI variable.
     *
     *  @return  The name of the original HTTP header, or null if this
     *  variable name is unknown.
     */

    public String cgiToHTTP(String name) {
      String h = cgiToHTTPMap.get(name);

      // If there was a mapping, use it

      if (h != null) {
	return h;
      }

      if (name.startsWith("HTTP_")) {
	// "Guess" the name by capitalizing the variable name

	StringBuilder str = new StringBuilder();

	boolean cap = true;
	for (int i = 5; i < name.length(); ++i) {
	  char c = name.charAt(i);

	  if (c == '_') {
	    str.append('-');
	    cap = true;
	  }
	  else if (cap) {
	    str.append(Character.toUpperCase(c));
	    cap = false;
	  }
	  else {
	    str.append(Character.toLowerCase(c));
	  }
	}

	return str.toString();
      }
      else {
	return null;
      }
    }

    public URI[] getIncludePath() {
      return includePath;
    }

    /** Utility method that parses an InputStream into a W3C DOM
     *  Document.
     *
     *  @param is  The InputStream to be parsed.
     *
     *  @param is_uri  The location of the InputStream.
     *
     *  @param external_uris A Collection of URIs that will be
     *  populated with all URIs visited during the parsing. Can be
     *  'null'.
     *
     *  @param err A PrintWriter that will be used to report parser
     *  errors. Can be 'null'.
     *
     *  @return A W3C DOM Document.
     *
     *  @throws SAXException On parser errors.
     *
     *  @throws IOException On I/O errors.
     */

    public Document parseXML(InputStream is, URI is_uri,
			     final Collection<URI> external_uris,
			     final PrintWriter err)
      throws ESXXException {
      DOMImplementationLS di = getDOMImplementationLS();
      LSInput in = di.createLSInput();

      in.setSystemId(is_uri.toString());
      in.setByteStream(is);

      LSParser p = di.createLSParser(DOMImplementationLS.MODE_SYNCHRONOUS, null);

      DOMErrorHandler eh = new DOMErrorHandler() {
	    public boolean handleError(DOMError error) {
	      DOMLocator  dl = error.getLocation();
	      String     pos = (dl.getUri() + ", line " + dl.getLineNumber() +
				", column " + dl.getColumnNumber());
	      Throwable  rel = (Throwable) error.getRelatedException();

	      switch (error.getSeverity()) {
		case DOMError.SEVERITY_FATAL_ERROR:
		case DOMError.SEVERITY_ERROR:
		  if (rel instanceof ESXXException) {
		    throw (ESXXException) rel;
		  }
		  else {
		    throw new ESXXException(pos + ": " + error.getMessage(), rel);
		  }

		case DOMError.SEVERITY_WARNING:
		  err.println(pos + ": " + error.getMessage());
		  return true;
	      }

	      return false;
	    }
	};

      DOMConfiguration dc = p.getDomConfig();

      URIResolver ur = new URIResolver(external_uris);

      try {
	dc.setParameter("comments", false);
	dc.setParameter("cdata-sections", false);
	dc.setParameter("entities", false);
	//      dc.setParameter("validate-if-schema", true);
	dc.setParameter("error-handler", eh);
	dc.setParameter("resource-resolver", ur);
	dc.setParameter("http://apache.org/xml/features/xinclude", true);

	return p.parse(in);
      }
      finally {
	ur.closeAllStreams();
      }
    }

    public InputStream openCachedURL(URL url, String[] content_type)
      throws IOException {
      return memoryCache.openCachedURL(url, content_type);
    }

    public InputStream openCachedURL(URL url)
      throws IOException {
      return memoryCache.openCachedURL(url, null);
    }

    public Object parseStream(String mime_type, HashMap<String,String> mime_params,
			      InputStream is, URI is_uri,
			      Collection<URI> external_uris,
			      PrintWriter err,
			      Context cx, Scriptable scope)
      throws Exception {
      return parsers.parse(mime_type, mime_params, is, is_uri, external_uris, err, cx, scope);
    }

    public Application getCachedApplication(final Context cx, final Request request)
      throws Exception {
      String url_string = request.getScriptFilename().toString();
      Application app;

      while (true) {
	app = applicationCache.add(url_string, new LRUCache.ValueFactory<String, Application>() {
	    public Application create(String key, long age) 
	    throws IOException {
	      // The application cache makes sure we are
	      // single-threaded (per application URL) here, so only
	      // one Application will ever be created, no matter how
	      // many concurrent requests there are.
	      Application app = new Application(cx, request);
	      
	      mxRegister("Application", app.getAppFilename(), app);

	      return app;
	    }
	}, 0);

	if (app.enter()) {
	  break;
	}

	// We could not "enter" the application, because it had been
	// marked for termination but not yet removed from the
	// cache. In this (rather unusual) situation, we let some
	// other thread execute for a while and then retry again.
	Thread.yield();
      }

      return app;
    }

    public void releaseApplication(Application app, long start_time) {
      app.exit(start_time);
    }

    public void removeCachedApplication(Application app) {
      applicationCache.remove(app.getAppFilename());
    }

    public XsltExecutable getCachedStylesheet(URL url, Application app)
      throws IOException {
      return memoryCache.getCachedStylesheet(url, app);
    }


    public DOMImplementationLS getDOMImplementationLS() {
      return (DOMImplementationLS) getDOMImplementation();
    }

    public synchronized DOMImplementation getDOMImplementation() {
      if (domImplementation == null) {
	try {
	  DOMImplementationRegistry reg  = DOMImplementationRegistry.newInstance();
	  domImplementation = reg.getDOMImplementation("XML 3.0");
	}
	catch (Exception ex) {
	  throw new ESXXException("Unable to get a DOM implementation object: "
				  + ex.getMessage(), ex);
	}
      }

      return domImplementation;
    }

    public synchronized Processor getSaxonProcessor() {
      if (saxonProcessor == null) {
	saxonProcessor = new Processor(false);

	// Hook in our own extension functions
	Configuration cfg = saxonProcessor.getUnderlyingConfiguration();
	FunctionLibrary java = cfg.getExtensionBinder("java");
	FunctionLibraryList fl = new FunctionLibraryList();
	fl.addFunctionLibrary(new ESXXFunctionLibrary());
	fl.addFunctionLibrary(java);
	cfg.setExtensionBinder("java", fl);
      }

      return saxonProcessor;
    }

  private void mxRegister(String type, String name, Object object) {
    try {
      javax.management.MBeanServer mbs = 
	java.lang.management.ManagementFactory.getPlatformMBeanServer();

      mbs.registerMBean(object, mxObjectName(type, name));
    }
    catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  private void mxUnregister(String type, String name) {
    try {
      javax.management.MBeanServer mbs = 
	java.lang.management.ManagementFactory.getPlatformMBeanServer();
    
      mbs.unregisterMBean(mxObjectName(type, name));
    }
    catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  /** A pattern that matches the characters '"', '\', '?' and '*'. */
  private static java.util.regex.Pattern mxObjectNamePattern = 
    java.util.regex.Pattern.compile("(\"\\\\\\?\\*)");

  private javax.management.ObjectName mxObjectName(String type, String name) 
    throws javax.management.MalformedObjectNameException {
    String object_name = ESXX.class.getName() + ":type=" + type;

    if (name != null) {
      // Quote illegal characters
      name = mxObjectNamePattern.matcher(name).replaceAll("\\\\$1");
      object_name += ",name=\"" + name + "\""; 
    }

    return new javax.management.ObjectName(object_name);
  }

  private static String identityTransform =
      "<xsl:transform xmlns:xsl='http://www.w3.org/1999/XSL/Transform' version='2.0'>" +
      "<xsl:template match='/'>" +
      "<xsl:copy-of select='.'/>" +
      "</xsl:template>" +
      "</xsl:transform>";

//       "<xsl:transform xmlns:xsl='http://www.w3.org/1999/XSL/Transform' version='2.0'>" +
//       "<xsl:template match='element()'>" +
//       "<xsl:copy>" +
//       "<xsl:apply-templates select='@*,node()'/>" +
//       "</xsl:copy>" +
//       "</xsl:template>" +
//       "<xsl:template match='attribute()|text()|comment()|processing-instruction()'>" +
//       "<xsl:copy/>" +
//       "</xsl:template>" +
//       "</xsl:transform>";

    public XsltExecutable compileStylesheet(InputStream is, final URI is_uri,
					    Collection<URI> external_uris,
					    final Application app)
      throws SaxonApiException {
      XsltCompiler compiler = getSaxonProcessor().newXsltCompiler();

      if (is == null) {
	return compiler.compile(new StreamSource(new StringReader(identityTransform)));
      }

      URIResolver ur = new URIResolver(external_uris);

      try {
	compiler.setURIResolver(ur);
	compiler.setErrorListener(new ErrorListener() {
	    public void error(TransformerException ex)
	      throws TransformerException {
	      app.getLogger().logp(Level.SEVERE, is_uri.toString(), null,
				   ex.getMessageAndLocation(), ex);
	      throw ex;
	    }

	    public void fatalError(TransformerException ex)
	      throws TransformerException {
	      app.getLogger().logp(Level.SEVERE, is_uri.toString(), null,
				   ex.getMessageAndLocation(), ex);
	      throw ex;
	    }

	    public void warning(TransformerException ex) {
	      app.getLogger().logp(Level.WARNING, is_uri.toString(), null,
				   ex.getMessageAndLocation());
	    }
	  });

	return compiler.compile(new StreamSource(is, is_uri.toString()));
      }
      finally {
	ur.closeAllStreams();
      }
    }

    public ContextFactory getContextFactory() {
      return contextFactory;
    } 



    public static String parseMIMEType(String ct, HashMap<String,String> params) {
      String[] parts = ct.split(";");
      String   type  = parts[0].trim();

      if (params != null) {
	params.clear();

	// Add all attributes
	for (int i = 1; i < parts.length; ++i) {
	  String[] attr = parts[i].split("=", 2);

	  if (attr.length == 2) {
	    params.put(attr[0].trim(), attr[1].trim());
	  }
	}
      }

      return type;
    }

    public static String combineMIMEType(String type, HashMap<String,String> params) {
      if (type == null) {
	return null;
      }

      try {
	javax.mail.internet.ContentType ct = new javax.mail.internet.ContentType(type);
	
	if (params != null) {
	  for (Map.Entry<String,String> e : params.entrySet()) {
	    ct.setParameter(e.getKey(), e.getValue());
	  }
	}
      
	return ct.toString();
      }
      catch (javax.mail.internet.ParseException ex) {
	throw new ESXXException("Failed to parse MIME type " + type + ": " + ex.getMessage(), ex);
      }
    }

    private class URIResolver
      implements javax.xml.transform.URIResolver, LSResourceResolver {
	public URIResolver(Collection<URI> log_visited) {
	  logVisited = log_visited;
	  openedStreams = new LinkedList<InputStream>();
	}

        public void closeAllStreams() {
	  for (InputStream is : openedStreams) {
	    try { is.close(); } catch (IOException ex) {}
	  }
	}

	public Source resolve(String href,
			      String base) {
	  URL url = getURL(href, base);
	  return new StreamSource(getIS(url));
	}


	public LSInput resolveResource(String type,
				       String namespaceURI,
				       String publicId,
				       String systemId,
				       String baseURI) {
	  LSInput lsi = getDOMImplementationLS().createLSInput();
	  URL     url = getURL(systemId, baseURI);

	  lsi.setSystemId(url.toString());
	  lsi.setByteStream(getIS(url));

	  return lsi;
	}

	private URL getURL(String uri, String base_uri) {
	  try {
	    if (base_uri != null) {
	      return new URL(new URL(base_uri), uri);
	    }
	    else {
	      return new URL(uri);
	    }
	  }
	  catch (MalformedURLException ex) {
	    throw new ESXXException("URIResolver error: " + ex.getMessage(), ex);
	  }
	}

	private InputStream getIS(URL url) {
	  try {
	    InputStream is = openCachedURL(url);

	    if (logVisited != null) {
	      // Log visited URLs if successfully opened
	      logVisited.add(url.toURI());
	    }

	    openedStreams.add(is);

	    return is;
	  }
	  catch (IOException ex) {
	    throw new ESXXException("URIResolver error: " + ex.getMessage(), ex);
	  }
	  catch (URISyntaxException ex) {
	    throw new ESXXException("URIResolver error: " + ex.getMessage(), ex);
	  }
	}

	private Collection<URI> logVisited;
        private Collection<InputStream> openedStreams;
    }


    public static class Workload {
      public Workload(long exp) {
	future    = null;
	expires   = exp;
      }

      public Future<Object> future;
      public long expires;
    }

    public interface ResponseHandler {
      Integer handleResponse(ESXX esxx, Context cx, Response result)
	throws Exception;
      Integer handleError(ESXX esxx, Context cx, Throwable error);
    }

    private int defaultTimeout;
    private URI[] includePath;

    private MemoryCache memoryCache;
    private LRUCache<String, Application> applicationCache;

    private Parsers parsers;
    private Properties settings;
    private HashMap<String,String> cgiToHTTPMap;

    private DOMImplementation domImplementation;
    private Processor saxonProcessor;

    private ContextFactory contextFactory;
    private ExecutorService executorService;
    private PriorityBlockingQueue<Workload> workloadSet;
    private Logger logger;

    private static final String htmlHeader =
      "<?xml version='1.0' encoding='UTF-8'?>" +
      "<!DOCTYPE html PUBLIC '-//W3C//DTD XHTML 1.0 Strict//EN' " +
      "'http://www.w3.org/TR/2002/REC-xhtml1-20020801/DTD/xhtml1-strict.dtd'>" +
      "<html xmlns='http://www.w3.org/1999/xhtml' xml:lang='en'><head>" +
      "<title>ESXX - The friendly ECMAscript/XML Application Server</title>" +
      "<link href='@RESOURCE_URI@favicon.ico' rel='shortcut icon' type='image/vnd.microsoft.icon'/>" +
      "<link rel='alternale stylesheet' type='text/css' href='@RESOURCE_URI@css/blackwhite.css' title='Black &amp; white'/>" +
      "<link rel='alternate stylesheet' type='text/css' href='@RESOURCE_URI@css/pastel.css' title='Pastel'/>" +
      "<link rel='alternate stylesheet' type='text/css' href='@RESOURCE_URI@css/plain.css' title='Plain'/>" +
      "<link rel='alternate stylesheet' type='text/css' href='@RESOURCE_URI@css/system.css' title='System default'/>" +
      "<link rel='alternate stylesheet' type='text/css' href='@RESOURCE_URI@css/amiga.css' title='Workbench 1.x' class='default'/>" +
      "<script type='text/javascript' src='@RESOURCE_URI@js/styleswitch.js' defer='defer'></script>" +
      "</head><body>" +
      "<h1>ESXX - The friendly ECMAscript/XML Application Server</h1>";

    private static final String htmlFooter =
      "<p><br /><br /><br /></p>" +
      "<table id='switcher'>" +
      "<tr>" +
      "<td><a href='#' onclick='setActiveStyleSheet(\"Black &amp; white\"); return false;'>Black &amp; white</a></td>" +
      "<td><a href='#' onclick='setActiveStyleSheet(\"Pastel\"); return false;'>Pastel</a></td>" +
      "<td><a href='#' onclick='setActiveStyleSheet(\"Plain\"); return false;'>Plain</a></td>" +
      "<td><a href='#' onclick='setActiveStyleSheet(\"System default\"); return false;'>System default</a></td>" +
      "<td><a href='#' onclick='setActiveStyleSheet(\"Workbench 1.x\"); return false;'>Workbench 1.x</a></td>" +
      "<td class='logo'><img src='@RESOURCE_URI@gfx/logo.gif' alt='Leviticus, Divine Software' /></td>" +
      "</tr>" +
      "</table>" +
      "</body></html>";

};
