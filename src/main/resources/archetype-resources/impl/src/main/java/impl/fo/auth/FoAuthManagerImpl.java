package ${package}.impl.fo.auth;

import static ${package}.intf.fo.basic.FoConstants.NULL_REQUEST_BEAN_TIP;

import java.io.IOException;
import java.security.GeneralSecurityException;

import javax.annotation.Resource;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import ${package}.impl.biz.auth.AccessToken;
import ${package}.impl.biz.auth.AccessTokenRepo;
import ${package}.impl.biz.auth.AuthService;
import ${package}.impl.biz.auth.RandomLoginCode;
import ${package}.impl.biz.auth.RandomLoginCodeRepo;
import ${package}.impl.biz.user.User;
import ${package}.impl.biz.user.UserRepo;
import ${package}.impl.fo.common.FoManagerImplBase;
import ${package}.impl.util.infrahelp.beanvalidae.MyValidator;
import ${package}.impl.util.tools.lang.MyDuplet;
import ${package}.intf.fo.auth.FoAuthManager;
import ${package}.intf.fo.auth.FoAuthTokenResult;
import ${package}.intf.fo.auth.FoGenRandomLoginCodeRequest;
import ${package}.intf.fo.auth.FoLocalLoginRequest;
import ${package}.intf.fo.auth.FoRandomCodeLoginRequest;
import ${package}.intf.fo.auth.FoRefreshTokenRequest;
import ${package}.intf.fo.auth.FoRegisterRequest;
import ${package}.intf.fo.auth.FoSocialAuthCodeLoginRequest;
import ${package}.intf.fo.auth.FoSocialLoginByTokenRequest;
import ${package}.intf.fo.basic.FoConstants;
import ${package}.intf.fo.basic.FoResponse;
import com.github.scribejava.apis.FacebookApi;
import com.github.scribejava.apis.GoogleApi20;
import com.github.scribejava.apis.google.GoogleToken;
import com.github.scribejava.core.builder.ServiceBuilder;
import com.github.scribejava.core.model.Token;
import com.github.scribejava.core.model.Verifier;
import com.github.scribejava.core.oauth.OAuth20Service;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.apache.ApacheHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.restfb.DefaultFacebookClient;
import com.restfb.FacebookClient;
import com.restfb.Parameter;
import com.restfb.Version;

/**
 * 
 * @author chenjianjx@gmail.com
 *
 */
@Service("foAuthManager")
public class FoAuthManagerImpl extends FoManagerImplBase implements
		FoAuthManager {

	private static final Logger logger = LoggerFactory
			.getLogger(FoAuthManagerImpl.class);

	@Resource
	AccessTokenRepo accessTokenRepo;

	@Resource
	UserRepo userRepo;

	@Resource
	RandomLoginCodeRepo randomCodeRepo;

	@Resource
	MyValidator myValidator;

	@Resource
	AuthService authService;

	HttpTransport googleHttpTransport = new ApacheHttpTransport();
	JsonFactory googleJsonFactory = new JacksonFactory();

	private String googleClientId;
	private String googleClientSecret;

	private String facebookClientId;
	private String facebookClientSecret;

	@Override
	public FoResponse<FoAuthTokenResult> localOauth2Login(
			FoLocalLoginRequest request) {
		String error = myValidator.validateBeanFastFail(request,
				NULL_REQUEST_BEAN_TIP);
		if (error != null) {
			return FoResponse.userErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST, error);
		}

		String principal = User.decidePrincipalFromLocal(request.getEmail());
		User user = userRepo.getUserByPrincipal(principal);

		if (user == null) {
			return FoResponse.userErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST, "invalid email");
		}

		// now compare password
		String encodedPassword = authService.encodePasswordOrRandomCode(request
				.getPassword());

		if (!encodedPassword.equals(user.getPassword())) {
			return FoResponse.userErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST, "invalid password");
		}

		// ok, do the token
		return buildAuthTokenResponse(user, request.isLongSession());
	}

	@Override
	public FoResponse<FoAuthTokenResult> localRandomCodeLogin(
			FoRandomCodeLoginRequest request) {

		String error = myValidator.validateBeanFastFail(request,
				NULL_REQUEST_BEAN_TIP);
		if (error != null) {
			return FoResponse.userErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST, error);
		}

		String principal = User.decidePrincipalFromLocal(request.getEmail());
		User user = userRepo.getUserByPrincipal(principal);

		if (user == null) {
			return FoResponse.userErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST, "Invalid email.");
		}

		// now compare the codes
		String encodedCode = authService.encodePasswordOrRandomCode(request
				.getRandomCode());
		RandomLoginCode rlc = randomCodeRepo.getByUserId(user.getId());
		if (rlc == null || !encodedCode.equals(rlc.getCodeStr())) {
			return FoResponse.userErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST,
					"Invalid random code.");
		}

		if (rlc.hasExpired()) {
			return FoResponse.userErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST,
					"Random code expired.");
		}

		// ok, do the token
		FoResponse<FoAuthTokenResult> response = buildAuthTokenResponse(user,
				false);

		// finally delete the code
		randomCodeRepo.deleteByUserId(user.getId());
		return response;
	}

	private FoResponse<FoAuthTokenResult> buildAuthTokenResponse(User user,
			boolean longSession) {
		AccessToken at = authService
				.genNewAccessTokenForUser(user, longSession);
		return buildAuthTokenResponse(at, user);

	}

	private FoResponse<FoAuthTokenResult> buildAuthTokenResponse(
			AccessToken accessToken, User user) {
		FoAuthTokenResult result = new FoAuthTokenResult();
		result.setAccessToken(accessToken.getTokenStr());
		result.setRefreshToken(accessToken.getRefreshTokenStr());
		result.setExpiresIn(accessToken.getLifespan());
		result.defaultTokenType();
		result.setUserPrincipal(user.getPrincipal());
		return FoResponse.success(result);
	}

	@Override
	public FoResponse<FoAuthTokenResult> localRegister(FoRegisterRequest request) {

		String error = myValidator.validateBeanFastFail(request,
				NULL_REQUEST_BEAN_TIP);
		if (error != null) {
			return FoResponse.userErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST, error);
		}

		String email = request.getEmail();
		if (!myValidator.isEmailValid(email)) {
			return FoResponse.userErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST, "Invalid Email");
		}

		// validate user existence

		String principalName = User.decidePrincipalFromLocal(email);
		User existingUser = userRepo.getUserByPrincipal(principalName);

		if (existingUser != null) {
			String err = "This email already exists";
			return FoResponse.userErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST, err);
		}

		// save it
		String source = User.SOURCE_LOCAL;
		String encodedPassword = authService.encodePasswordOrRandomCode(request
				.getPassword());
		User user1 = new User();
		user1.setEmail(email);
		user1.setPassword(encodedPassword);
		user1.setSource(source);
		user1.setCreatedBy(user1.getPrincipal());
		userRepo.saveNewUser(user1);

		User user = user1;
		return buildAuthTokenResponse(user, false);
	}

	@Override
	public FoResponse<Void> oauth2Logout(Long currentUserId, String tokenStr) {
		if (currentUserId == null) {
			// not login yet? say nothing harsh
			return FoResponse.success(null);
		}
		tokenStr = StringUtils.trimToNull(tokenStr);
		if (tokenStr == null) {
			// say nothing harsh
			return FoResponse.success(null);
		}

		AccessToken at = accessTokenRepo.getByTokenStr(tokenStr);
		if (at == null) {
			// invalid token? say nothing harsh
			return FoResponse.success(null);
		}

		if (at.getUserId() != currentUserId) {
			// not his or her token? say nothing harsh
			return FoResponse.success(null);
		}
		accessTokenRepo.deleteByTokenStr(tokenStr);
		return FoResponse.success(null);
	}

	@Override
	public FoResponse<Void> generateRandomLoginCode(
			FoGenRandomLoginCodeRequest request) {
		String error = myValidator.validateBeanFastFail(request,
				NULL_REQUEST_BEAN_TIP);
		if (error != null) {
			return FoResponse.userErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST, error);
		}

		String principalName = User
				.decidePrincipalFromLocal(request.getEmail());
		User user = userRepo.getUserByPrincipal(principalName);

		if (user == null) {
			return FoResponse.userErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST, "Invalid email.");
		}

		String randomCodeStr = authService.generateRandomLoginCode();
		RandomLoginCode randomCodeObj = authService.saveNewRandomCodeForUser(
				user, randomCodeStr);

		// send the email
		try {
			authService.sendEmailForRandomLoginCodeAsync(user, randomCodeObj,
					randomCodeStr);
		} catch (Exception e) {
			logger.error("fail to send random login codel asyncly  for user "
					+ user.getPrincipal(), e);
		}

		return FoResponse.success(null);
	}

	@Override
	public FoResponse<FoAuthTokenResult> socialLoginByToken(
			FoSocialLoginByTokenRequest request) {

		String error = myValidator.validateBeanFastFail(request,
				NULL_REQUEST_BEAN_TIP);
		if (error != null) {
			return FoResponse.devErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST, error, null);
		}
		String source = request.getSource();
		if (!User.isValidSocialAccountSource(source)) {
			return FoResponse.devErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST,
					"unsupported source: " + source, null);
		}

		boolean longSession = request.isLongSession();

		String socialToken = request.getToken();
		MyDuplet<String, FoResponse<FoAuthTokenResult>> emailOrErrResp = getEmailFromSocialSiteToken(
				source, socialToken);
		return handleSocialSiteEmailResult(source, longSession, emailOrErrResp);
	}

	@Override
	public FoResponse<FoAuthTokenResult> socialLoginByAuthCode(
			FoSocialAuthCodeLoginRequest request) {

		String error = myValidator.validateBeanFastFail(request,
				NULL_REQUEST_BEAN_TIP);
		if (error != null) {
			return FoResponse.devErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST, error, null);
		}
		String source = request.getSource();
		if (!User.isValidSocialAccountSource(source)) {
			return FoResponse.devErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST,
					"unsupported source: " + source, null);
		}

		boolean longSession = request.isLongSession();

		String authCode = request.getAuthCode();
		MyDuplet<String, FoResponse<FoAuthTokenResult>> emailOrErrResp = getEmailFromSocialSiteAuthCode(
				source, authCode);
		return handleSocialSiteEmailResult(source, longSession, emailOrErrResp);
	}

	private FoResponse<FoAuthTokenResult> handleSocialSiteEmailResult(
			String source, boolean longSession,
			MyDuplet<String, FoResponse<FoAuthTokenResult>> emailOrErrResp) {
		if (emailOrErrResp.right != null) {
			return emailOrErrResp.right;
		}

		String email = emailOrErrResp.left;
		String principal = User.decidePrincipal(source, email);
		User existingUser = userRepo.getUserByPrincipal(principal);

		if (existingUser == null) {
			existingUser = new User();
			existingUser.setEmail(email);
			existingUser.setPassword(null);
			existingUser.setSource(source);
			existingUser.setCreatedBy(existingUser.getPrincipal());
			userRepo.saveNewUser(existingUser);

		}
		// ok, do the token
		return buildAuthTokenResponse(existingUser, longSession);
	}

	/**
	 * 
	 * @param source
	 * @param socialToken
	 *            left = email, right = error response if any
	 * @return
	 */
	private MyDuplet<String, FoResponse<FoAuthTokenResult>> getEmailFromSocialSiteToken(
			String source, String socialToken) {

		if (User.SOURCE_GOOGLE.equals(source)) {
			return getEmailFromGoogleToken(socialToken);
		}

		if (User.SOURCE_FACEBOOK.equals(source)) {
			return getEmailFromFacebookToken(socialToken);
		}

		throw new IllegalStateException("Unreachable code");
	}

	/**
	 * 
	 * @param source
	 * @param authCode
	 *            left = email, right = error response if any
	 * @return
	 */
	private MyDuplet<String, FoResponse<FoAuthTokenResult>> getEmailFromSocialSiteAuthCode(
			String source, String authCode) {
		if (User.SOURCE_GOOGLE.equals(source)) {
			return getEmailFromGoogleAuthCode(authCode);
		}

		if (User.SOURCE_FACEBOOK.equals(source)) {
			return getEmailFromFacebookAuthCode(authCode);
		}

		throw new IllegalStateException("Unreachable code");
	}

	private MyDuplet<String, FoResponse<FoAuthTokenResult>> getEmailFromFacebookAuthCode(
			String authCode) {

		// exchange the code for token
		final OAuth20Service service = new ServiceBuilder()
				.apiKey(facebookClientId)
				.apiSecret(facebookClientSecret)
				.scope("email")
				.callback("https://www.facebook.com/connect/login_success.html")
				.build(FacebookApi.instance());
		Token facebookTokenObj = service.getAccessToken(new Verifier(authCode));
		String token = facebookTokenObj.getToken();
		// get email by token
		return this.getEmailFromFacebookToken(token);

	}

	private MyDuplet<String, FoResponse<FoAuthTokenResult>> getEmailFromFacebookToken(
			String socialToken) {
		FacebookClient facebookClient = new DefaultFacebookClient(socialToken,
				Version.VERSION_2_5);
		com.restfb.types.User user = facebookClient.fetchObject("me",
				com.restfb.types.User.class,
				Parameter.with("fields", "id,name,email"));
		if (user == null) {
			FoResponse<FoAuthTokenResult> errResp = FoResponse.devErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST,
					"invalid facebook access token", null);
			return MyDuplet.newInstance(null, errResp);
		}

		String email = user.getEmail();
		if (email == null) {
			FoResponse<FoAuthTokenResult> errResp = FoResponse
					.devErrResponse(
							FoConstants.FEC_OAUTH2_INVALID_REQUEST,
							"cannot extract email from this facebook access token. please check the scope used for facebook connect",
							null);
			return MyDuplet.newInstance(null, errResp);
		}
		return MyDuplet.newInstance(email, null);
	}

	private MyDuplet<String, FoResponse<FoAuthTokenResult>> getEmailFromGoogleToken(
			String idToken) {
		GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
				googleHttpTransport, googleJsonFactory).build();
		GoogleIdToken git = verifyGoogleIdToken(verifier, idToken);
		if (git == null) {
			FoResponse<FoAuthTokenResult> errResp = FoResponse.devErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST,
					"invalid google open id token", null);
			return MyDuplet.newInstance(null, errResp);
		}
		String email = git.getPayload().getEmail();
		if (email == null) {
			FoResponse<FoAuthTokenResult> errResp = FoResponse
					.devErrResponse(
							FoConstants.FEC_OAUTH2_INVALID_REQUEST,
							"cannot extract email from this open id token. please check the scope used for google sign-in",
							null);
			return MyDuplet.newInstance(null, errResp);
		}
		return MyDuplet.newInstance(email, null);
	}

	private MyDuplet<String, FoResponse<FoAuthTokenResult>> getEmailFromGoogleAuthCode(
			String authCode) {
		// exchange the code for token
		final OAuth20Service service = new ServiceBuilder()
				.apiKey(googleClientId).apiSecret(googleClientSecret)
				.scope("email")
				// replace with desired scope
				.callback("urn:ietf:wg:oauth:2.0:oob")
				.build(GoogleApi20.instance());
		GoogleToken googleTokenObj = (GoogleToken) service
				.getAccessToken(new Verifier(authCode));
		String idToken = googleTokenObj.getOpenIdToken();
		// get email by token
		return this.getEmailFromGoogleToken(idToken);
	}

	private GoogleIdToken verifyGoogleIdToken(GoogleIdTokenVerifier verifier,
			String idToken) {
		try {
			return verifier.verify(idToken);
		} catch (IOException e) {
			throw new RuntimeException(e);
		} catch (GeneralSecurityException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public FoResponse<FoAuthTokenResult> oauth2RefreshToken(
			FoRefreshTokenRequest request) {

		String error = myValidator.validateBeanFastFail(request,
				NULL_REQUEST_BEAN_TIP);
		if (error != null) {
			return FoResponse.devErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST, error, null);
		}

		String refreshToken = request.getRefreshToken();

		AccessToken at = accessTokenRepo.getByRefreshTokenStr(refreshToken);

		if (at == null) {
			return FoResponse.devErrResponse(
					FoConstants.FEC_OAUTH2_INVALID_REQUEST,
					"invalid refresh token", null);
		}

		User user = userRepo.getUserById(at.getUserId());

		at.setTokenStr(authService.generateAccessTokenStr());
		at.setRefreshTokenStr(authService.generateRefreshTokenStr());
		at.setExpiresAt(authService.calExpiresAt(at.getLifespan()));
		accessTokenRepo.updateAccessToken(at);

		// ok, do the token
		return buildAuthTokenResponse(at, user);

	}

	@Value("${googleClientId}")
	public void setGoogleClientId(String googleClientId) {
		this.googleClientId = StringUtils.trimToNull(googleClientId);
	}

	@Value("${googleClientSecret}")
	public void setGoogleClientSecret(String googleClientSecret) {
		this.googleClientSecret = StringUtils.trimToNull(googleClientSecret);
	}

	@Value("${facebookClientId}")
	public void setFacebookClientId(String facebookClientId) {
		this.facebookClientId = StringUtils.trimToNull(facebookClientId);
	}

	@Value("${facebookClientSecret}")
	public void setFacebookClientSecret(String facebookClientSecret) {
		this.facebookClientSecret = StringUtils
				.trimToNull(facebookClientSecret);
	}
}
