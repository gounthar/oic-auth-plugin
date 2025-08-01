/*
 * The MIT License
 *
 * Copyright (c) 2016  Michael Bischoff & GeriMedica - www.gerimedica.nl
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.plugins.oic;

import static org.apache.commons.lang.StringUtils.isNotBlank;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.nimbusds.jose.EncryptionMethod;
import com.nimbusds.jose.JWEAlgorithm;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.oauth2.sdk.GrantType;
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.token.AccessToken;
import com.nimbusds.oauth2.sdk.token.BearerAccessToken;
import com.nimbusds.oauth2.sdk.token.RefreshToken;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Descriptor.FormException;
import hudson.model.Failure;
import hudson.model.User;
import hudson.security.ChainedServletFilter2;
import hudson.security.SecurityRealm;
import hudson.tasks.Mailer;
import hudson.util.FormValidation;
import hudson.util.Secret;
import io.burt.jmespath.Expression;
import io.burt.jmespath.JmesPath;
import io.burt.jmespath.RuntimeConfiguration;
import io.burt.jmespath.jcf.JcfRuntime;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.InvalidObjectException;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import jenkins.model.IdStrategy;
import jenkins.model.IdStrategyDescriptor;
import jenkins.model.Jenkins;
import jenkins.security.ApiTokenProperty;
import jenkins.security.FIPS140;
import jenkins.security.SecurityListener;
import jenkins.util.SystemProperties;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.Header;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.pac4j.core.context.CallContext;
import org.pac4j.core.context.FrameworkParameters;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.credentials.Credentials;
import org.pac4j.core.exception.TechnicalException;
import org.pac4j.core.exception.http.HttpAction;
import org.pac4j.core.exception.http.RedirectionAction;
import org.pac4j.core.http.callback.NoParameterCallbackUrlResolver;
import org.pac4j.core.profile.creator.ProfileCreator;
import org.pac4j.jee.context.JEEContextFactory;
import org.pac4j.jee.context.JEEFrameworkParameters;
import org.pac4j.jee.context.session.JEESessionStoreFactory;
import org.pac4j.jee.http.adapter.JEEHttpActionAdapter;
import org.pac4j.oidc.client.OidcClient;
import org.pac4j.oidc.config.OidcConfiguration;
import org.pac4j.oidc.credentials.authenticator.OidcAuthenticator;
import org.pac4j.oidc.metadata.OidcOpMetadataResolver;
import org.pac4j.oidc.metadata.StaticOidcOpMetadataResolver;
import org.pac4j.oidc.profile.OidcProfile;
import org.pac4j.oidc.profile.creator.TokenValidator;
import org.pac4j.oidc.redirect.OidcRedirectionActionBuilder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.util.Assert;

/**
 * Login with OpenID Connect / OAuth 2
 *
 * @author Michael Bischoff
 * @author Steve Arch
 */
public class OicSecurityRealm extends SecurityRealm implements Serializable {
    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(OicSecurityRealm.class.getName());
    private IdStrategy userIdStrategy;
    private IdStrategy groupIdStrategy;

    public static enum TokenAuthMethod {
        client_secret_basic(ClientAuthenticationMethod.CLIENT_SECRET_BASIC),
        client_secret_post(ClientAuthenticationMethod.CLIENT_SECRET_POST);

        private ClientAuthenticationMethod clientAuthMethod;

        TokenAuthMethod(ClientAuthenticationMethod clientAuthMethod) {
            this.clientAuthMethod = clientAuthMethod;
        }

        ClientAuthenticationMethod toClientAuthenticationMethod() {
            return clientAuthMethod;
        }
    };

    private static final String ID_TOKEN_REQUEST_ATTRIBUTE = "oic-id-token";
    private static final String NO_SECRET = "none";
    private static final String SESSION_POST_LOGIN_REDIRECT_URL_KEY = "oic-redirect-on-login-url";

    private final String clientId;
    private final Secret clientSecret;

    /** @deprecated see {@link OicServerWellKnownConfiguration#getWellKnownOpenIDConfigurationUrl()} */
    @Deprecated
    private transient String wellKnownOpenIDConfigurationUrl;

    /** @deprecated see {@link OicServerConfiguration#getTokenServerUrl()} */
    @Deprecated
    private transient String tokenServerUrl;

    /** @deprecated see {@link OicServerConfiguration#getJwksServerUrl()} */
    @Deprecated
    private transient String jwksServerUrl;

    /** @deprecated see {@link OicServerConfiguration#getTokenAuthMethod()} */
    @Deprecated
    private transient TokenAuthMethod tokenAuthMethod;

    /** @deprecated see {@link OicServerConfiguration#getAuthorizationServerUrl()} */
    @Deprecated
    private transient String authorizationServerUrl;

    /** @deprecated see {@link OicServerConfiguration#getUserInfoServerUrl()} */
    @Deprecated
    private transient String userInfoServerUrl;

    private String userNameField = "sub";
    private transient Expression<Object> userNameFieldExpr = null;
    private String tokenFieldToCheckKey = null;
    private transient Expression<Object> tokenFieldToCheckExpr = null;
    private String tokenFieldToCheckValue = null;
    private String fullNameFieldName = null;
    private transient Expression<Object> fullNameFieldExpr = null;
    private String emailFieldName = null;
    private transient Expression<Object> emailFieldExpr = null;
    private String groupsFieldName = null;
    private transient Expression<Object> groupsFieldExpr = null;
    private transient Expression<Object> avatarFieldExpr = null;
    private transient String simpleGroupsFieldName = null;
    private transient String nestedGroupFieldName = null;

    /** @deprecated see {@link OicServerConfiguration#getScopes()} */
    @Deprecated
    private transient String scopes = null;

    private final boolean disableSslVerification;
    private boolean logoutFromOpenidProvider = true;

    /** @deprecated see {@link OicServerConfiguration#getEndSessionUrl()} */
    @Deprecated
    private transient String endSessionEndpoint = null;

    private String postLogoutRedirectUrl;
    private boolean escapeHatchEnabled = false;
    private String escapeHatchUsername = null;
    private Secret escapeHatchSecret = null;
    private String escapeHatchGroup = null;

    @Deprecated
    /** @deprecated with no replacement.  See sub classes of {@link OicServerConfiguration} */
    private transient String automanualconfigure = null;

    @Deprecated
    /** @deprecated see {@link OicServerWellKnownConfiguration#isUseRefreshTokens()} */
    private transient boolean useRefreshTokens = false;

    private OicServerConfiguration serverConfiguration;

    /** @deprecated with no replacement.  See sub classes of {@link OicServerConfiguration} */
    @Deprecated
    private String overrideScopes = null;

    /** Flag indicating if root url should be taken from config or request
     *
     * Taking root url from request requires a well configured proxy/ingress
     */
    private boolean rootURLFromRequest = false;

    /** Flag to send scopes in code token request
     */
    private boolean sendScopesInTokenRequest = false;

    /** Flag to enable PKCE challenge
     */
    private boolean pkceEnabled = false;

    /** Flag to disable JWT signature verification
     */
    private boolean disableTokenVerification = false;

    /** Flag to disable nonce security
     */
    private boolean nonceDisabled = false;

    /** Flag to disable token expiration check
     */
    private boolean tokenExpirationCheckDisabled = false;

    /** Flag to enable traditional Jenkins API token based access (no OicSession needed)
     */
    private boolean allowTokenAccessWithoutOicSession = false;

    /** Additional number of seconds to add to token expiration
     */
    private Long allowedTokenExpirationClockSkewSeconds = 60L;

    /**
     * Flag when set to true will cause enforce nonce checking in the refresh flow.
     * https://openid.net/specs/openid-connect-core-1_0.html#RefreshTokenResponse states the nonce claim should not be present
     * and when faced with a provider that adheres to this if using a nonce, the library attempts to validate the "missing" nonce and fails.
     * So this is disabled by default, but if the provider does send the nonce in the claim then we do need to verify it.
     * But there is no way to know ahead of time if the server is going to send this or not.
     */
    private static boolean checkNonceInRefreshFlow =
            SystemProperties.getBoolean(OicSecurityRealm.class.getName() + ".checkNonceInRefreshFlow", false);

    /** old field that had an '/' implicitly added at the end,
     * transient because we no longer want to have this value stored
     * but it's still needed for backwards compatibility */
    @Deprecated
    private transient String endSessionUrl;

    /** Random generator needed for robust random wait
     */
    private static final Random RANDOM = new Random();

    /** Clock used for token expiration check
     */
    private static final Clock CLOCK = Clock.systemUTC();

    /** Runtime context to compile JMESPath
     */
    private static final JmesPath<Object> JMESPATH = new JcfRuntime(
            new RuntimeConfiguration.Builder().withSilentTypeErrors(true).build());

    /**
     * Resource retriever configured with an appropriate SSL Factory based on {@link #isDisableSslVerification()}
     */
    private transient ProxyAwareResourceRetriever proxyAwareResourceRetriever;

    @SuppressFBWarnings(value = "SE_BAD_FIELD", justification = "we are not using standard serialization")
    private List<LoginQueryParameter> loginQueryParameters;

    @SuppressFBWarnings(value = "SE_BAD_FIELD", justification = "we are not using standard serialization")
    private List<LogoutQueryParameter> logoutQueryParameters;

    @DataBoundConstructor
    public OicSecurityRealm(
            String clientId,
            Secret clientSecret,
            OicServerConfiguration serverConfiguration,
            Boolean disableSslVerification,
            IdStrategy userIdStrategy,
            IdStrategy groupIdStrategy)
            throws IOException, FormException {
        // Needed in DataBoundSetter
        this.disableSslVerification = Util.fixNull(disableSslVerification, Boolean.FALSE);
        if (FIPS140.useCompliantAlgorithms() && this.disableSslVerification) {
            throw new FormException(
                    Messages.OicSecurityRealm_DisableSslVerificationFipsMode(), "disableSslVerification");
        }
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.serverConfiguration = serverConfiguration;
        this.userIdStrategy = userIdStrategy;
        this.groupIdStrategy = groupIdStrategy;
        this.avatarFieldExpr =
                compileJMESPath("picture", "avatar field"); // Default on OIDC spec, part of profile claim
    }

    @SuppressWarnings("deprecated")
    protected Object readResolve() throws ObjectStreamException {
        // Fail if migrating to a FIPS non-compliant config
        if (FIPS140.useCompliantAlgorithms()) {
            if (isDisableSslVerification()) {
                throw new IllegalStateException(Messages.OicSecurityRealm_DisableSslVerificationFipsMode());
            }
            if (isDisableTokenVerification()) {
                throw new IllegalStateException(Messages.OicSecurityRealm_DisableTokenVerificationFipsMode());
            }
        }

        if (!Strings.isNullOrEmpty(endSessionUrl)) {
            this.endSessionEndpoint = endSessionUrl + "/";
        }

        // backward compatibility with wrong groupsFieldName split
        if (Strings.isNullOrEmpty(this.groupsFieldName) && !Strings.isNullOrEmpty(this.simpleGroupsFieldName)) {
            String originalGroupFieldName = this.simpleGroupsFieldName;
            if (!Strings.isNullOrEmpty(this.nestedGroupFieldName)) {
                originalGroupFieldName += "[]." + this.nestedGroupFieldName;
            }
            this.setGroupsFieldName(originalGroupFieldName);
        } else {
            this.setGroupsFieldName(this.groupsFieldName);
        }
        // ensure Field JMESPath are computed
        this.avatarFieldExpr =
                this.compileJMESPath("picture", "avatar field"); // Default on OIDC spec, part of profile claim
        this.setUserNameField(this.userNameField);
        this.setEmailFieldName(this.emailFieldName);
        this.setFullNameFieldName(this.fullNameFieldName);
        this.setTokenFieldToCheckKey(this.tokenFieldToCheckKey);
        // ensure escapeHatchSecret is encrypted
        this.setEscapeHatchSecret(this.escapeHatchSecret);

        // validate this option in FIPS env or not
        try {
            this.setEscapeHatchEnabled(this.escapeHatchEnabled);
        } catch (FormException e) {
            throw new IllegalArgumentException(e.getFormField() + ": " + e.getMessage());
        }

        try {
            if (automanualconfigure != null) {
                if ("auto".equals(automanualconfigure)) {
                    OicServerWellKnownConfiguration conf =
                            new OicServerWellKnownConfiguration(wellKnownOpenIDConfigurationUrl);
                    conf.setScopesOverride(this.overrideScopes);
                    serverConfiguration = conf;
                } else {
                    OicServerManualConfiguration conf = new OicServerManualConfiguration(
                            /* TODO */ "migrated", tokenServerUrl, authorizationServerUrl);
                    if (tokenAuthMethod != null) {
                        conf.setTokenAuthMethod(tokenAuthMethod);
                    }
                    conf.setEndSessionUrl(endSessionEndpoint);
                    conf.setJwksServerUrl(jwksServerUrl);
                    conf.setScopes(scopes != null ? scopes : "openid email");
                    conf.setUseRefreshTokens(useRefreshTokens);
                    conf.setUserInfoServerUrl(userInfoServerUrl);
                    serverConfiguration = conf;
                }
            }
        } catch (FormException e) {
            // FormException does not override toString() so looses info on the fields set and the message may not have
            // context
            // extract if into a better message until this is fixed.
            ObjectStreamException ose = new InvalidObjectException(e.getFormField() + ": " + e.getMessage());
            ose.initCause(e);
            throw ose;
        }
        createProxyAwareResourceRetriver();
        return this;
    }

    @DataBoundSetter
    public void setLoginQueryParameters(List<LoginQueryParameter> values) {
        this.loginQueryParameters = values;
    }

    public List<LoginQueryParameter> getLoginQueryParameters() {
        return loginQueryParameters;
    }

    @DataBoundSetter
    public void setLogoutQueryParameters(List<LogoutQueryParameter> values) {
        this.logoutQueryParameters = values;
    }

    public List<LogoutQueryParameter> getLogoutQueryParameters() {
        return logoutQueryParameters;
    }

    public String getClientId() {
        return clientId;
    }

    public Secret getClientSecret() {
        return clientSecret == null ? Secret.fromString(NO_SECRET) : clientSecret;
    }

    @Restricted(NoExternalUse.class) // jelly access
    public OicServerConfiguration getServerConfiguration() {
        return serverConfiguration;
    }

    public String getUserNameField() {
        return userNameField;
    }

    @Restricted(NoExternalUse.class)
    public boolean isMissingIdStrategy() {
        return userIdStrategy == null || groupIdStrategy == null;
    }

    @Override
    public IdStrategy getUserIdStrategy() {
        if (userIdStrategy != null) {
            return userIdStrategy;
        } else {
            return IdStrategy.CASE_INSENSITIVE;
        }
    }

    public String getTokenFieldToCheckKey() {
        return tokenFieldToCheckKey;
    }

    public String getTokenFieldToCheckValue() {
        return tokenFieldToCheckValue;
    }

    public String getFullNameFieldName() {
        return fullNameFieldName;
    }

    public String getEmailFieldName() {
        return emailFieldName;
    }

    public String getGroupsFieldName() {
        return groupsFieldName;
    }

    @Override
    public IdStrategy getGroupIdStrategy() {
        if (groupIdStrategy != null) {
            return groupIdStrategy;
        } else {
            return IdStrategy.CASE_INSENSITIVE;
        }
    }

    public boolean isDisableSslVerification() {
        return disableSslVerification;
    }

    public boolean isLogoutFromOpenidProvider() {
        return logoutFromOpenidProvider;
    }

    public String getPostLogoutRedirectUrl() {
        return postLogoutRedirectUrl;
    }

    public boolean isEscapeHatchEnabled() {
        return escapeHatchEnabled;
    }

    public String getEscapeHatchUsername() {
        return escapeHatchUsername;
    }

    public Secret getEscapeHatchSecret() {
        return escapeHatchSecret;
    }

    public String getEscapeHatchGroup() {
        return escapeHatchGroup;
    }

    public boolean isRootURLFromRequest() {
        return rootURLFromRequest;
    }

    public boolean isSendScopesInTokenRequest() {
        return sendScopesInTokenRequest;
    }

    public boolean isPkceEnabled() {
        return pkceEnabled;
    }

    public boolean isDisableTokenVerification() {
        return disableTokenVerification;
    }

    public boolean isNonceDisabled() {
        return nonceDisabled;
    }

    public boolean isTokenExpirationCheckDisabled() {
        return tokenExpirationCheckDisabled;
    }

    public boolean isAllowTokenAccessWithoutOicSession() {
        return allowTokenAccessWithoutOicSession;
    }

    public Long getAllowedTokenExpirationClockSkewSeconds() {
        return allowedTokenExpirationClockSkewSeconds;
    }

    @PostConstruct
    @Restricted(NoExternalUse.class)
    public void createProxyAwareResourceRetriver() {
        proxyAwareResourceRetriever =
                ProxyAwareResourceRetriever.createProxyAwareResourceRetriver(isDisableSslVerification());
    }

    ProxyAwareResourceRetriever getResourceRetriever() {
        return proxyAwareResourceRetriever;
    }

    private OidcConfiguration buildOidcConfiguration() {
        // TODO cache this and use the well known if available.
        OidcConfiguration conf = new CustomOidcConfiguration(this.isDisableSslVerification());
        conf.setClientId(clientId);
        conf.setSecret(clientSecret.getPlainText());

        // TODO what do we prefer?
        // conf.setPreferredJwsAlgorithm(JWSAlgorithm.HS256);
        // set many more as needed...

        OIDCProviderMetadata oidcProviderMetadata = serverConfiguration.toProviderMetadata();
        filterNonFIPS140CompliantAlgorithms(oidcProviderMetadata);
        OidcOpMetadataResolver opMetadataResolver;
        if (this.isDisableTokenVerification()) {
            conf.setAllowUnsignedIdTokens(true);
            opMetadataResolver = new StaticOidcOpMetadataResolver(conf, oidcProviderMetadata) {
                @Override
                protected TokenValidator createTokenValidator() {
                    return new AnythingGoesTokenValidator();
                }
            };
        } else {
            opMetadataResolver = new StaticOidcOpMetadataResolver(conf, oidcProviderMetadata);
        }
        conf.setOpMetadataResolver(opMetadataResolver);
        if (oidcProviderMetadata.getScopes() != null) {
            // auto configuration does not need to supply scopes
            conf.setScope(oidcProviderMetadata.getScopes().toString());
        }
        conf.setUseNonce(!this.nonceDisabled);
        if (allowedTokenExpirationClockSkewSeconds != null) {
            conf.setMaxClockSkew(allowedTokenExpirationClockSkewSeconds.intValue());
        }
        conf.setResourceRetriever(getResourceRetriever());
        if (this.isPkceEnabled()) {
            conf.setPkceMethod(CodeChallengeMethod.S256);
        } else {
            conf.setDisablePkce(true);
        }
        opMetadataResolver.init();
        if (loginQueryParameters != null && !loginQueryParameters.isEmpty()) {
            for (LoginQueryParameter lqp : loginQueryParameters) {
                conf.addCustomParam(lqp.getKey(), lqp.getValue());
            }
        }
        return conf;
    }

    // Visible for testing
    @Restricted(NoExternalUse.class)
    protected void filterNonFIPS140CompliantAlgorithms(@NonNull OIDCProviderMetadata oidcProviderMetadata) {
        if (FIPS140.useCompliantAlgorithms()) {
            // If FIPS is not enabled, then we don't have to filter the algorithms
            filterJwsAlgorithms(oidcProviderMetadata);
            filterJweAlgorithms(oidcProviderMetadata);
            filterEncryptionMethods(oidcProviderMetadata);
        }
    }

    private void filterEncryptionMethods(@NonNull OIDCProviderMetadata oidcProviderMetadata) {
        if (oidcProviderMetadata.getRequestObjectJWEEncs() != null) {
            List<EncryptionMethod> requestObjectJWEEncs = OicAlgorithmValidatorFIPS140.getFipsCompliantEncryptionMethod(
                    oidcProviderMetadata.getRequestObjectJWEEncs());
            oidcProviderMetadata.setRequestObjectJWEEncs(requestObjectJWEEncs);
        }

        if (oidcProviderMetadata.getAuthorizationJWEEncs() != null) {
            List<EncryptionMethod> authorizationJWEEncs = OicAlgorithmValidatorFIPS140.getFipsCompliantEncryptionMethod(
                    oidcProviderMetadata.getAuthorizationJWEEncs());
            oidcProviderMetadata.setAuthorizationJWEEncs(authorizationJWEEncs);
        }

        if (oidcProviderMetadata.getIDTokenJWEEncs() != null) {
            List<EncryptionMethod> idTokenJWEEncs = OicAlgorithmValidatorFIPS140.getFipsCompliantEncryptionMethod(
                    oidcProviderMetadata.getIDTokenJWEEncs());
            oidcProviderMetadata.setIDTokenJWEEncs(idTokenJWEEncs);
        }

        if (oidcProviderMetadata.getUserInfoJWEEncs() != null) {
            List<EncryptionMethod> userInfoJWEEncs = OicAlgorithmValidatorFIPS140.getFipsCompliantEncryptionMethod(
                    oidcProviderMetadata.getUserInfoJWEEncs());
            oidcProviderMetadata.setUserInfoJWEEncs(userInfoJWEEncs);
        }

        if (oidcProviderMetadata.getRequestObjectJWEEncs() != null) {
            List<EncryptionMethod> requestObjectJweEncs = OicAlgorithmValidatorFIPS140.getFipsCompliantEncryptionMethod(
                    oidcProviderMetadata.getRequestObjectJWEEncs());
            oidcProviderMetadata.setRequestObjectJWEEncs(requestObjectJweEncs);
        }

        if (oidcProviderMetadata.getAuthorizationJWEEncs() != null) {
            List<EncryptionMethod> authJweEncs = OicAlgorithmValidatorFIPS140.getFipsCompliantEncryptionMethod(
                    oidcProviderMetadata.getAuthorizationJWEEncs());
            oidcProviderMetadata.setAuthorizationJWEEncs(authJweEncs);
        }
    }

    private void filterJweAlgorithms(@NonNull OIDCProviderMetadata oidcProviderMetadata) {
        if (oidcProviderMetadata.getIDTokenJWEAlgs() != null) {
            List<JWEAlgorithm> idTokenJWEAlgs =
                    OicAlgorithmValidatorFIPS140.getFipsCompliantJWEAlgorithm(oidcProviderMetadata.getIDTokenJWEAlgs());
            oidcProviderMetadata.setIDTokenJWEAlgs(idTokenJWEAlgs);
        }

        if (oidcProviderMetadata.getUserInfoJWEAlgs() != null) {
            List<JWEAlgorithm> userTokenJWEAlgs = OicAlgorithmValidatorFIPS140.getFipsCompliantJWEAlgorithm(
                    oidcProviderMetadata.getUserInfoJWEAlgs());
            oidcProviderMetadata.setUserInfoJWEAlgs(userTokenJWEAlgs);
        }

        if (oidcProviderMetadata.getRequestObjectJWEAlgs() != null) {
            List<JWEAlgorithm> requestObjectJWEAlgs = OicAlgorithmValidatorFIPS140.getFipsCompliantJWEAlgorithm(
                    oidcProviderMetadata.getRequestObjectJWEAlgs());
            oidcProviderMetadata.setRequestObjectJWEAlgs(requestObjectJWEAlgs);
        }

        if (oidcProviderMetadata.getAuthorizationJWEAlgs() != null) {
            List<JWEAlgorithm> authorizationJWEAlgs = OicAlgorithmValidatorFIPS140.getFipsCompliantJWEAlgorithm(
                    oidcProviderMetadata.getAuthorizationJWEAlgs());
            oidcProviderMetadata.setAuthorizationJWEAlgs(authorizationJWEAlgs);
        }
    }

    private void filterJwsAlgorithms(@NonNull OIDCProviderMetadata oidcProviderMetadata) {
        if (oidcProviderMetadata.getIDTokenJWSAlgs() != null) {
            List<JWSAlgorithm> idTokenJWSAlgs =
                    OicAlgorithmValidatorFIPS140.getFipsCompliantJWSAlgorithm(oidcProviderMetadata.getIDTokenJWSAlgs());
            oidcProviderMetadata.setIDTokenJWSAlgs(idTokenJWSAlgs);
        }

        if (oidcProviderMetadata.getUserInfoJWSAlgs() != null) {
            List<JWSAlgorithm> userInfoJwsAlgo = OicAlgorithmValidatorFIPS140.getFipsCompliantJWSAlgorithm(
                    oidcProviderMetadata.getUserInfoJWSAlgs());
            oidcProviderMetadata.setUserInfoJWSAlgs(userInfoJwsAlgo);
        }

        if (oidcProviderMetadata.getTokenEndpointJWSAlgs() != null) {
            List<JWSAlgorithm> tokenEndpointJWSAlgs = OicAlgorithmValidatorFIPS140.getFipsCompliantJWSAlgorithm(
                    oidcProviderMetadata.getTokenEndpointJWSAlgs());
            oidcProviderMetadata.setTokenEndpointJWSAlgs(tokenEndpointJWSAlgs);
        }

        if (oidcProviderMetadata.getIntrospectionEndpointJWSAlgs() != null) {
            List<JWSAlgorithm> introspectionEndpointJWSAlgs = OicAlgorithmValidatorFIPS140.getFipsCompliantJWSAlgorithm(
                    oidcProviderMetadata.getIntrospectionEndpointJWSAlgs());
            oidcProviderMetadata.setIntrospectionEndpointJWSAlgs(introspectionEndpointJWSAlgs);
        }

        if (oidcProviderMetadata.getRevocationEndpointJWSAlgs() != null) {
            List<JWSAlgorithm> revocationEndpointJWSAlgs = OicAlgorithmValidatorFIPS140.getFipsCompliantJWSAlgorithm(
                    oidcProviderMetadata.getRevocationEndpointJWSAlgs());
            oidcProviderMetadata.setRevocationEndpointJWSAlgs(revocationEndpointJWSAlgs);
        }

        if (oidcProviderMetadata.getRequestObjectJWSAlgs() != null) {
            List<JWSAlgorithm> requestObjectJWSAlgs = OicAlgorithmValidatorFIPS140.getFipsCompliantJWSAlgorithm(
                    oidcProviderMetadata.getRequestObjectJWSAlgs());
            oidcProviderMetadata.setRequestObjectJWSAlgs(requestObjectJWSAlgs);
        }

        if (oidcProviderMetadata.getDPoPJWSAlgs() != null) {
            List<JWSAlgorithm> dPoPJWSAlgs =
                    OicAlgorithmValidatorFIPS140.getFipsCompliantJWSAlgorithm(oidcProviderMetadata.getDPoPJWSAlgs());
            oidcProviderMetadata.setDPoPJWSAlgs(dPoPJWSAlgs);
        }

        if (oidcProviderMetadata.getAuthorizationJWSAlgs() != null) {
            List<JWSAlgorithm> authorizationJWSAlgs = OicAlgorithmValidatorFIPS140.getFipsCompliantJWSAlgorithm(
                    oidcProviderMetadata.getAuthorizationJWSAlgs());
            oidcProviderMetadata.setAuthorizationJWSAlgs(authorizationJWSAlgs);
        }

        if (oidcProviderMetadata.getBackChannelAuthenticationRequestJWSAlgs() != null) {
            List<JWSAlgorithm> backChannelAuthenticationRequestJWSAlgs =
                    OicAlgorithmValidatorFIPS140.getFipsCompliantJWSAlgorithm(
                            oidcProviderMetadata.getBackChannelAuthenticationRequestJWSAlgs());
            oidcProviderMetadata.setBackChannelAuthenticationRequestJWSAlgs(backChannelAuthenticationRequestJWSAlgs);
        }

        if (oidcProviderMetadata.getClientRegistrationAuthnJWSAlgs() != null) {
            List<JWSAlgorithm> clientRegisterationAuth = OicAlgorithmValidatorFIPS140.getFipsCompliantJWSAlgorithm(
                    oidcProviderMetadata.getClientRegistrationAuthnJWSAlgs());
            oidcProviderMetadata.setClientRegistrationAuthnJWSAlgs(clientRegisterationAuth);
        }
    }

    @Restricted(NoExternalUse.class) // exposed for testing only
    protected OidcClient buildOidcClient() {
        OidcConfiguration oidcConfiguration = buildOidcConfiguration();
        OidcClient client = new OidcClient(oidcConfiguration);
        // add the extra settings for the client...
        client.setCallbackUrl(buildOAuthRedirectUrl());
        client.setAuthenticator(new OidcAuthenticator(oidcConfiguration, client));
        // when building the redirect URL by default pac4j adds the "client_name=DOidcClient" query parameter to the
        // redirectURL.
        // OPs will reject this for existing clients as the redirect URL is not the same as previously configured
        client.setCallbackUrlResolver(new NoParameterCallbackUrlResolver());
        return client;
    }

    @DataBoundSetter
    public void setUserNameField(String userNameField) {
        this.userNameField = Util.fixNull(Util.fixEmptyAndTrim(userNameField), "sub");
        this.userNameFieldExpr = compileJMESPath(this.userNameField, "user name field");
    }

    @DataBoundSetter
    public void setTokenFieldToCheckKey(String tokenFieldToCheckKey) {
        this.tokenFieldToCheckKey = Util.fixEmptyAndTrim(tokenFieldToCheckKey);
        this.tokenFieldToCheckExpr = compileJMESPath(this.tokenFieldToCheckKey, "token field to check");
    }

    @DataBoundSetter
    public void setTokenFieldToCheckValue(String tokenFieldToCheckValue) {
        this.tokenFieldToCheckValue = Util.fixEmptyAndTrim(tokenFieldToCheckValue);
    }

    @DataBoundSetter
    public void setFullNameFieldName(String fullNameFieldName) {
        this.fullNameFieldName = Util.fixEmptyAndTrim(fullNameFieldName);
        this.fullNameFieldExpr = compileJMESPath(this.fullNameFieldName, "full name field");
    }

    @DataBoundSetter
    public void setEmailFieldName(String emailFieldName) {
        this.emailFieldName = Util.fixEmptyAndTrim(emailFieldName);
        this.emailFieldExpr = compileJMESPath(this.emailFieldName, "email field");
    }

    protected static Expression<Object> compileJMESPath(String str, String logComment) {
        if (str == null) {
            return null;
        }

        try {
            Expression<Object> expr = JMESPATH.compile(str);
            if (expr == null && logComment != null) {
                LOGGER.warning(logComment + " with config '" + str + "' is an invalid JMESPath expression ");
            }
            return expr;
        } catch (RuntimeException e) {
            if (logComment != null) {
                LOGGER.warning(logComment + " config failed " + e.toString());
            }
        }
        return null;
    }

    private Object applyJMESPath(Expression<Object> expression, Object map) {
        return expression.search(map);
    }

    @DataBoundSetter
    public void setGroupsFieldName(String groupsFieldName) {
        this.groupsFieldName = Util.fixEmptyAndTrim(groupsFieldName);
        this.groupsFieldExpr = this.compileJMESPath(this.groupsFieldName, "groups field");
    }

    @DataBoundSetter
    public void setLogoutFromOpenidProvider(boolean logoutFromOpenidProvider) {
        this.logoutFromOpenidProvider = logoutFromOpenidProvider;
    }

    @DataBoundSetter
    public void setPostLogoutRedirectUrl(String postLogoutRedirectUrl) {
        this.postLogoutRedirectUrl = Util.fixEmptyAndTrim(postLogoutRedirectUrl);
    }

    @DataBoundSetter
    public void setEscapeHatchEnabled(boolean escapeHatchEnabled) throws FormException {
        if (FIPS140.useCompliantAlgorithms() && escapeHatchEnabled) {
            throw new FormException("Escape Hatch cannot be enabled in FIPS environment", "escapeHatchEnabled");
        }
        this.escapeHatchEnabled = escapeHatchEnabled;
    }

    @DataBoundSetter
    public void setEscapeHatchUsername(String escapeHatchUsername) {
        this.escapeHatchUsername = Util.fixEmptyAndTrim(escapeHatchUsername);
    }

    @DataBoundSetter
    public void setEscapeHatchSecret(Secret escapeHatchSecret) {
        if (escapeHatchSecret != null) {
            // ensure escapeHatchSecret is BCrypt hash
            String escapeHatchString = Secret.toString(escapeHatchSecret);

            final Pattern BCryptPattern = Pattern.compile("\\A\\$[^$]+\\$\\d+\\$[./0-9A-Za-z]{53}");
            if (!BCryptPattern.matcher(escapeHatchString).matches()) {
                this.escapeHatchSecret = Secret.fromString(BCrypt.hashpw(escapeHatchString, BCrypt.gensalt()));
                return;
            }
        }
        this.escapeHatchSecret = escapeHatchSecret;
    }

    protected boolean checkEscapeHatch(String username, String password) {
        final boolean isUsernameMatch = username.equals(this.escapeHatchUsername);
        final boolean isPasswordMatch = BCrypt.checkpw(password, Secret.toString(this.escapeHatchSecret));
        return isUsernameMatch & isPasswordMatch;
    }

    @DataBoundSetter
    public void setEscapeHatchGroup(String escapeHatchGroup) {
        this.escapeHatchGroup = Util.fixEmptyAndTrim(escapeHatchGroup);
    }

    @DataBoundSetter
    public void setRootURLFromRequest(boolean rootURLFromRequest) {
        this.rootURLFromRequest = rootURLFromRequest;
    }

    @DataBoundSetter
    public void setSendScopesInTokenRequest(boolean sendScopesInTokenRequest) {
        this.sendScopesInTokenRequest = sendScopesInTokenRequest;
    }

    @DataBoundSetter
    public void setPkceEnabled(boolean pkceEnabled) {
        this.pkceEnabled = pkceEnabled;
    }

    @DataBoundSetter
    public void setDisableTokenVerification(boolean disableTokenVerification) throws FormException {
        if (FIPS140.useCompliantAlgorithms() && disableTokenVerification) {
            throw new FormException(
                    Messages.OicSecurityRealm_DisableTokenVerificationFipsMode(), "disableTokenVerification");
        }
        this.disableTokenVerification = disableTokenVerification;
    }

    @DataBoundSetter
    public void setNonceDisabled(boolean nonceDisabled) {
        this.nonceDisabled = nonceDisabled;
    }

    @DataBoundSetter
    public void setTokenExpirationCheckDisabled(boolean tokenExpirationCheckDisabled) {
        this.tokenExpirationCheckDisabled = tokenExpirationCheckDisabled;
    }

    @DataBoundSetter
    public void setAllowTokenAccessWithoutOicSession(boolean allowTokenAccessWithoutOicSession) {
        this.allowTokenAccessWithoutOicSession = allowTokenAccessWithoutOicSession;
    }

    @DataBoundSetter
    public void setAllowedTokenExpirationClockSkewSeconds(Long allowedTokenExpirationClockSkewSeconds) {
        this.allowedTokenExpirationClockSkewSeconds = allowedTokenExpirationClockSkewSeconds;
    }

    @Override
    public String getLoginUrl() {
        // Login begins with our doCommenceLogin(String,String) method
        return "securityRealm/commenceLogin";
    }

    @Override
    public String getAuthenticationGatewayUrl() {
        return "securityRealm/escapeHatch";
    }

    @Override
    public Filter createFilter(FilterConfig filterConfig) {
        return new ChainedServletFilter2(super.createFilter(filterConfig), new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {

                if (OicSecurityRealm.this.handleTokenExpiration(
                        (HttpServletRequest) request, (HttpServletResponse) response)) {
                    chain.doFilter(request, response);
                }
            }
        });
    }

    /*
     * Acegi has this notion that first an {@link Authentication} object is created
     * by collecting user information and then the act of authentication is done
     * later (by {@link AuthenticationManager}) to verify it. But in case of OpenID,
     * we create an {@link Authentication} only after we verified the user identity,
     * so {@link AuthenticationManager} becomes no-op.
     */
    @Override
    public SecurityComponents createSecurityComponents() {
        return new SecurityComponents(new AuthenticationManager() {
            public Authentication authenticate(Authentication authentication) throws AuthenticationException {
                if (authentication instanceof AnonymousAuthenticationToken) return authentication;

                if (authentication instanceof UsernamePasswordAuthenticationToken && escapeHatchEnabled) {
                    randomWait(); // to slowdown brute forcing
                    if (checkEscapeHatch(
                            authentication.getPrincipal().toString(),
                            authentication.getCredentials().toString())) {
                        List<GrantedAuthority> grantedAuthorities = new ArrayList<>();
                        grantedAuthorities.add(SecurityRealm.AUTHENTICATED_AUTHORITY2);
                        if (isNotBlank(escapeHatchGroup)) {
                            grantedAuthorities.add(new SimpleGrantedAuthority(escapeHatchGroup));
                        }
                        UsernamePasswordAuthenticationToken token =
                                new UsernamePasswordAuthenticationToken(escapeHatchUsername, "", grantedAuthorities);
                        SecurityContextHolder.getContext().setAuthentication(token);
                        OicUserDetails userDetails = new OicUserDetails(escapeHatchUsername, grantedAuthorities);
                        SecurityListener.fireAuthenticated2(userDetails);
                        return token;
                    } else {
                        throw new BadCredentialsException("Wrong username and password: " + authentication);
                    }
                }
                throw new BadCredentialsException("Unexpected authentication type: " + authentication);
            }
        });
    }

    /**
     * Validate post-login redirect URL
     *
     * For security reasons, the login must not redirect outside Jenkins
     * realm. For useablility reason, the logout page should redirect to
     * root url.
     */
    protected String getValidRedirectUrl(String url) {
        final String rootUrl = getRootUrl();
        if (url != null && !url.isEmpty()) {
            try {
                final String redirectUrl = new URL(new URL(rootUrl), url).toString();
                // check redirect url stays within rootUrl
                if (redirectUrl.startsWith(rootUrl)) {
                    // check if redirect is logout page
                    final String logoutUrl = new URL(new URL(rootUrl), OicLogoutAction.POST_LOGOUT_URL).toString();
                    if (redirectUrl.startsWith(logoutUrl)) {
                        return rootUrl;
                    }
                    return redirectUrl;
                }
            } catch (MalformedURLException e) {
                // Invalid URL, will return root URL
            }
        }
        return rootUrl;
    }

    /**
     * Handles the the securityRealm/commenceLogin resource and sends the user off to the IdP
     * @param from the relative URL to the page that the user has just come from
     * @param referer the HTTP referer header (where to redirect the user back to after login has finished)
     * @throws URISyntaxException if the provided data is invalid
     */
    @Restricted(DoNotUse.class) // stapler only
    public void doCommenceLogin(@QueryParameter String from, @Header("Referer") final String referer)
            throws URISyntaxException {

        OidcClient client = buildOidcClient();
        // add the extra params for the client...
        final String redirectOnFinish = getValidRedirectUrl(from != null ? from : referer);

        OidcRedirectionActionBuilder builder = new OidcRedirectionActionBuilder(client);
        FrameworkParameters parameters =
                new JEEFrameworkParameters(Stapler.getCurrentRequest2(), Stapler.getCurrentResponse2());
        WebContext webContext = JEEContextFactory.INSTANCE.newContext(parameters);
        SessionStore sessionStore = JEESessionStoreFactory.INSTANCE.newSessionStore(parameters);
        CallContext ctx = new CallContext(webContext, sessionStore);
        RedirectionAction redirectionAction = builder.getRedirectionAction(ctx).orElseThrow();

        // store the redirect url for after the login.
        sessionStore.set(webContext, SESSION_POST_LOGIN_REDIRECT_URL_KEY, redirectOnFinish);
        JEEHttpActionAdapter.INSTANCE.adapt(redirectionAction, webContext);
        return;
    }

    private void randomWait() {
        try {
            Thread.sleep(1000 + RANDOM.nextInt(1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean failedCheckOfTokenField(JWT idToken) throws ParseException {
        if (tokenFieldToCheckKey == null || tokenFieldToCheckValue == null) {
            return false;
        }
        if (idToken == null) {
            return true;
        }
        String value = getStringField(idToken.getJWTClaimsSet().getClaims(), tokenFieldToCheckExpr);
        if (value == null) {
            return true;
        }
        return !tokenFieldToCheckValue.equals(value);
    }

    private UsernamePasswordAuthenticationToken loginAndSetUserData(
            String userName, JWT idToken, Map<String, Object> userInfo, OicCredentials credentials)
            throws IOException, ParseException {

        List<GrantedAuthority> grantedAuthorities = determineAuthorities(idToken, userInfo);
        if (LOGGER.isLoggable(Level.FINEST)) {
            StringBuilder grantedAuthoritiesAsString = new StringBuilder(userName);
            grantedAuthoritiesAsString.append(" (");
            for (GrantedAuthority grantedAuthority : grantedAuthorities) {
                grantedAuthoritiesAsString.append(" ").append(grantedAuthority.getAuthority());
            }
            grantedAuthoritiesAsString.append(" )");
            LOGGER.finest("GrantedAuthorities:" + grantedAuthoritiesAsString);
        }

        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(userName, "", grantedAuthorities);

        SecurityContextHolder.getContext().setAuthentication(token);

        User user = User.get2(token);
        if (user == null) {
            // should not happen
            throw new IOException("Cannot set OIDC property on anonymous user");
        }
        String email = determineStringField(emailFieldExpr, idToken, userInfo);
        if (email != null) {
            user.addProperty(new Mailer.UserProperty(email));
        }

        String fullName = determineStringField(fullNameFieldExpr, idToken, userInfo);
        if (fullName != null) {
            user.setFullName(fullName);
        }

        // Set avatar if possible
        String avatarUrl = determineStringField(avatarFieldExpr, idToken, userInfo);
        OicAvatarProperty oicAvatarProperty;
        if (avatarUrl != null) {
            LOGGER.finest(() -> "Avatar url is: " + avatarUrl);
            OicAvatarProperty.AvatarImage avatarImage = new OicAvatarProperty.AvatarImage(avatarUrl);
            oicAvatarProperty = new OicAvatarProperty(avatarImage);
        } else {
            LOGGER.finest(() -> "No avatar URL found for user " + user.getId() + ". Ensure to remove existing avatar");
            oicAvatarProperty = new OicAvatarProperty(null);
        }
        user.addProperty(oicAvatarProperty);

        user.addProperty(credentials);

        OicUserDetails userDetails = new OicUserDetails(userName, grantedAuthorities);
        SecurityListener.fireAuthenticated2(userDetails);
        SecurityListener.fireLoggedIn(userName);

        return token;
    }

    private String determineStringField(Expression<Object> fieldExpr, JWT idToken, Map userInfo) throws ParseException {
        if (fieldExpr != null) {
            if (userInfo != null) {
                Object field = fieldExpr.search(userInfo);
                if (field != null) {
                    if (field instanceof String) {
                        String fieldValue = Util.fixEmptyAndTrim((String) field);
                        if (fieldValue != null) {
                            return fieldValue;
                        }
                    }
                    // pac4j OIDC client returns URI for some fields like the "picture" field
                    if (field instanceof URI) {
                        return ((URI) field).toASCIIString();
                    }
                }
            }
            if (idToken != null) {
                String fieldValue = Util.fixEmptyAndTrim(
                        getStringField(idToken.getJWTClaimsSet().getClaims(), fieldExpr));
                if (fieldValue != null) {
                    return fieldValue;
                }
            }
        }
        return null;
    }

    protected String getStringField(Object object, Expression<Object> fieldExpr) {
        if (object != null && fieldExpr != null) {
            Object value = fieldExpr.search(object);
            if ((value != null) && !(value instanceof Map) && !(value instanceof List)) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private List<GrantedAuthority> determineAuthorities(JWT idToken, Map<String, Object> userInfo)
            throws ParseException {
        List<GrantedAuthority> grantedAuthorities = new ArrayList<>();
        grantedAuthorities.add(SecurityRealm.AUTHENTICATED_AUTHORITY2);
        if (this.groupsFieldExpr == null) {
            if (this.groupsFieldName == null) {
                LOGGER.fine("Not adding groups because groupsFieldName is not set. groupsFieldName=" + groupsFieldName);
            } else {
                LOGGER.fine("Not adding groups because groupsFieldName is invalid. groupsFieldName=" + groupsFieldName);
            }
            return grantedAuthorities;
        }

        Object groupsObject = null;

        // userInfo has precedence when available
        if (userInfo != null) {
            groupsObject = this.groupsFieldExpr.search(userInfo);
        }
        if (groupsObject == null && idToken != null) {
            groupsObject = this.groupsFieldExpr.search(idToken.getJWTClaimsSet().getClaims());
        }
        if (groupsObject == null) {
            LOGGER.warning("idToken and userInfo did not contain group field name: " + this.groupsFieldName);
            return grantedAuthorities;
        }

        List<String> groupNames = ensureString(groupsObject);
        if (groupNames.isEmpty()) {
            LOGGER.warning("Could not identify groups in " + groupsFieldName + "=" + groupsObject.toString());
            return grantedAuthorities;
        }
        LOGGER.fine("Number of groups in groupNames: " + groupNames.size());

        for (String groupName : groupNames) {
            LOGGER.fine("Adding group from UserInfo: " + groupName);
            grantedAuthorities.add(new SimpleGrantedAuthority(groupName));
        }

        return grantedAuthorities;
    }

    /** Ensure group field object returns is string or list of string
     */
    private List<String> ensureString(Object field) {
        if (field == null) {
            LOGGER.warning("userInfo did not contain a valid group field content, got null");
            return Collections.<String>emptyList();
        } else if (field instanceof String) {
            // if its a String, the original value was not a json array.
            // We try to convert the string to list based on comma while ignoring whitespaces and square brackets.
            // Example value "[demo-user-group, demo-test-group, demo-admin-group]"
            String sField = (String) field;
            String[] rawFields = sField.split("[\\s\\[\\],]");
            List<String> result = new ArrayList<>();
            for (String rawField : rawFields) {
                if (rawField != null && !rawField.isEmpty()) {
                    result.add(rawField);
                }
            }
            return result;
        } else if (field instanceof List) {
            List<String> result = new ArrayList<>();
            List<Object> groups = (List<Object>) field;
            for (Object group : groups) {
                if (group instanceof String) {
                    result.add(group.toString());
                } else if (group instanceof Map) {
                    // if its a Map, we use the nestedGroupFieldName to grab the groups
                    Map<String, String> groupMap = (Map<String, String>) group;
                    if (nestedGroupFieldName != null && groupMap.keySet().contains(nestedGroupFieldName)) {
                        result.add(groupMap.get(nestedGroupFieldName));
                    }
                }
            }
            return result;
        } else {
            try {
                return (List<String>) field;
            } catch (ClassCastException e) {
                LOGGER.warning("userInfo did not contain a valid group field content, got: "
                        + field.getClass().getSimpleName());
                return Collections.<String>emptyList();
            }
        }
    }

    @Restricted(DoNotUse.class) // stapler only
    public void doLogout(StaplerRequest2 req, StaplerResponse2 rsp) throws IOException, ServletException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User user = User.get2(authentication);

        Assert.notNull(user, "User must not be null");

        OicCredentials credentials = user.getProperty(OicCredentials.class);

        if (credentials != null) {
            if (this.logoutFromOpenidProvider
                    && serverConfiguration.toProviderMetadata().getEndSessionEndpointURI() != null) {
                // This ensures that token will be expired at the right time with API Key calls, but no refresh can be
                // made.
                user.addProperty(new OicCredentials(null, null, null, CLOCK.millis()));
            }

            req.setAttribute(ID_TOKEN_REQUEST_ATTRIBUTE, credentials.getIdToken());
        }

        super.doLogout(req, rsp);
    }

    @Override
    public String getPostLogOutUrl2(StaplerRequest2 req, Authentication auth) {
        Object idToken = req.getAttribute(ID_TOKEN_REQUEST_ATTRIBUTE);
        Object state = getStateAttribute(req.getSession());
        var openidLogoutEndpoint = maybeOpenIdLogoutEndpoint(
                Objects.toString(idToken, ""), Objects.toString(state), this.postLogoutRedirectUrl);
        if (openidLogoutEndpoint != null) {
            return openidLogoutEndpoint;
        }
        return getFinalLogoutUrl(req, auth);
    }

    @VisibleForTesting
    Object getStateAttribute(HttpSession session) {
        // return null;
        OidcClient client = buildOidcClient();
        FrameworkParameters parameters =
                new JEEFrameworkParameters(Stapler.getCurrentRequest2(), Stapler.getCurrentResponse2());
        WebContext webContext = JEEContextFactory.INSTANCE.newContext(parameters);
        SessionStore sessionStore = JEESessionStoreFactory.INSTANCE.newSessionStore(parameters);
        CallContext ctx = new CallContext(webContext, sessionStore);
        return client.getConfiguration()
                .getValueRetriever()
                .retrieve(ctx, client.getStateSessionAttributeName(), client)
                .orElse(null);
    }

    @CheckForNull
    String maybeOpenIdLogoutEndpoint(String idToken, String state, String postLogoutRedirectUrl) {
        final URI url = serverConfiguration.toProviderMetadata().getEndSessionEndpointURI();
        if (this.logoutFromOpenidProvider && url != null) {
            Map<String, String> segmentsMap = new LinkedHashMap<>();
            Set<String> segmentsSet = new LinkedHashSet<>();
            if (!Strings.isNullOrEmpty(idToken)) {
                segmentsMap.put("id_token_hint", idToken);
            }
            if (!Strings.isNullOrEmpty(state) && !"null".equals(state)) {
                segmentsMap.put("state", state);
            }
            if (postLogoutRedirectUrl != null) {
                segmentsMap.put(
                        "post_logout_redirect_uri", URLEncoder.encode(postLogoutRedirectUrl, StandardCharsets.UTF_8));
            }
            if (logoutQueryParameters != null && !logoutQueryParameters.isEmpty()) {
                logoutQueryParameters.forEach(lqp -> {
                    String key = lqp.getURLEncodedKey();
                    String value = lqp.getURLEncodedValue();
                    if (value.isEmpty()) {
                        segmentsSet.add(key);
                    } else {
                        segmentsMap.put(key, value);
                    }
                });
            }

            StringBuilder openidLogoutEndpoint = new StringBuilder(url.toString());
            String concatChar = openidLogoutEndpoint.toString().contains("?") ? "&" : "?";
            if (!segmentsMap.isEmpty()) {
                String joinedString = segmentsMap.entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue())
                        .collect(Collectors.joining("&"));
                openidLogoutEndpoint.append(concatChar).append(joinedString);
                concatChar = "&";
            }
            if (!segmentsSet.isEmpty()) {
                openidLogoutEndpoint.append(concatChar).append(String.join("&", segmentsSet));
            }
            return openidLogoutEndpoint.toString();
        }
        return null;
    }

    private String getFinalLogoutUrl(StaplerRequest2 req, Authentication auth) {
        if (Jenkins.get().hasPermission(Jenkins.READ)) {
            return super.getPostLogOutUrl2(req, auth);
        }
        return req.getContextPath() + "/" + OicLogoutAction.POST_LOGOUT_URL;
    }

    private String getRootUrl() {
        if (rootURLFromRequest) {
            return Jenkins.get().getRootUrlFromRequest();
        } else {
            return Jenkins.get().getRootUrl();
        }
    }

    private String ensureRootUrl() {
        String rootUrl = getRootUrl();
        if (rootUrl == null) {
            throw new NullPointerException("Jenkins root url must not be null");
        } else {
            return rootUrl;
        }
    }

    private String buildOauthCommenceLogin() {
        return ensureRootUrl() + getLoginUrl();
    }

    private String buildOAuthRedirectUrl() throws NullPointerException {
        return ensureRootUrl() + "securityRealm/finishLogin";
    }

    /**
     * This is where the user comes back to at the end of the OpenID redirect ping-pong.
     * @param request The user's request
     * @throws ParseException if the JWT (or other response) could not be parsed.
     */
    public void doFinishLogin(StaplerRequest2 request, StaplerResponse2 response) throws IOException, ParseException {
        OidcClient client = buildOidcClient();

        FrameworkParameters parameters = new JEEFrameworkParameters(request, response);
        WebContext webContext = JEEContextFactory.INSTANCE.newContext(parameters);
        SessionStore sessionStore = JEESessionStoreFactory.INSTANCE.newSessionStore(parameters);

        try {
            // NB: TODO this also handles back channel logout if "logoutendpoint" parameter is set
            // see  org.pac4j.oidc.credentials.extractor.OidcExtractor.extract(WebContext, SessionStore)
            // but we probably need to hookup a special LogoutHandler in the clients configuration to do all the special
            // Jenkins stuff correctly
            // also should have its own URL to make the code easier to follow :)

            if (!sessionStore.renewSession(webContext)) {
                throw new TechnicalException("Could not create a new session");
            }

            CallContext ctx = new CallContext(webContext, sessionStore);
            Credentials credentials = client.getCredentials(ctx)
                    .orElseThrow(() -> new Failure("Could not extract credentials from request"));
            credentials = client.validateCredentials(ctx, credentials)
                    .orElseThrow(() -> new Failure("Could not validate credentials from request"));

            ProfileCreator profileCreator = client.getProfileCreator();

            // creating the profile performs validation of the token
            OidcProfile profile = (OidcProfile) profileCreator
                    .create(ctx, credentials)
                    .orElseThrow(() -> new Failure("Could not build user profile"));

            AccessToken accessToken = profile.getAccessToken();
            JWT idToken = profile.getIdToken();
            RefreshToken refreshToken = profile.getRefreshToken();

            String username = determineStringField(userNameFieldExpr, idToken, profile.getAttributes());
            if (failedCheckOfTokenField(idToken)) {
                throw new FailedCheckOfTokenException(client.getConfiguration().findLogoutUrl());
            }

            OicCredentials oicCredentials = new OicCredentials(
                    accessToken == null ? null : accessToken.getValue(), // XXX (how) can the access token be null?
                    idToken.getParsedString(),
                    refreshToken != null ? refreshToken.getValue() : null,
                    accessToken == null ? 0 : accessToken.getLifetime(),
                    CLOCK.millis(),
                    getAllowedTokenExpirationClockSkewSeconds());

            loginAndSetUserData(username, idToken, profile.getAttributes(), oicCredentials);

            String redirectUrl = (String) sessionStore
                    .get(webContext, SESSION_POST_LOGIN_REDIRECT_URL_KEY)
                    .orElse(Jenkins.get().getRootUrl());
            response.sendRedirect(HttpURLConnection.HTTP_MOVED_TEMP, redirectUrl);

        } catch (HttpAction e) {
            // this may be an OK flow for logout login is handled upstream.
            JEEHttpActionAdapter.INSTANCE.adapt(e, webContext);
            return;
        }
    }

    /**
     * Handles Token Expiration.
     * @throws IOException a low level exception
     */
    public boolean handleTokenExpiration(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
            throws IOException {
        if (httpRequest.getRequestURI().endsWith("/logout")) {
            // No need to refresh token when logging out
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        User user = User.get2(authentication);
        if (user == null) {
            return true;
        }

        if (isAllowTokenAccessWithoutOicSession()) {
            // check if this is a valid api token based request
            String authHeader = httpRequest.getHeader("Authorization");
            if (authHeader != null && authHeader.startsWith("Basic ")) {
                String token = new String(Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8)
                        .split(":")[1];
                ApiTokenProperty apiTokenProperty = user.getProperty(ApiTokenProperty.class);
                if (apiTokenProperty != null && apiTokenProperty.matchesPassword(token)) {
                    // this was a valid jenkins token being used, exit this filter and let
                    // the rest of chain be processed
                    return true;
                } // else do nothing and continue evaluating this request
            }
        }

        OicCredentials credentials = user.getProperty(OicCredentials.class);

        if (credentials == null) {
            return true;
        }

        if (isExpired(credentials)) {
            if (serverConfiguration.toProviderMetadata().getGrantTypes() != null
                    && serverConfiguration.toProviderMetadata().getGrantTypes().contains(GrantType.REFRESH_TOKEN)
                    && !Strings.isNullOrEmpty(credentials.getRefreshToken())) {
                LOGGER.log(Level.FINEST, "Attempting to refresh credential for user: {0}", user.getId());
                boolean retVal = refreshExpiredToken(user.getId(), credentials, httpRequest, httpResponse);
                LOGGER.log(Level.FINEST, "Refresh credential for user returned {0}", retVal);
                return retVal;
            } else if (!isTokenExpirationCheckDisabled()) {
                redirectToLoginUrl(httpRequest, httpResponse);
                return false;
            }
        }

        return true;
    }

    private void redirectToLoginUrl(HttpServletRequest req, HttpServletResponse res) throws IOException {
        if (req != null && (req.getSession(false) != null || Strings.isNullOrEmpty(req.getHeader("Authorization")))) {
            req.getSession().invalidate();
        }
        if (res != null) {
            res.sendRedirect(Jenkins.get().getSecurityRealm().getLoginUrl());
        }
    }

    public boolean isExpired(OicCredentials credentials) {
        if (credentials.getExpiresAtMillis() == null) {
            return false;
        }

        return CLOCK.millis() >= credentials.getExpiresAtMillis();
    }

    private boolean refreshExpiredToken(
            String expectedUsername,
            OicCredentials credentials,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse)
            throws IOException {

        FrameworkParameters parameters = new JEEFrameworkParameters(httpRequest, httpResponse);
        WebContext webContext = JEEContextFactory.INSTANCE.newContext(parameters);
        SessionStore sessionStore = JEESessionStoreFactory.INSTANCE.newSessionStore(parameters);
        OidcClient client = buildOidcClient();
        // PAC4J maintains the nonce even though servers should not respond with an id token containing the nonce
        // https://openid.net/specs/openid-connect-core-1_0.html#RefreshTokenResponse
        // it SHOULD NOT have a nonce Claim, even when the ID Token issued at the time of the original authentication
        // contained nonce;
        // however, if it is present, its value MUST be the same as in the ID Token issued at the time of the original
        // authentication
        // by default we will strip out the nonce unless the user has opted into it.
        client.getConfiguration().setUseNonce(!nonceDisabled && checkNonceInRefreshFlow);
        try {
            OidcProfile profile = new OidcProfile();
            profile.setAccessToken(new BearerAccessToken(credentials.getAccessToken()));
            profile.setIdTokenString(credentials.getIdToken());
            profile.setRefreshToken(new RefreshToken(credentials.getRefreshToken()));

            CallContext ctx = new CallContext(webContext, sessionStore);
            profile = (OidcProfile) client.renewUserProfile(ctx, profile)
                    .orElseThrow(() -> new IllegalStateException("Could not renew user profile"));

            // During refresh the IDToken may or may not be present.
            // The refresh token may also not be present.
            // in these cases we will reuse the original values.

            AccessToken accessToken = profile.getAccessToken();
            JWT idToken = Objects.requireNonNullElse(profile.getIdToken(), JWTParser.parse(credentials.getIdToken()));
            RefreshToken refreshToken = Objects.requireNonNullElse(
                    profile.getRefreshToken(), new RefreshToken(credentials.getRefreshToken()));

            String username = determineStringField(userNameFieldExpr, idToken, profile.getAttributes());
            if (!User.idStrategy().equals(expectedUsername, username)) {
                httpResponse.sendError(
                        HttpServletResponse.SC_UNAUTHORIZED, "User name was not the same after refresh request");
                return false;
            }
            // the username may have changed case during a call, but still be the same user (as we have checked the
            // idStrategy)
            // we need to keep using exactly the same principal otherwise there is a potential for crumbs not to match.
            // whilst we could do some normalization of the username, just use the original (expected) username
            // see https://github.com/jenkinsci/oic-auth-plugin/issues/411
            if (LOGGER.isLoggable(Level.FINE)) {
                Authentication a = SecurityContextHolder.getContext().getAuthentication();
                User u = User.get2(a);
                LOGGER.log(
                        Level.FINE,
                        "Token refresh.  Current Authentitcation principal: " + a.getName() + " user id:"
                                + (u == null ? "null user" : u.getId()) + " newly retreived username would have been: "
                                + username);
            }
            username = expectedUsername;

            if (failedCheckOfTokenField(idToken)) {
                throw new FailedCheckOfTokenException(client.getConfiguration().findLogoutUrl());
            }

            OicCredentials refreshedCredentials = new OicCredentials(
                    accessToken.getValue(),
                    idToken.getParsedString(),
                    refreshToken.getValue(),
                    accessToken.getLifetime(),
                    CLOCK.millis(),
                    getAllowedTokenExpirationClockSkewSeconds());

            loginAndSetUserData(username, idToken, profile.getAttributes(), refreshedCredentials);
            return true;
        } catch (TechnicalException e) {
            if (StringUtils.contains(e.getMessage(), "error=invalid_grant")) {
                // the code is lost from the TechnicalException so we need to resort to string matching
                // to retain the same flow :-(
                if (isTokenExpirationCheckDisabled()) {
                    // the code is lost from the TechnicalException so we need to resort to string matching to retain
                    // the same flow :-(
                    LOGGER.log(
                            Level.FINE,
                            "Failed to refresh expired token because grant is invalid, proceeding as \"Token Expiration Check Disabled\" is set");
                    return false;
                }
                LOGGER.log(Level.FINE, "Failed to refresh expired token", e);
                redirectToLoginUrl(httpRequest, httpResponse);
                return false;
            }
            LOGGER.log(Level.WARNING, "Failed to refresh expired token", e);
            httpResponse.sendError(
                    HttpServletResponse.SC_UNAUTHORIZED, Messages.OicSecurityRealm_TokenRefreshFailure());
            return false;
        } catch (ParseException e) {
            LOGGER.log(Level.WARNING, "Failed to refresh expired token", e);
            // could not renew
            httpResponse.sendError(
                    HttpServletResponse.SC_UNAUTHORIZED, Messages.OicSecurityRealm_TokenRefreshFailure());
            return false;
        } catch (IllegalStateException e) {
            LOGGER.log(Level.WARNING, "Failed to refresh expired token, profile was null", e);
            // could not renew
            httpResponse.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<SecurityRealm> {

        @Override
        public String getDisplayName() {
            return Messages.OicSecurityRealm_DisplayName();
        }

        @RequirePOST
        public FormValidation doCheckClientId(@QueryParameter String clientId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(clientId) == null) {
                return FormValidation.error(Messages.OicSecurityRealm_ClientIdRequired());
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckClientSecret(@QueryParameter String clientSecret) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(clientSecret) == null) {
                return FormValidation.error(Messages.OicSecurityRealm_ClientSecretRequired());
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckPostLogoutRedirectUrl(@QueryParameter String postLogoutRedirectUrl) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(postLogoutRedirectUrl) != null) {
                try {
                    new URL(postLogoutRedirectUrl);
                    return FormValidation.ok();
                } catch (MalformedURLException e) {
                    return FormValidation.error(e, Messages.OicSecurityRealm_NotAValidURL());
                }
            }

            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckUserNameField(@QueryParameter String userNameField) {
            return this.doCheckFieldName(
                    userNameField, FormValidation.ok(Messages.OicSecurityRealm_UsingDefaultUsername()));
        }

        @RequirePOST
        public FormValidation doCheckFullNameFieldName(@QueryParameter String fullNameFieldName) {
            return this.doCheckFieldName(fullNameFieldName, FormValidation.ok());
        }

        @RequirePOST
        public FormValidation doCheckEmailFieldName(@QueryParameter String emailFieldName) {
            return this.doCheckFieldName(emailFieldName, FormValidation.ok());
        }

        @RequirePOST
        public FormValidation doCheckGroupsFieldName(@QueryParameter String groupsFieldName) {
            return this.doCheckFieldName(groupsFieldName, FormValidation.ok());
        }

        @RequirePOST
        public FormValidation doCheckTokenFieldToCheckKey(@QueryParameter String tokenFieldToCheckKey) {
            return this.doCheckFieldName(tokenFieldToCheckKey, FormValidation.ok());
        }

        @RequirePOST
        public FormValidation doCheckDisableSslVerification(@QueryParameter Boolean disableSslVerification) {
            if (FIPS140.useCompliantAlgorithms() && disableSslVerification) {
                return FormValidation.error(Messages.OicSecurityRealm_DisableSslVerificationFipsMode());
            }
            return FormValidation.ok();
        }

        @RequirePOST
        public FormValidation doCheckDisableTokenVerification(@QueryParameter Boolean disableTokenVerification) {
            if (FIPS140.useCompliantAlgorithms() && disableTokenVerification) {
                return FormValidation.error(Messages.OicSecurityRealm_DisableTokenVerificationFipsMode());
            }
            return FormValidation.ok();
        }

        // method to check fieldName matches JMESPath format
        private FormValidation doCheckFieldName(String fieldName, FormValidation validIfNull) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(fieldName) == null) {
                return validIfNull;
            }
            if (OicSecurityRealm.compileJMESPath(fieldName, null) == null) {
                return FormValidation.error(Messages.OicSecurityRealm_InvalidFieldName());
            }
            return FormValidation.ok();
        }

        @Restricted(NoExternalUse.class) // jelly only
        public Descriptor<OicServerConfiguration> getDefaultServerConfigurationType() {
            return Jenkins.get().getDescriptor(OicServerWellKnownConfiguration.class);
        }

        @Restricted(NoExternalUse.class) // used by jelly only
        public boolean isFipsEnabled() {
            return FIPS140.useCompliantAlgorithms();
        }

        @Restricted(NoExternalUse.class)
        public List<IdStrategyDescriptor> getIdStrategyDescriptors() {
            return ExtensionList.lookup(IdStrategyDescriptor.class);
        }

        /**
         * The default username strategy for new OicSecurityRealm
         */
        @Restricted(NoExternalUse.class)
        public IdStrategy defaultUsernameIdStrategy() {
            return new IdStrategy.CaseSensitive();
        }

        /**
         * The default group strategy for new OicSecurityRealm
         */
        @Restricted(NoExternalUse.class)
        public IdStrategy defaultGroupIdStrategy() {
            return new IdStrategy.CaseSensitive();
        }
    }
}
