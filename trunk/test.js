
function MyApp(e) {

  this.handleError = function(ex) {
	esxx.debug.println("**** START ERROR HANDLER ****");
	esxx.debug.println(ex);
	esxx.debug.println("**** END ERROR HANDLER ****");
	return <html><body>{ex}</body></html>
    };

  this.handleGet = function() {
    esxx.headers.Status = "201 OK";
    esxx.debug.println("**** START GET HANDLER ****")

    var ldap = new URI("ldap://blom.org:389/ou=People,dc=blom,dc=org??sub?(objectClass=*)");

    esxx.debug.println(ldap.load());

    esxx.debug.println("**** END GET HANDLER ****");

    return <db/>;
  }
}
