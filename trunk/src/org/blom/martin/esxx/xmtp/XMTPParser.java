/*
     ESXX - The friendly ECMAscript/XML Application Server
     Copyright (C) 2007 Martin Blom <martin@blom.org>
     
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

package org.blom.martin.esxx.xmtp;

import java.io.*;
import java.util.*;
import java.util.regex.*;
import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.xml.transform.*;
import javax.xml.transform.dom.*;
import javax.xml.transform.stax.*;
import javax.mail.*;
import javax.mail.internet.*;
import javax.mail.util.ByteArrayDataSource;
import javax.xml.stream.*;

import static javax.xml.stream.XMLStreamConstants.*;

public class XMTPParser {
    public XMTPParser() {
      this.session = Session.getDefaultInstance(System.getProperties());
    }

    public MimeMessage convertMessage(InputStream is) 
      throws XMLStreamException {
      return convertMessage(XMLInputFactory.newInstance().createXMLStreamReader(is));
    }

    public MimeMessage convertMessage(Reader rd)
      throws XMLStreamException {
      return convertMessage(XMLInputFactory.newInstance().createXMLStreamReader(rd));
    }

    public MimeMessage convertMessage(javax.xml.transform.Source s)
      throws XMLStreamException {
      return convertMessage(XMLInputFactory.newInstance().createXMLStreamReader(s));
    }

    public MimeMessage convertMessage(XMLStreamReader xr)
      throws XMLStreamException {

      MimeMessage message = null;

      try {
	boolean exit = false;

	// We expect to see a <Message> element and nothing else

	while (!exit && xr.hasNext()) {
	  int ev = xr.next();

	  switch (ev) {
	    case START_ELEMENT: {
	      verifyNamespaceURI(xr);

	      String lname = xr.getLocalName();

	      if (lname.equals("Message")) {
		// Accepted
		message = new MimeMessage(session);
	      
		convertPart(xr, message);
		message.saveChanges();
	      }
	      else {
		throw new XMLStreamException("Unsupported root element: " + lname);
	      }
	      break;
	    }

	    case END_ELEMENT:
	      if (xr.getLocalName().equals("Body")) {
		// If this <Message> was an attachment (inside a <Body>),
		// we end up here.
		exit = true;
	      }
	      else {
		throw new XMLStreamException("MIME+XML parser is messed up #1");
	      }
	      break;

	    case END_DOCUMENT:
	      // For top-level <Message> elements, this is where we end up.
	      exit = true;
	      break;

	    case DTD:
	    case COMMENT:
	    case ENTITY_DECLARATION:
	    case NOTATION_DECLARATION:
	    case PROCESSING_INSTRUCTION:
	    case SPACE:
	      break;

	    default:
	      throw new XMLStreamException("MIME+XML parser is messed up #2");
	  }
	}

	return message;
      }
      catch (MessagingException ex) {
	throw new XMLStreamException("Unsupported MIME+XML format: " + ex.getMessage(),
				     ex);
      }
      catch (ClassCastException ex) {
	throw new XMLStreamException("Unsupported MIME+XML format (CCE)", ex);
      }
    }


    public static void sendMessage(MimeMessage message, Address[] recipients)
      throws MessagingException {
      if (recipients == null) {
	Transport.send(message);
      }
      else {
	Transport.send(message, recipients);
      }
    }


    protected void convertPart(XMLStreamReader xr, Part part)
      throws XMLStreamException, MessagingException {
      boolean exit = false;

      // We're inside a Message element, expecting only headers or a
      // <Body> element.

      while (!exit && xr.hasNext()) {
	int ev = xr.nextTag();

	switch (ev) {
	  case START_ELEMENT: {
	    verifyNamespaceURI(xr);

	    String name = xr.getLocalName();

	    if (name.equals("Body")) {
	      convertBody(xr, part);
	    }
	    else if (name.equals("Content-Type")) {
	      ParameterList params = new ParameterList();
	      String value = convertResourceHeader(xr, params);
	      
	      ContentType ct = new ContentType(value);
	      ct.setParameterList(params);

	      part.addHeader(name, ct.toString());
	    }
	    else if (name.equals("Content-Disposition")) {
	      ParameterList params = new ParameterList();
	      String value = convertResourceHeader(xr, params);

	      part.addHeader(name, new ContentDisposition(value, params).toString());
	    }
	    else if (name.equals("From")) {
	      // Clean up often misspelled and misformatted header
	      ((MimeMessage) part).setFrom(convertAddressHeader(xr)[0] /* Only one allowed */);
	    }
	    else if (name.equals("Sender")) {
	      // Clean up often misspelled and misformatted header
	      ((MimeMessage) part).setSender(convertAddressHeader(xr)[0] /* Only one allowed */);
	    }
	    else if (name.equals("Reply-To")) {
	      // Clean up often misspelled and misformatted header
	      ((MimeMessage) part).setReplyTo(convertAddressHeader(xr));
	    }
	    else if (name.equals("To")) {
	      // Clean up often misspelled and misformatted header
	      ((MimeMessage) part).addRecipients(Message.RecipientType.TO, 
						 convertAddressHeader(xr));
	    }
	    else if (name.equals("Cc")) {
	      // Clean up often misspelled and misformatted header
	      ((MimeMessage) part).addRecipients(Message.RecipientType.CC, 
						 convertAddressHeader(xr));
	    }
	    else if (name.equals("Bcc")) {
	      // Clean up often misspelled and misformatted header
	      ((MimeMessage) part).addRecipients(Message.RecipientType.BCC, 
						 convertAddressHeader(xr));
	    }
	    else if (name.equals("Newsgroups")) {
	      ((MimeMessage) part).addRecipients(MimeMessage.RecipientType.NEWSGROUPS, 
						 convertAddressHeader(xr));
	    }
	    else if (name.equals("Date")) {
	      try {
		((MimeMessage) part).setSentDate(mailDateFormat.parse(convertPlainHeader(xr)));
	      }
	      catch (java.text.ParseException ex) {
		throw new XMLStreamException("Invalid date format in Date element");
	      }
	    }
	    else {
	      part.addHeader(name, convertPlainHeader(xr));
	    }
	    
	    break;
	  }

	  case END_ELEMENT:
	    if (xr.getLocalName().equals("Message")) {
	      // We're done with this part; exit
	      exit = true;
	    }
	    else {
	      throw new XMLStreamException("MIME+XML parser is messed up #3");
	    }
	    break;

	  default:
	    throw new XMLStreamException("MIME+XML parser is messed up #4");
	}
      }
    }

    protected String convertResourceHeader(XMLStreamReader xr, ParameterList params)
      throws XMLStreamException {

      for (int i = 0; i < xr.getAttributeCount(); ++i) {
	params.set(xr.getAttributeLocalName(i), xr.getAttributeValue(i));
      }

      return convertPlainHeader(xr);
    }

    protected String convertPlainHeader(XMLStreamReader xr)
      throws XMLStreamException {
      return xr.getElementText();
    }

    protected Address[] convertAddressHeader(XMLStreamReader xr)
      throws XMLStreamException {
      try {
	String value = convertPlainHeader(xr);

	return InternetAddress.parse(value);
      }
      catch (AddressException ex) {
	throw new XMLStreamException("Invalid address format: " + ex.getMessage(), ex);
      }
    }

    protected void convertBody(XMLStreamReader xr, Part part)
      throws XMLStreamException, MessagingException {

      ContentType content_type = new ContentType(part.getContentType());

      String base_type = content_type.getBaseType().toLowerCase();
      String prim_type = content_type.getPrimaryType().toLowerCase();

      if (prim_type.equals("multipart")) {
	part.setContent(convertMultiPartBody(xr, content_type), 
			part.getContentType());
      }
      else if (base_type.equals("message/rfc822")) {
	part.setContent(convertMessage(xr), part.getContentType());
      }
      else if (base_type.endsWith("/xml") || base_type.endsWith("+xml")) {
	xr.next(); // I didn't think I needed this, but apparently I
		   // do ...

	// By serializing to a byte array, we can control the XML
	// charset, and we select ASCII, which is 7-bit
	// clean. Otherwise, JavaMail might decide to base64-encode
	// the XML document, which is somewhat ugly-looking.
	ByteArrayOutputStream bo = new ByteArrayOutputStream(4096);
	XMLEventWriter        bw = XMLOutputFactory.newInstance().createXMLEventWriter(bo, 
										       "ASCII");
	DOMResult             dr = null;
	XMLEventWriter        ew = null;
	XMLEventReader        er = XMLInputFactory.newInstance().createXMLEventReader(xr);

	if (base_type.equals("text/x-html+xml")) {
	  // Convert XHTML to HTML -- copy content into a DOM node first
	  dr = new DOMResult();
	  ew = XMLOutputFactory.newInstance().createXMLEventWriter(dr);
	}
	else {
	  // Copy XML as-is
	  ew = bw;
	}

	for (int level = 0;;) {
	  javax.xml.stream.events.XMLEvent ev = er.nextEvent();

	  if (ev.isStartElement()) {
	    ++level;
	  }
	  else if (ev.isEndElement()) {
	    --level;
	  }

	  if (level >= 0) {
	    ew.add(ev);
	  }
	  else {
	    break;
	  }
	}

	ew.flush();

	if (base_type.equals("text/x-html+xml")) {
	  try {
	    // Convert XHTML to HTML -- transfrom DOM node using HTML rules
	    TransformerFactory tf = TransformerFactory.newInstance();
	    tf.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);

	    Transformer tr = tf.newTransformer();
	    tr.setOutputProperty(OutputKeys.METHOD, "html");
	    tr.transform(new DOMSource(dr.getNode()), new StAXResult(bw));
	  }
	  catch (TransformerException ex) {
	    throw new XMLStreamException("Unable to transfrom 'text/x-html+xml' into 'text/xml': "
					 + ex.getMessage(), ex);
	  }

	  part.setDataHandler(new DataHandler(new ByteArrayDataSource(bo.toByteArray(),
								      "text/html")));
	}
	else {
	  part.setDataHandler(new DataHandler(new ByteArrayDataSource(bo.toByteArray(),
								      part.getContentType())));
	}
      }
      else if (base_type.startsWith("text/")) {
	part.setContent(convertTextBody(xr), part.getContentType());
      }
      else {
	String encoding[] = part.getHeader("Content-Transfer-Encoding");

	if (encoding != null && encoding.length >= 1 && !encoding[0].isEmpty()) {
	  // Encoded content; dump to disk and add a DataHandler for it
	  EncodedDataSource ds = new EncodedDataSource(xr,
						       part.getFileName(),
						       part.getContentType(),
						       encoding[0]);
	  part.setDataHandler(new DataHandler(ds));
	}
	else {
	  throw new XMLStreamException("Unsupported Content-Type/Content-Transfer-Encoding " +
				       "combination");
	}
      }
    }

    protected String convertTextBody(XMLStreamReader xr)
      throws XMLStreamException, MessagingException {
      return xr.getElementText();
    }

    protected Multipart convertMultiPartBody(XMLStreamReader xr, ContentType content_type)
      throws XMLStreamException, MessagingException {

      boolean exit         = false;
      boolean got_preamble = false;

      MimeMultipart mp = new MimeMultipart(content_type.getSubType());
      StringBuilder sb = new StringBuilder();

      while (!exit) {
	int t = xr.next();

	switch (t) {
	  case CHARACTERS:
	  case CDATA:
	  case SPACE:
	  case ENTITY_REFERENCE:
	    if (got_preamble && !xr.isWhiteSpace()) {
	      throw new XMLStreamException("Character content not allowed between " +
					   "Message elements");
	    }

	    sb.append(xr.getText());
	    break;

	  case START_ELEMENT: {
	    verifyNamespaceURI(xr);

	    if (!got_preamble) {
	      mp.setPreamble(sb.toString());
	      got_preamble = true;
	    }

	    MimeBodyPart mbp = new MimeBodyPart();

	    mp.addBodyPart(mbp);

	    convertPart(xr, mbp);
	    
	    break;
	  }

	  case END_ELEMENT:
	    if (xr.getLocalName().equals("Body")) {
	      // We're done with this part; exit
	      exit = true;
	    }
	    else {
	      throw new XMLStreamException("Unsupported MIME+XML format");
	    }
	    break;
	}
      }

      return mp;
    }

    protected void ignoreElement(XMLStreamReader xr)
      throws XMLStreamException {
      int level = 0;

      while (level >= 0) {
	switch (xr.next()) {
	  case START_ELEMENT:
	    ++level;
	    break;

	  case END_ELEMENT:
	    --level;
	    break;
	}
      }
    }

    private static void verifyNamespaceURI(XMLStreamReader xr)
      throws XMLStreamException {
      String nsuri = xr.getNamespaceURI();

      if (nsuri != null && !validNS.matcher(nsuri).matches()) {
	throw new XMLStreamException("Unsupported XML namespace");
      }
    }


    private static class EncodedDataSource
      implements DataSource {
	public EncodedDataSource(XMLStreamReader xr, String fn, String ct, String enc) 
	  throws XMLStreamException {
	  try {
	    name = fn;
	    contentType = ct;
	    encoding = enc;
	    tempFile = dumpTextToTempFile(xr);
	  }
	  catch (IOException ex) {
	    throw new XMLStreamException("Failed to write " + encoding + "-encoded data to disk: " +
					 ex.getMessage(), ex);
	  }
	}

	public void finalize() {
	  tempFile.delete();
	}

	public String getContentType() {
	  return contentType;
	}

	public InputStream getInputStream() 
	  throws IOException {
	  try {
	    return MimeUtility.decode(new FileInputStream(tempFile), encoding);
	  }
	  catch (MessagingException ex) {
	    throw new IOException("Unable to decode " + encoding + "-encoded data: " + 
				  ex.getMessage(), ex);
	  }
	}

	public String getName() {
	  return name;
	}

	public OutputStream getOutputStream()
	  throws IOException {
	  throw new IOException("EncodedDataSource is for output only");
	}


	private static File dumpTextToTempFile(XMLStreamReader xr)
	  throws XMLStreamException, IOException {
	  File f = File.createTempFile(XMTPParser.class.getName(), null);

	  OutputStreamWriter out = new OutputStreamWriter(new FileOutputStream(f),
							  "iso-8859-1");

	  int    length = 1024; 
	  char[] buffer = new char[length]; 

	  while (xr.next() != END_ELEMENT) {
	    for (int offset = 0, done = length; done == length; offset += length) {
	      done = xr.getTextCharacters(offset, buffer, 0, length);
	      out.write(buffer, 0, done);
	    }	  
	  }

	  out.close();

	  return f;
	}

	File tempFile;
	String name;
	String contentType;
	String encoding;
    }

    private static Pattern validNS = Pattern.compile("(^" + MIMEParser.MIME_NAMESPACE + "$)|" +
						     "(^" + MIMEParser.XMTP_NAMESPACE + "$)");

    private static MailDateFormat mailDateFormat = new MailDateFormat();

    private Session session;

    public static void main(String[] args) {

      if (args.length != 2) {
	System.err.println("Usage: XMTPParser <show|send> <MIME+XML file>");
	System.exit(10);
      }

      try {
	XMTPParser xp = new XMTPParser();
	
	MimeMessage msg = xp.convertMessage(new FileInputStream(args[1]));

	if (args[0].equals("show")) {
	  msg.writeTo(System.out);
	}
	else if (args[0].equals("send")) {
	  XMTPParser.sendMessage(msg, null);
	  System.err.println("Sent");
	}
	else {
	  System.err.println("Unknown command.");
	}
      }
      catch (Exception ex) {
	ex.printStackTrace();
      }
    }
}
