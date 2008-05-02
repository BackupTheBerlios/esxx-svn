/*
     ESXX - The friendly ECMAscript/XML Application Server
     Copyright (C) 2007-2008 Martin Blom <martin@blom.org>

     This program is free software; you can redistribute it and/or
     modify it under the terms of the GNU General Public License
     as published by the Free Software Foundation; either version 2
     of the License, or (at your option) any later version.

     This program is distributed in the hope that it will be useful,
     but WITHOUT ANY WARRANTY; without even the implied warranty of
     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
     GNU General Public License for more details.

     You should have received a copy of the GNU General Public License
     along with this program; if not, write to the Free Software
     Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
*/

package org.esxx;

import org.esxx.js.JSResponse;

import java.io.*;
import java.net.*;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.Properties;
import java.util.Set;
import org.apache.commons.cli.*;
import org.bumblescript.jfast.*;
import org.mozilla.javascript.RhinoException;

public class Main {
  static private class ScriptRequest
    extends Request 
    implements ESXX.ResponseHandler {

    public ScriptRequest(URL url, String[] cmdline) 
      throws IOException {
      super(url, cmdline, new Properties(),
	    System.in,
	    new OutputStreamWriter(System.err));
    }

    public Object handleResponse(ESXX esxx, JSResponse response) 
      throws Exception {
      // Output debug stream to stderr first
      System.err.print(getDebugWriter().toString());

      // Then write result
      HashMap<String,String> mime_params = new HashMap<String,String>();
      String mime_type = ESXX.parseMIMEType(response.getContentType(), mime_params);

      esxx.serializeToStream(response.getResult(), null, null,
			     mime_type, mime_params,
			     System.out);

      try {
	int rc = Integer.parseInt(response.getStatus().split(" ", 2)[0]);
	
	if (rc >= 500) {
	  return 20;
	}
	else if (rc >= 400) {
	  return 10;
	}
	else if (rc >= 300) {
	  return 5;
	}
	else if (rc >= 200) {
	  return 0;
	}
	else {
	  return rc;
	}
      }
      catch (NumberFormatException ex) {
	return 20;
      }
    }

    public Object handleError(ESXX esxx, Throwable t) {
      if (t instanceof ESXXException) {
	ESXXException ex = (ESXXException) t;

	System.err.println(ex.getClass().getSimpleName() + " " + ex.getStatus() 
			   + ": " + ex.getMessage());
	return 10;
      }
      else if (t instanceof RhinoException) {
	RhinoException ex = (RhinoException) t;

	System.err.println(ex.getClass().getSimpleName() + ": " + ex.getMessage());
	System.err.println(ex.getScriptStackTrace(new ESXX.JSFilenameFilter()));
	return 10;
      }
      else {
	t.printStackTrace();
	return 20;
      }
    }
  }

  static private class CGIRequest
    extends Request 
    implements ESXX.ResponseHandler {

    public CGIRequest(Properties cgi)
      throws IOException {
      super(createURL(cgi), null, cgi,
	    System.in,
	    new OutputStreamWriter(System.err));
      jFast = null;
      outStream = System.out;
    }

    public CGIRequest(JFastRequest jfast)
      throws IOException {
      super(createURL(jfast.properties), null, jfast.properties,
	    new ByteArrayInputStream(jfast.data),
	    new OutputStreamWriter(System.err));
      jFast = jfast;
      outStream = jfast.out;
    }

    public URL getWD() {
      try {
	URI main = super.getURL().toURI();

	return new File(main).getParentFile().toURI().toURL();
      }
      catch (Exception ex) {
	// Should not happen. Fall back to super method if it does.
      }

      return super.getWD();
    }

    public Object handleResponse(ESXX esxx, JSResponse response)
      throws Exception {
      // Output HTTP headers
      final PrintWriter out = new PrintWriter(createWriter(outStream, "US-ASCII"));

      out.println("Status: " + response.getStatus());
      out.println("Content-Type: " + response.getContentType());

      response.enumerateHeaders(new JSResponse.HeaderEnumerator() {
	  public void header(String name, String value) {
	    out.println(name + ": " + value);
	  }
	});
	
      out.println();
      out.flush();

      Object result = response.getResult();

      // Output body
      HashMap<String,String> mime_params = new HashMap<String,String>();
      String mime_type = ESXX.parseMIMEType(response.getContentType(), mime_params);

      esxx.serializeToStream(result, null, null,
			     mime_type, mime_params,
			     outStream);

      getErrorWriter().flush();
      getDebugWriter().flush();
      outStream.flush();

      if (jFast != null) {
	jFast.end();
      }

      return 0;
    }

    public Object handleError(ESXX esxx, Throwable ex) {
      String title = "ESXX Server Error";
      int    code  = 500;
	
      if (ex instanceof ESXXException) {
	code = ((ESXXException) ex).getStatus();
      }

      StringWriter sw = new StringWriter();
      PrintWriter out = new PrintWriter(sw);

      out.println("<?xml version='1.0'?>");
      out.println("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" " +
		  "\"http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd\">");
      out.println("<html><head><title>" + title + "</title></head><body>");
      out.println("<h1>" + title + "</h1>");
      out.println("<h2>Unhandled exception: " + ex.getClass().getSimpleName() + "</h2>");
      if (ex instanceof ESXXException ||
	  ex instanceof javax.xml.stream.XMLStreamException ||
	  ex instanceof javax.xml.transform.TransformerException) {
	out.println("<p><tt>" + ex.getMessage() + "</tt></p>");
      }
      else if (ex instanceof RhinoException) {
	out.println("<pre>");
	out.println(ex.getClass().getSimpleName() + ": " + ex.getMessage());
	out.println(((RhinoException) ex).getScriptStackTrace(new ESXX.JSFilenameFilter()));
	out.println("</pre>");
      }
      else {
	out.println("<pre>");
	ex.printStackTrace(out);
	out.println("</pre>");
      }
      out.println("</body></html>");
      out.close();

      try {
	return handleResponse(esxx, new JSResponse(code + " " + title,
						   "text/html",
						   sw.toString()));
      }
      catch (Exception ex2) {
	// Hmm
	return 20;
      }
    }

    private OutputStream outStream;
    private JFastRequest jFast;
    boolean scriptMode;
  }

  private static URL createURL(Properties headers)
    throws IOException {
    try {
      File file = new File(headers.getProperty("PATH_TRANSLATED"));

      while (file != null && !file.exists()) {
	file = file.getParentFile();
      }

      if (file.isDirectory()) {
	throw new IOException("Unable to find a file in path "
			      + headers.getProperty("PATH_TRANSLATED"));
      }

      return new URL("file", "", file.getAbsolutePath());
    }
    catch (MalformedURLException ex) {
      ex.printStackTrace();
      return null;
    }
  }


  private static void usage(Options opt, String error, int rc) {
    PrintWriter  err = new PrintWriter(System.err);
    HelpFormatter hf = new HelpFormatter();

    hf.printUsage(err, 80, "esxx.jar [OPTION...] [--script -- <script.js> SCRIPT ARGS...]");
    hf.printOptions(err, 80, opt, 2, 8);

    if (error != null) {
      err.println();
      hf.printWrapped(err, 80, "Invalid arguments: " + error + ".");
    }

    err.flush();
    System.exit(rc);
  }

  public static void main(String[] args) {
    Options opt = new Options();
    OptionGroup mode_opt = new OptionGroup();

    mode_opt.addOption(new Option("b", "bind",    true, ("Listen for FastCGI requests on " +
							 "this <port>")));
    mode_opt.addOption(new Option("c", "cgi",    false, "Force CGI mode."));
    mode_opt.addOption(new Option("s", "script", false, "Force script mode."));

    opt.addOptionGroup(mode_opt);
    opt.addOption("m", "method",  true,  "Override CGI request method");
    opt.addOption("f", "file",    true,  "Override CGI request file");
    opt.addOption("?", "help",    false, "Show help");

    try {
      CommandLineParser parser = new GnuParser();
      CommandLine cmd = parser.parse(opt, args, false);

      if (!cmd.hasOption('c') &&
	  (cmd.hasOption('m') || cmd.hasOption('f'))) {
	throw new ParseException("--method and --file can only be specified in --cgi mode");
      }

      int fastcgi_port = -1;
      Properties   cgi = null;
      String[]  script = null;

      if (cmd.hasOption('?')) {
	usage(opt, null, 0);
      }

      if (cmd.hasOption('b')) {
	fastcgi_port = Integer.parseInt(cmd.getOptionValue('b'));
      }
      else if (cmd.hasOption('c')) {
	cgi = new Properties();
      }
      else if (cmd.hasOption('s')) {
	script = cmd.getArgs();
      }
      else {
	// Guess execution mode by looking at FCGI_PORT and
	// REQUEST_METHOD environment variables.
	String fcgi_port  = System.getenv("FCGI_PORT");
	String req_method = System.getenv("REQUEST_METHOD");

	if (fcgi_port != null) {
	  fastcgi_port = Integer.parseInt(fcgi_port);
	}
	else if (req_method != null) {
	  cgi = new Properties();
	}
	else {
	  // Default mode is to execute a JS script
	  script = cmd.getArgs();
	}
      }

      ESXX esxx = ESXX.initInstance(System.getProperties());

      if (fastcgi_port != -1) {
	JFast jfast = new JFast(fastcgi_port);

	while (true) {
	  try {
	    JFastRequest req = jfast.acceptRequest();

	    // Fire and forget
	    CGIRequest cr = new CGIRequest(req);
	    esxx.addRequest(cr, cr, 0);
	  }
	  catch (JFastException ex) {
	    ex.printStackTrace();
	  }
	  catch (IOException ex) {
	    ex.printStackTrace();
	  }
	}
      }
      else if (cgi != null) {
	cgi.putAll(System.getenv());

	if (cmd.hasOption('m')) {
	  cgi.setProperty("REQUEST_METHOD", cmd.getOptionValue('m'));
	}

	if (cmd.hasOption('f')) {
	  String file = cmd.getOptionValue('f');

	  cgi.setProperty("PATH_TRANSLATED", new File(file).getAbsolutePath());
	  cgi.setProperty("PATH_INFO", file);
	}

	if (cgi.getProperty("REQUEST_METHOD") == null) {
	  usage(opt, "REQUEST_METHOD not set", 10);
	}

	if (cgi.getProperty("PATH_TRANSLATED") == null) {
	  usage(opt, "PATH_TRANSLATED not set", 10);
	}

	CGIRequest    cr = new CGIRequest(cgi);
	ESXX.Workload wl = esxx.addRequest(cr, cr, 0);

	Integer rc = (Integer) wl.future.get();
	System.exit(rc);
      }
      else if (script != null && script.length != 0) {
	File file = new File(script[0]);
	URL  url  = new URL("file", "", file.getAbsolutePath());

	ScriptRequest sr = new ScriptRequest(url, script);
	ESXX.Workload wl = esxx.addRequest(sr, sr, -1 /* no timeout for scripts */);

	Integer rc = (Integer) wl.future.get();
	System.exit(rc);
      }
      else {
	usage(opt, "Required argument missing", 10);
      }
    }
    catch (ParseException ex) {
      usage(opt, ex.getMessage(), 10);
    }
    catch (IOException ex) {
      System.err.println("I/O error: " + ex.getMessage());
      System.exit(20);
    }
    catch (Exception ex) {
      ex.printStackTrace();
      System.exit(20);
    }

    System.exit(0);
  }
}
