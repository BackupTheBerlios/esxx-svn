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
import java.net.URISyntaxException;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.stream.*;
import org.esxx.js.*;
import org.esxx.util.*;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextAction;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.WrappedException;
import org.w3c.dom.*;

import net.sf.saxon.s9api.*;
import net.sf.saxon.dom.*;

/** This class is responsible for parsing the XML file the web server
  * invokes ESXX with. The XML file may include ESXX-specific
  * processing instructions or elements from the ESXX namespace, which
  * will be interpreted.
  */

public class Application 
  extends javax.management.StandardEmitterMBean
  implements org.esxx.jmx.ApplicationMXBean {
  public Application(Context cx, Request request)
    throws IOException {

    super(org.esxx.jmx.ApplicationMXBean.class, true, 
	  new javax.management.NotificationBroadcasterSupport());


    esxx = ESXX.getInstance();

    baseURI           = request.getScriptFilename();
    baseURL           = baseURI.toURL();
    workingDirectory  = request.getWD().toURL();
    ident             = baseURL.getPath().replaceAll("^.*/", "").replaceAll("\\.[^.]*", "");
    debuggerEnabled   = request.isDebuggerEnabled();
    debuggerActivated = request.isDebuggerActivated();
    started           = new Date();
    xmlInputFactory   = XMLInputFactory.newInstance();

    loadMainFile();
    compileAndInitialize(cx);
  }

  @Override public String toString() {
    return "[" + this.getClass().getName() + ": " + baseURL + "]";
  }

  public JSGlobal getJSGlobal() {
    return applicationScope;
  }

  public JSESXX getJSESXX() {
    return jsESXX;
  }

  public Collection<URI> getExternalURIs() {
    return externalURIs;
  }

  public synchronized Logger getAppLogger() {
    if (logger == null) {
      logger = Logger.getLogger(Application.class.getName() + "." + ident);

      if (logger.getHandlers().length == 0) {
	try {
	  // No specific log handler configured in
	  // jre/lib/logging.properties -- log everything to both
	  // syslog and console using the TrivialFormatter.

	  if (logFormatter == null) {
	    logFormatter = new TrivialFormatter(true);
	  }

	  ConsoleHandler ch = new ConsoleHandler();

	  ch.setLevel(Level.ALL);
	  ch.setFormatter(logFormatter);

	  logger.setUseParentHandlers(false);
	  logger.setLevel(Level.ALL);

	  logger.addHandler(new SyslogHandler("esxx"));
	  logger.addHandler(ch);
	}
	catch (UnsupportedOperationException ex) {
	  // Never mind
	}
      }
    }

    return logger;
  }

  public synchronized JSLRUCache getPLS(Context cx) {
    if (cache == null) {
      cache = newLRUCache(cx);
    }

    return cache;
  }

  public void clearPLS() {
    if (cache != null) {
      cache.jsFunction_clear();
    }
  }

  public JSLRUCache getTLS(Context cx) {
    TLS tls = (TLS) cx.getThreadLocal(TLS.class);

    if (tls == null) {
      tls = new TLS();
      cx.putThreadLocal(TLS.class, tls);
    }

    JSLRUCache cache = tls.caches.get(this);

    if (cache == null) {
      cache = newLRUCache(cx);
      tls.caches.put(this, cache);
    }

    return cache;
  }

  public static void clearTLS(Context cx) {
    TLS tls = (TLS) cx.getThreadLocal(TLS.class);

    if (tls != null) {
      for (JSLRUCache c : tls.caches.values()) {
	c.jsFunction_clear();
      }
    }
  }

  private JSLRUCache newLRUCache(Context cx) {
    return (JSLRUCache) JSESXX.newObject(cx, jsESXX, "LRUCache",
					 new Object[] { Integer.MAX_VALUE, Long.MAX_VALUE });
  }


  public synchronized void importAndExecute(Context cx, Scriptable scope, JSESXX js_esxx,
					    URL url, InputStream is)
    throws IOException {
    Code c = importCode(url, is);

    if (c.code == null) {
      c.code = cx.compileString(c.source, c.url.toString(), c.line, null);
    }

    if (!c.hasExecuted) {
      JSURI old_uri = js_esxx.setLocation(cx, scope, c.url);
      c.code.exec(cx, scope);
      js_esxx.setLocation(old_uri);
      c.hasExecuted = true;
    }
  }

  public Object executeSOAPAction(Context cx, JSRequest req, String soap_action, String path_info)
    throws javax.xml.soap.SOAPException {
    Object result;
    RequestMatcher.Match match = soapMatcher.matchRequest(soap_action, path_info, 
							  cx, applicationScope);

    if (match == null) {
      throw new ESXXException(404, "'" + soap_action + "' SOAP action object not defined for URI "
			      + "'" + path_info + "'");
    }

    req.setArgs(match.params);

    String object = match.handler;

    javax.xml.soap.SOAPMessage message = (javax.xml.soap.SOAPMessage) req.jsGet_message();

    if (!object.equals("")) {
      // RPC style SOAP handler

      org.w3c.dom.Node    soap_header = null;
      org.w3c.dom.Element soap_body   = null;

      try {
	soap_header = message.getSOAPHeader();
      }
      catch (javax.xml.soap.SOAPException ex) {
	// The header is optional
      }

      soap_body = message.getSOAPBody().extractContentAsDocument().getDocumentElement();

      Object args[] = { req,
			ESXX.domToE4X(soap_body, cx, applicationScope),
			ESXX.domToE4X(soap_header, cx, applicationScope) };

      String prefix = soap_body.getPrefix();
      String nsuri  = soap_body.getNamespaceURI();
      String method = soap_body.getLocalName();

      result = JS.callJSMethod(object, method, args, "SOAP handler", cx, applicationScope);

      // Automatically add a SOAP-Envelope, if missing. The generated
      // envelope is based on the request envelope.
      if (result instanceof org.mozilla.javascript.xml.XMLObject) {
	result = ESXX.e4xToDOM((Scriptable) result);
      }

      if (result instanceof org.w3c.dom.Node) {
	org.w3c.dom.Node node = (org.w3c.dom.Node) result;

	if (!node.getLocalName().equals("Envelope")) {
	  // Convert Envelope to a response
	  javax.xml.soap.SOAPPart     sp = message.getSOAPPart();
	  javax.xml.soap.SOAPEnvelope se = sp.getEnvelope();

	  if (se.getHeader() != null) { 
	    se.getHeader().detachNode();
	  }

	  if (se.getBody() != null) {
	    se.getBody().detachNode();
	  }

	  // Add result to the now empty SOAP Envelope
	  javax.xml.soap.SOAPBody        sb = se.addBody();
	  javax.xml.soap.SOAPBodyElement be = sb.addBodyElement(se.createName(method + "Response",
									      prefix, nsuri));
	  Document sd = be.getOwnerDocument();
	  node = sd.adoptNode((org.w3c.dom.Node) result);
	  if (node == null) {
	    node = sd.importNode((org.w3c.dom.Node) result, true);
	  }

	  be.appendChild(node);
	  result = se;
	}
      }
    }
    else {
      // No RPC handler; the SOAP message itself is the result

      result = ESXX.domToE4X(message.getSOAPPart(), cx, applicationScope);
    }

    return result;
  }

  public Object executeHTTPMethod(Context cx, JSRequest req,
				  String request_method, String path_info) {
    Object result;
    RequestMatcher.Match match = requestMatcher.matchRequest(request_method, path_info, 
							     cx, applicationScope);

    if (match == null) {
      throw new ESXXException(404, "'" + request_method + "' handler not defined for URI "
			      + "'" + path_info + "'");
    }

    req.setArgs(match.params);

    Object args[] = { req };

    result = JS.callJSMethod(match.handler, args, "'" + request_method + "' handler", 
			     cx, applicationScope);

    return result;
  }

  public Object executeMain(Context cx, JSRequest req, 
			    String[] cmdline) {
    Object[] js_cmdline = new Object[cmdline.length];

    for (int i = 0; i < cmdline.length; ++i) {
      js_cmdline[i] = cmdline[i];
    }

    req.setArgs(cx.newArray(applicationScope, js_cmdline));

    return JS.callJSMethod("main", js_cmdline, "Program entry" , cx, applicationScope);
  }

  public void executeExitHandler(Context cx) {
    String handler = getExitHandlerFunction();

    if (handler != null) {
      Object args[] = { };

      JS.callJSMethod(handler, args, "Exit handler", cx, applicationScope);
    }
  }

  public Object executeErrorHandler(Context cx, JSRequest req, 
				    Exception error)
    throws Exception {
    Object result  = null;
    String handler = getErrorHandlerFunction();

    if (handler != null) {
      try {
	Object args[] = { req, Context.javaToJS(error, applicationScope) };

	result = JS.callJSMethod(handler, args, "Error handler", cx, applicationScope);
      }
      catch (Exception ex) {
	throw new ESXXException("Failed to handle error '" + error.toString() +
				"':\n" +
				"Error handler '" + handler +
				"' failed with message '" +
				ex.getMessage() + "'",
				ex);
      }
    }

    if (result == null || result == Context.getUndefinedValue()) {
      // No installed error handler or handler returned
      // null/undefined: throw (unwrapped) exception
      if (error instanceof WrappedException) {
	Throwable t = ((WrappedException) error).getWrappedException();

	if (t instanceof Exception) {
	  error = (Exception) t;
	}
      }

      throw error;
    }

    return result;
  }


  public synchronized boolean enter() {
    if (terminated) {
      return false;
    }

    ++enterCount;

    return true;
  }

  public synchronized void terminate(long timeout) {
    if (timer != null) {
      // Cancel all timers
      timer.cancel();
    }

    while (enterCount != 0) {
      try {
	this.wait(timeout);
      }
      catch (InterruptedException ex) {
	// Preserve status and, since enterCount != 0, thow TimeOut
	Thread.currentThread().interrupt();
      }

      if (enterCount != 0) {
	throw new ESXXException.TimeOut();
      }

      terminated = true;
    }
  }

  public synchronized void exit(long start_time) {
    if (enterCount == 0) {
      throw new IllegalStateException("enterCount becomes negative!");
    }

    ++invocations;
    lastAccessed  = System.currentTimeMillis();

    if (start_time != 0) {
      executionTime += (lastAccessed - start_time);
    }

    --enterCount;
    this.notify();
  }

  public boolean isDebuggerEnabled() {
    return debuggerEnabled;
  }

  public boolean isDebuggerActivated() {
    return debuggerActivated;
  }

  public String getAppName() {
    return ident;
  }

  public String getAppFilename() {
    return baseURI.toString();
  }

  public synchronized org.esxx.jmx.ApplicationStats getStatistics() {
    return new org.esxx.jmx.ApplicationStats(invocations, executionTime, 
					     started, new Date(lastAccessed));
  }

  public Scriptable getMainDocument() {
    return mainDocument;
  }

  public void setMainDocument(Scriptable doc) {
    mainDocument = doc;
  }

  public JSURI getMainURI() {
    return mainURI;
  }

  public URL getWD() {
    return workingDirectory;
  }

  public Scriptable getIncludePath() {
    return includePath;
  }

  public void setIncludePath(Scriptable paths) {
    includePath = paths;
  }

  public URI getStylesheet(Context cx, String media_type, String path_info) {
    try {
      RequestMatcher.Match match = xsltMatcher.matchRequest(media_type, path_info, 
							    cx, applicationScope);
      return match == null ? null : new URI(match.handler);
    }
    catch (URISyntaxException ex) {
      throw new ESXXException("Stylesheet 'href' is invalid: " +
			      ex.getMessage());
    }

  }

  public boolean hasHandlers() {
    return hasHTTPHandlers() || hasSOAPHandlers();
  }

  public boolean hasHTTPHandlers() {
    return gotHTTPHandlers;
  }

  public boolean hasSOAPHandlers() {
    return gotSOAPHandlers;
  }

  public String getErrorHandlerFunction() {
    return errorHandler;
  }

  public String getExitHandlerFunction() {
    return exitHandler;
  }

  public void unloadApplication() {
    esxx.removeCachedApplication(this);
  }

  private void loadMainFile()
    throws IOException {
    boolean is_handled = false;
    InputStream is = esxx.openCachedURL(baseURL);

    externalURIs.add(baseURI);

    // Check if it's an XML document or a JS file

    if (!is.markSupported()) {
      is = new BufferedInputStream(is);
    }

    is.mark(4096);

    if (is.read() == '#' &&
	is.read() == '!') {
      // Skip shebang
      while (is.read() != '\n') {}
      importCode(baseURL, is);

      is_handled = true;
    }
    else {
      is.reset();

      for (int i = 0; i < 4096; ++i) {
	int c = is.read();

	if (c == '<') {
	  // '<' triggers XML mode
	  break;
	}
	else if (!Character.isWhitespace(c)) {
	  // Any other character except blanks triggers direct JS-mode
	  is.reset();
	  importCode(baseURL, is);

	  is_handled = true;
	  break;
	}
      }
    }

    if (!is_handled) {
      // Load and parse document as XML

      is.reset();
      loadESXXFile(is);
    }

    is.close();
  }

  private void loadESXXFile(InputStream is) 
    throws IOException {
    try {
      xml = esxx.parseXML(is, baseURI, externalURIs, null);

      // Extract ESXX information, if any

      Processor processor = esxx.getSaxonProcessor();

      XPathCompiler xc = processor.newXPathCompiler();
      xc.declareNamespace("esxx", ESXX.NAMESPACE);

      XPathSelector xs = xc.compile("//processing-instruction() | " +
				    "//esxx:esxx/esxx:handlers/esxx:*").load();
      xs.setContextItem(processor.newDocumentBuilder().wrap(xml));

      for (XdmItem i : xs) {
	Node n = (Node) ((NodeWrapper) i.getUnderlyingValue()).getUnderlyingNode();

	if (n.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE) {
	  String name = n.getNodeName();

	  if (name.equals("esxx-stylesheet")) {
	    handleStylesheetPI(n.getNodeValue());
	    n.getParentNode().removeChild(n);
	  }
	  else if (name.equals("esxx-include")) {
	    handleImportPI(n.getNodeValue());
	    n.getParentNode().removeChild(n);
	  }
	  else if (name.equals("esxx")) {
	    addCode(baseURL, 0, n.getNodeValue());
	    n.getParentNode().removeChild(n);
	  }
	}
	else if (n.getNodeType() == Node.ELEMENT_NODE) {
	  Element e = (Element) n;
	  String name = e.getLocalName();

	  if (name.equals("http")) {
	    // esxx/handlers/http matched.
	    gotHTTPHandlers = true;
	    handleHTTPHandler(e);
	  }
	  else if (name.equals("soap")) {
	    // esxx/handlers/soap matched.
	    gotSOAPHandlers = true;
	    handleSOAPHandler(e);
	  }
	  else if (name.equals("timer")) {
	    handleTimerHandler(e);
	  }
	  else if (name.equals("stylesheet")) {
	    handleStylesheet(e);
	  }
	  else if (name.equals("error")) {
	    handleErrorHandler(e);
	  }
	  else if (name.equals("exit")) {
	    handleExitHandler(e);
	  }
	}
      }
    }
    catch (SaxonApiException ex) {
      // Should never happen
      ex.printStackTrace();
      throw new ESXXException("SaxonApiException: " + ex.getMessage(), ex);
    }
    catch (XMLStreamException ex) {
      throw new ESXXException("XMLStreamException: " + ex.getMessage(), ex);
    }
    catch (DOMException ex) {
      throw new ESXXException("DOMException: " + ex.getMessage(), ex);
    }
  }

  private void compileAndInitialize(Context cx) {
    try {
      // Create per-application top-level and global scopes
      applicationScope = new JSGlobal(cx);

      // Compile all <?esxx and <?esxx-import PIs and create JS
      // versions of the URI and the main document.
      compile(cx);

      // Make the JSESXX object available as "esxx" in the global
      // scope, so the set-up code has access to it. This call returns
      // the old esxx variable, if already present.
      jsESXX = applicationScope.createJSESXX(cx, this);

      // Execute all <?esxx and <?esxx-import PIs, if not already done
      execute(cx);

      // Prevent handler from adding global variables
      applicationScope.disallowNewGlobals();

      // Start timers, if any
      startTimers();
    }
    catch (IllegalAccessException ex) {
      throw new ESXXException("Failed to initialize Application: " + ex.getMessage(), ex);
    }
    catch (InstantiationException ex) {
      throw new ESXXException("Failed to initialize Application: " + ex.getMessage(), ex);
    }
    catch (java.lang.reflect.InvocationTargetException ex) {
      throw new ESXXException("Failed to initialize Application: " + ex.getMessage(), ex);
    }
  }

  private void compile(Context cx)
    throws IllegalAccessException, InstantiationException,
	   java.lang.reflect.InvocationTargetException {

    // Compile uri-matching regex patterns
    soapMatcher.compile();
    requestMatcher.compile();
    xsltMatcher.compile();

    for (Code c : codeList.values()) {
      c.code = cx.compileString(c.source, c.url.toString(), c.line, null);
    }

    // Create JS versions of the document, it's URI and the include path
    mainDocument = ESXX.domToE4X(xml, cx, applicationScope);
    mainURI = (JSURI) cx.newObject(applicationScope, "URI", new Object[] { baseURL });
    URI[] include_path = esxx.getIncludePath();

    includePath = cx.newArray(applicationScope, include_path.length);

    for (int i = 0; i < include_path.length; ++i) {
      includePath.put(i, includePath, cx.newObject(applicationScope, "URI",
						   new Object[] { include_path[i] }));
    }
  }

  private void execute(Context cx) {
    if (!hasExecuted) {
      for (Code c : codeList.values().toArray(new Code[0])) {
	if (!c.hasExecuted) {
	  JSURI old_uri = jsESXX.setLocation(cx, applicationScope, c.url);
	  c.code.exec(cx, applicationScope);
	  jsESXX.setLocation(old_uri);
	  c.hasExecuted = true;
	}
      }
      hasExecuted = true;
    }
  }

  private void startTimers() {
    // Start timers, if any
    if (!timerHandlers.isEmpty()) {
      timer = new Timer(getAppName() + " timer thread");

      for (final TimerHandler th : timerHandlers) {
	timer.scheduleAtFixedRate(new TimerTask() {
	    @Override public void run() {
	      esxx.addContextAction(null, new ContextAction() {
		  @Override public Object run(Context cx) {
		    try{
		      Object[] args = { new Date(scheduledExecutionTime()) };
		      
		      return JS.callJSMethod(th.handler, args, 
					     getAppName() + " timer", 
					     cx, applicationScope);
		    }
		    catch (Exception ex) {
		      ex.printStackTrace();
		      return null;
		    }
		  }
		}, (int) (th.period * 2) /* Timeout */);
	    }
	  }, th.delay, th.period);
      }
    }
  }


  private Code importCode(URL url)
    throws IOException {
    InputStream is = esxx.openCachedURL(url);

    try {
      return importCode(url, is);
    }
    finally {
      is.close();
    }
  }

  private Code importCode(URL url, InputStream is)
    throws IOException {
    try {
      String key = url.toURI().normalize().toString();
      Code     c = codeList.get(key);

      if (c == null) {
	ByteArrayOutputStream os = new ByteArrayOutputStream();

	IO.copyStream(is, os);
	c = addCode(url, 1, os.toString());
      }

      return c;
    }
    catch (URISyntaxException ex) {
      throw new IOException("Unable to include " + url + ": " + ex.getMessage(), ex);
    }
  }

  private Code addCode(URL url, int line, String data)
    throws IOException {
    try {
      Code c = new Code(url, line, data);
      codeList.put(url.toURI().normalize().toString(), c);
      externalURIs.add(url.toURI());

      return c;
    }
    catch (URISyntaxException ex) {
      throw new IOException("Unable to include " + url + ": " + ex.getMessage(), ex);
    }
  }


  private void handleStylesheetPI(String data)
    throws XMLStreamException {

    XMLStreamReader xsr = xmlInputFactory.createXMLStreamReader(
	new StringReader("<esxx-stylesheet " + data + "/>"));

    while (xsr.hasNext()) {
      if (xsr.next() == XMLStreamConstants.START_ELEMENT) {
	String type = xsr.getAttributeValue(null, "type");
	if (type == null || !type.equals("text/xsl")) {
	  throw new ESXXException("<?esxx-stylesheet?> attribute 'type' " +
				  "must be set to 'text/xsl'");
	}

	String href = xsr.getAttributeValue(null, "href");

	if (href == null) {
	  throw new ESXXException("<?esxx-stylesheet?> attribute 'href' " +
				  "must be specified");
	}

	try {
	  xsltMatcher.addRequestPattern("", "", new URL(baseURL, href).toString());
	}
	catch (MalformedURLException ex) {
	  throw new ESXXException("<?esxx-stylesheet?> attribute 'href' is invalid: " +
				  ex.getMessage());
	}
      }
    }

    xsr.close();
  }

  private void handleImportPI(String data)
    throws XMLStreamException {

    XMLStreamReader xsr = xmlInputFactory.createXMLStreamReader(
								new StringReader("<esxx-include " + data + "/>"));

    while (xsr.hasNext()) {
      if (xsr.next() == XMLStreamConstants.START_ELEMENT) {
	String href = xsr.getAttributeValue(null, "href");

	if (href == null) {
	  throw new ESXXException("<?esxx-include?> attribute 'href' " +
				  "must be specified");
	}

	try {
	  importCode(new URL(baseURL, href));
	}
	catch (MalformedURLException ex) {
	  throw new ESXXException("<?esxx-include?> attribute 'href' is invalid: " +
				  ex.getMessage(), ex);
	}
	catch (IOException ex) {
	  throw new ESXXException("<?esxx-include?> failed to include document: " +
				  ex.getMessage(), ex);
	}
      }
    }

    xsr.close();
  }

  private void handleHTTPHandler(Element e) {
    String method  = e.getAttributeNS(null, "method").trim();
    String uri     = e.getAttributeNS(null, "uri").trim();
    String handler = e.getAttributeNS(null, "handler").trim();

    if (method.equals("")) {
      throw new ESXXException("<http> attribute 'method' must " +
			      "must be specified");
    }

    if (handler.equals("")) {
      throw new ESXXException("<http> attribute 'handler' must " +
			      "must be specified");
    }

    if (handler.endsWith(")")) {
      throw new ESXXException("<http> attribute 'handler' value " +
			      "should not include parentheses");
    }

    requestMatcher.addRequestPattern(method, uri, handler);
  }

  private void handleSOAPHandler(Element e) {
    String action = e.getAttributeNS(null, "action").trim();
    String uri    = e.getAttributeNS(null, "uri").trim();
    String object = e.getAttributeNS(null, "object").trim();

    // (All arguments are optional)

    soapMatcher.addRequestPattern(action, uri, object);
  }

  private void handleTimerHandler(Element e) {
    String delay   = e.getAttributeNS(null, "delay").trim();
    String period  = e.getAttributeNS(null, "period").trim();
    String handler = e.getAttributeNS(null, "handler").trim();

    if (delay.equals("") && period.equals("")) {
      throw new ESXXException("<timer> attribute 'delat' or 'period' must must be specified");
    }

    if (handler.equals("")) {
      throw new ESXXException("<timer> attribute 'handler' must be specified");
    }

    if (handler.endsWith(")")) {
      throw new ESXXException("<timer> attribute 'handler' value should not include parentheses");
    }


    timerHandlers.add(new TimerHandler(delay, period, handler));
  }

  private void handleErrorHandler(Element e) {
    String handler = e.getAttributeNS(null, "handler").trim();

    if (errorHandler != null) {
      throw new ESXXException("Error handler already defined as '" + errorHandler + "'");
    }

    if (handler.endsWith(")")) {
      throw new ESXXException("<error> attribute 'handler' value " +
			      "should not include parentheses");
    }

    errorHandler = handler;
  }

  private void handleExitHandler(Element e) {
    String handler = e.getAttributeNS(null, "handler").trim();

    if (exitHandler != null) {
      throw new ESXXException("Exit handler already defined as '" + exitHandler + "'");
    }

    if (handler.endsWith(")")) {
      throw new ESXXException("<exit> attribute 'handler' value " +
			      "should not include parentheses");
    }

    exitHandler = handler;
  }

  private void handleStylesheet(Element e) {
    String media_type = e.getAttributeNS(null, "media-type").trim();
    String uri        = e.getAttributeNS(null, "uri").trim();
    String href       = e.getAttributeNS(null, "href").trim();
    String type       = e.getAttributeNS(null, "type").trim();

    if (href.equals("")) {
      throw new ESXXException("<stylesheet> attribute 'href' " +
			      "must be specified");
    }

    if (!type.equals("") && !type.equals("text/xsl")) {
      throw new ESXXException("<stylesheet> attribute 'type' " +
			      "must be set to 'text/xsl'");
    }

    try {
      xsltMatcher.addRequestPattern(media_type, uri, new URL(baseURL, href).toString());
    }
    catch (MalformedURLException ex) {
      throw new ESXXException("<stylesheet> attribute 'href' is invalid: " +
			      ex.getMessage());
    }
  }

  private static class Code {
    public Code(URL u, int l, String s) {
      url = u;
      line = l;
      source = s;
      code = null;
      hasExecuted = false;
    }

    @Override public String toString() {
      return url.toString() + "::" + line + ": " + code;
    }

    public URL url;
    public int line;
    public String source;
    public Script code;
    public boolean hasExecuted;
  };

  private static class TLS {
    HashMap<Object, JSLRUCache> caches = new HashMap<Object, JSLRUCache>();
  };

  private class TimerHandler {
    TimerHandler(String delay, String period, String handler) {
      try {
	if (!delay.isEmpty()) {
	  this.delay = (long) (1000 * Double.parseDouble(delay));
	}
      } 
      catch (NumberFormatException ex) {
	throw new ESXXException("Failed to parse <timer> attribute 'delay': " + ex.getMessage());
      }

      try {
	if (!period.isEmpty()) {
	  this.period = (long) (1000 * Double.parseDouble(period));
	}
      } 
      catch (NumberFormatException ex) {
	throw new ESXXException("Failed to parse <timer> attribute 'period': " + ex.getMessage());
      }

      this.handler = handler;
    }

    public long delay;
    public long period;
    public String handler;
  }

  private XMLInputFactory xmlInputFactory;

  private ESXX esxx;
  private URI baseURI;
  private URL baseURL;
  private HashSet<URI> externalURIs = new HashSet<URI>();
  private URL workingDirectory;

  private String ident;
  private Logger logger;

  private boolean debuggerEnabled;
  private boolean debuggerActivated;

  private JSGlobal applicationScope;
  private JSESXX jsESXX;
  private JSLRUCache cache;

  private int enterCount = 0;
  private boolean terminated = false;

  private long invocations;
  private long executionTime;
  private Date started;
  private long lastAccessed;

  private Scriptable mainDocument;
  private JSURI mainURI;
  private Scriptable includePath;
  private boolean hasExecuted = false;

  private boolean gotHTTPHandlers = false;
  private boolean gotSOAPHandlers = false;

  private Document xml;
  private LinkedHashMap<String, Code> codeList = new LinkedHashMap<String, Code>();

  private RequestMatcher soapMatcher = new RequestMatcher();
  private RequestMatcher requestMatcher = new RequestMatcher();
  private RequestMatcher xsltMatcher = new RequestMatcher();
  private String errorHandler;
  private String exitHandler;

  private Timer timer;
  private Collection<TimerHandler> timerHandlers = new LinkedList<TimerHandler>();

  private static java.util.logging.Formatter logFormatter;
};
