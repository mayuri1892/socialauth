/*
 ===========================================================================
 Copyright (c) 2010 BrickRed Technologies Limited

 Permission is hereby granted, free of charge, to any person obtaining a copy
 of this software and associated documentation files (the "Software"), to deal
 in the Software without restriction, including without limitation the rights
 to use, copy, modify, merge, publish, distribute, sub-license, and/or sell
 copies of the Software, and to permit persons to whom the Software is
 furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included in
 all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 THE SOFTWARE.
 ===========================================================================

 */

package org.brickred.socialauth.provider;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.brickred.socialauth.AbstractProvider;
import org.brickred.socialauth.AuthProvider;
import org.brickred.socialauth.Contact;
import org.brickred.socialauth.Permission;
import org.brickred.socialauth.Profile;
import org.brickred.socialauth.exception.ProviderStateException;
import org.brickred.socialauth.exception.ServerDataException;
import org.brickred.socialauth.exception.SocialAuthConfigurationException;
import org.brickred.socialauth.exception.SocialAuthException;
import org.brickred.socialauth.exception.UserDeniedPermissionException;
import org.brickred.socialauth.util.Constants;
import org.brickred.socialauth.util.OAuthConfig;
import org.brickred.socialauth.util.OAuthConsumer;
import org.brickred.socialauth.util.Response;
import org.brickred.socialauth.util.Token;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Provider implementation for Myspace
 * 
 */
public class MySpaceImpl extends AbstractProvider implements AuthProvider,
		Serializable {

	private static final long serialVersionUID = -4074039782095430942L;
	private static final String PROPERTY_DOMAIN = "api.myspace.com";
	private static final String REQUEST_TOKEN_URL = "http://api.myspace.com/request_token";
	private static final String AUTHORIZATION_URL = "http://api.myspace.com/authorize?myspaceid.permissions=VIEWER_FULL_PROFILE_INFO|ViewFullProfileInfo|UpdateMoodStatus";
	private static final String ACCESS_TOKEN_URL = "http://api.myspace.com/access_token";
	private static final String PROFILE_URL = "http://api.myspace.com/1.0/people/@me/@self";
	private static final String CONTACTS_URL = "http://api.myspace.com/1.0/people/@me/@all";
	private static final String UPDATE_STATUS_URL = "http://api.myspace.com/1.0/statusmood/@me/@self";
	private final Log LOG = LogFactory.getLog(MySpaceImpl.class);

	private Permission scope;
	private Properties properties;
	private boolean isVerify;
	private Token requestToken;
	private Token accessToken;
	OAuthConsumer oauth;
	OAuthConfig config;

	public MySpaceImpl(final Properties props) throws Exception {
		try {
			this.properties = props;
			config = OAuthConfig.load(this.properties, PROPERTY_DOMAIN);
		} catch (IllegalStateException e) {
			throw new SocialAuthConfigurationException(e);
		}
		if (config.get_consumerSecret().length() == 0) {
			throw new SocialAuthConfigurationException(
					"api.myspace.com.consumer_secret value is null");
		}
		if (config.get_consumerKey().length() == 0) {
			throw new SocialAuthConfigurationException(
					"api.myspace.com.consumer_key value is null");
		}
		oauth = new OAuthConsumer(config);
	}

	/**
	 * This is the most important action. It redirects the browser to an
	 * appropriate URL which will be used for authentication with the provider
	 * that has been set using setId()
	 * 
	 * @throws Exception
	 */

	@Override
	public String getLoginRedirectURL(final String returnTo) throws Exception {
		LOG.info("Determining URL for redirection");
		setProviderState(true);
		LOG.debug("Call to fetch Request Token");
		requestToken = oauth.getRequestToken(REQUEST_TOKEN_URL, returnTo);
		StringBuilder urlBuffer = oauth.buildAuthUrl(AUTHORIZATION_URL,
				requestToken, returnTo);
		LOG.info("Redirection to following URL should happen : "
				+ urlBuffer.toString());
		return urlBuffer.toString();
	}

	/**
	 * Verifies the user when the external provider redirects back to our
	 * application.
	 * 
	 * @return Profile object containing the profile information
	 * @param request
	 *            Request object the request is received from the provider
	 * @throws Exception
	 */

	@Override
	public Profile verifyResponse(final HttpServletRequest request)
			throws Exception {
		LOG.info("Verifying the authentication response from provider");
		if (request.getParameter("oauth_problem") != null
				&& "user_refused".equals(request.getParameter("oauth_problem"))) {
			throw new UserDeniedPermissionException();
		}
		if (!isProviderState()) {
			throw new ProviderStateException();
		}

		if (requestToken == null) {
			throw new SocialAuthException("Request token is null");
		}
		String verifier = request.getParameter(Constants.OAUTH_VERIFIER);
		if (verifier != null) {
			requestToken.setAttribute(Constants.OAUTH_VERIFIER, verifier);
		}

		LOG.debug("Call to fetch Access Token");
		accessToken = oauth.getAccessToken(ACCESS_TOKEN_URL, requestToken);

		isVerify = true;
		return getUserProfile();
	}

	/**
	 * Gets the list of contacts of the user and their profile URL.
	 * 
	 * @return List of contact objects representing Contacts. Only name and
	 *         profile URL will be available
	 */

	@Override
	public List<Contact> getContactList() throws Exception {
		if (!isVerify) {
			throw new SocialAuthException(
					"Please call verifyResponse function first to get Access Token");
		}
		LOG.info("Fetching contacts from " + CONTACTS_URL);

		Response serviceResponse = null;
		try {
			serviceResponse = oauth.httpGet(CONTACTS_URL, null, accessToken);
		} catch (Exception ie) {
			throw new SocialAuthException(
					"Failed to retrieve the contacts from " + CONTACTS_URL, ie);
		}
		String result;
		try {
			result = serviceResponse
					.getResponseBodyAsString(Constants.ENCODING);
			LOG.debug("Contacts JSON :" + result);
		} catch (Exception exc) {
			throw new SocialAuthException("Failed to read contacts from  "
					+ CONTACTS_URL);
		}
		JSONArray fArr = new JSONArray();
		JSONObject resObj = new JSONObject(result);
		if (resObj.has("entry")) {
			fArr = resObj.getJSONArray("entry");
		} else {
			throw new ServerDataException(
					"Failed to parse the user Contacts json : " + result);
		}
		List<Contact> plist = new ArrayList<Contact>();
		for (int i = 0; i < fArr.length(); i++) {
			JSONObject fObj = fArr.getJSONObject(i);
			if (fObj.has("person")) {
				Contact contact = new Contact();
				JSONObject pObj = fObj.getJSONObject("person");
				if (pObj.has("displayName")) {
					contact.setDisplayName(pObj.getString("displayName"));
				}
				if (pObj.has("name")) {
					JSONObject nobj = pObj.getJSONObject("name");
					if (nobj.has("familyName")) {
						contact.setLastName(nobj.getString("familyName"));
					}
					if (nobj.has("givenName")) {
						contact.setFirstName(nobj.getString("givenName"));
					}
				}

				if (pObj.has("profileUrl")) {
					contact.setProfileUrl(pObj.getString("profileUrl"));
				}
				plist.add(contact);
			}
		}
		return plist;
	}

	/**
	 * Updates the status on the chosen provider if available. This may not be
	 * implemented for all providers.
	 * 
	 * @param msg
	 *            Message to be shown as user's status
	 * @throws Exception
	 */
	@Override
	public void updateStatus(final String msg) throws Exception {
		if (!isVerify) {
			throw new SocialAuthException(
					"Please call verifyResponse function first to get Access Token");
		}
		if (msg == null || msg.trim().length() == 0) {
			throw new ServerDataException("Status cannot be blank");
		}
		LOG.info("Updating status " + msg + " on " + UPDATE_STATUS_URL);
		Map<String, String> params = new HashMap<String, String>();
		String msgBody = "{\"status\":\"" + msg + "\"}";
		Response serviceResponse = null;
		try {
			serviceResponse = oauth.httpPut(UPDATE_STATUS_URL, params, null,
					msgBody, accessToken, false);
		} catch (Exception ie) {
			throw new SocialAuthException("Failed to update status on "
					+ UPDATE_STATUS_URL, ie);
		}
		LOG.info("Update Status Response :" + serviceResponse.getStatus());

	}

	/**
	 * Logout
	 */
	@Override
	public void logout() {
		requestToken = null;
		accessToken = null;
	}

	/**
	 * 
	 * @param p
	 *            Permission object which can be Permission.AUHTHENTICATE_ONLY,
	 *            Permission.ALL, Permission.DEFAULT
	 */
	public void setPermission(final Permission p) {
		LOG.debug("Permission requested : " + p.toString());
		this.scope = p;
	}

	private Profile getUserProfile() throws Exception {
		LOG.debug("Obtaining user profile");
		Profile profile = new Profile();

		Response serviceResponse = null;
		try {
			serviceResponse = oauth.httpGet(PROFILE_URL, null, accessToken);
		} catch (Exception e) {
			throw new SocialAuthException(
					"Failed to retrieve the user profile from  " + PROFILE_URL);
		}
		if (serviceResponse.getStatus() != 200) {
			throw new SocialAuthException(
					"Failed to retrieve the user profile from  " + PROFILE_URL
							+ ". Staus :" + serviceResponse.getStatus());
		}

		String result;
		try {
			result = serviceResponse
					.getResponseBodyAsString(Constants.ENCODING);
			LOG.debug("User Profile :" + result);
		} catch (Exception exc) {
			throw new SocialAuthException("Failed to read response from  "
					+ PROFILE_URL);
		}
		JSONObject pObj = new JSONObject();
		JSONObject jobj = new JSONObject(result);
		if (jobj.has("person")) {
			pObj = jobj.getJSONObject("person");
		} else {
			throw new ServerDataException(
					"Failed to parse the user profile json : " + result);
		}
		if (pObj.has("displayName")) {
			profile.setDisplayName(pObj.getString("displayName"));
		}
		if (pObj.has("id")) {
			profile.setValidatedId(pObj.getString("id"));
		}
		if (pObj.has("name")) {
			JSONObject nobj = pObj.getJSONObject("name");
			if (nobj.has("familyName")) {
				profile.setLastName(nobj.getString("familyName"));
			}
			if (nobj.has("givenName")) {
				profile.setFirstName(nobj.getString("givenName"));
			}
		}
		if (pObj.has("location")) {
			profile.setLocation(pObj.getString("location"));
		}
		if (pObj.has("nickname")) {
			profile.setDisplayName(pObj.getString("nickname"));
		}
		if (pObj.has("lang")) {
			profile.setLanguage(pObj.getString("lang"));
		}
		if (pObj.has("birthdate")) {
			profile.setDob(pObj.getString("birthdate"));
		}
		if (pObj.has("thumbnailUrl")) {
			profile.setProfileImageURL(pObj.getString("thumbnailUrl"));
		}

		return profile;
	}
}