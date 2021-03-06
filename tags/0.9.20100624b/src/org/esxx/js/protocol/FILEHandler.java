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

package org.esxx.js.protocol;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.regex.Pattern;
import org.esxx.*;
import org.esxx.js.*;
import org.esxx.util.StringUtil;
import org.esxx.util.XML;
import org.mozilla.javascript.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class FILEHandler
  extends URLHandler {
  public FILEHandler(JSURI jsuri) 
    throws URISyntaxException {
    super(jsuri);
    // Make sure we're not accessing compromised paths
    if (uriSlashPattern.matcher(jsuri.getURI().toString()).matches()) {
      throw new URISyntaxException(jsuri.getURI().toString(),
				   "Encoded path separators are not allowed in ESXX file URIs");
    }
  }

  @Override
  public Object load(Context cx, Scriptable thisObj,
		     String type, HashMap<String,String> params)
    throws Exception {
    File file = new File(jsuri.getURI());

    if (type == null) {
      type = ESXX.fileTypeMap.getContentType(file);
    }

    if ((type.equals("text/xml") || type.equals("application/xml"))
	&& file.exists() && file.isDirectory()) {
      type = "application/vnd.esxx.directory+xml";
    }

    if (type.equals("application/vnd.esxx.directory+xml")) {
      Document result = createDirectoryListing(file);

      return ESXX.domToE4X(result, cx, thisObj);
    }

    return super.load(cx, thisObj, type, params);
  }

  @Override
  public Object save(Context cx, Scriptable thisObj,
		     Object data, String type, HashMap<String,String> params)
    throws Exception {
    File file = new File(jsuri.getURI());

    Response.writeObject(data, type, params, new FileOutputStream(file));
    return ESXX.domToE4X(createDirectoryEntry(file), cx, thisObj);
  }

  @Override
  public Object append(Context cx, Scriptable thisObj,
		       Object data, String type, HashMap<String,String> params)
    throws Exception {
    File file = new File(jsuri.getURI());

    if (file.exists() && file.isDirectory()) {
      String filename = params.get("name");

      if (filename == null) {
	throw Context.reportRuntimeError("append() to a directory reqires the 'name' parameter");
      }

      file = new File(file, filename);

      if (!file.createNewFile()) {
	throw Context.reportRuntimeError("Failed to create " + file);
      }

      Response.writeObject(data, type, params, new FileOutputStream(file));
    }
    else {
      Response.writeObject(data, type, params, new FileOutputStream(file, true));
    }

    return ESXX.domToE4X(createDirectoryEntry(file), cx, thisObj);
  }

//   @Override
//   public Object query(Context cx, Scriptable thisObj, Object[] args)
//     throws Exception {
//     return createDirectoryListing(new File(jsuri.getURI()));
//   }

  @Override
  public Object remove(Context cx, Scriptable thisObj,
		       String type, HashMap<String,String> params)
    throws Exception {
    File file = new File(jsuri.getURI());

    return new Boolean(file.delete());
  }

  public static Document createDirectoryListing(File dir) {
    ESXX     esxx     = ESXX.getInstance();
    Document document = esxx.createDocument("directory");
    Element  root     = document.getDocumentElement();

    root.setAttributeNS(null, "uri", dir.toURI().toString());

    for (File f : dir.listFiles()) {
      root.appendChild(createDirectoryEntry(document, f));
    }

    return document;
  }

  public static Document createDirectoryEntry(File f) {
    ESXX     esxx     = ESXX.getInstance();
    Document document = esxx.createDocument("tmp");
    
    document.replaceChild(createDirectoryEntry(document, f), 
			  document.getDocumentElement());
    return document;
  }

  public static Element createDirectoryEntry(Document document, File f) {
    Element element;

    if (f.isDirectory()) {
      element = document.createElementNS(null, "directory");
    }
    else if (f.isFile()) {
      element = document.createElementNS(null, "file");
      XML.addChild(element, "length", Long.toString(f.length()));
    }
    else {
      element = document.createElementNS(null, "object");
    }

    element.setAttributeNS(null, "uri", f.toURI().toString());

    XML.addChild(element, "name", f.getName());
    //       XML.addChild(element, "path", f.getPath());
    XML.addChild(element, "hidden", f.isHidden() ? "true" : "false");
    XML.addChild(element, "lastModified", Long.toString(f.lastModified()));
    XML.addChild(element, "id", Integer.toHexString(f.hashCode()));
    XML.addChild(element, "type", ESXX.fileTypeMap.getContentType(f));

    return element;
  }

  /** Return a Pattern that can be used to find illegal URI encoding
   *  sequences in file URIs.
   * 
   *  @return A Pattern matching illegal URIs.
   */
  
  private static Pattern getURISlashPattern() {
    String fileSeparators = System.getProperty("file.separator");

    // Normal forward slashes are always disallowed.
    String slashPattern = "(%2[fF])";

    // Disallow all characters in "file.separator"
    for (int i = 0; i < fileSeparators.length(); ++i) {
      try {
	String c = fileSeparators.substring(i, i + 1);
	String enc = StringUtil.encodeURI(c, false);
	slashPattern = slashPattern 
      		       + "|(" + enc.toLowerCase() + ")" +
      		       "|(" + enc.toUpperCase() + ")";
      }
      catch (URISyntaxException ex) {
	// Should never happen
	ex.printStackTrace();
      }
    }

    return Pattern.compile(".*(" + slashPattern + ").*");
  }

  private static final Pattern uriSlashPattern = getURISlashPattern();
}
