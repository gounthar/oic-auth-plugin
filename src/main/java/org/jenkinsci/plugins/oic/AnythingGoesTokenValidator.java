package org.jenkinsci.plugins.oic;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.oauth2.sdk.ParseException;
import com.nimbusds.oauth2.sdk.auth.ClientAuthenticationMethod;
import com.nimbusds.oauth2.sdk.id.Issuer;
import com.nimbusds.openid.connect.sdk.Nonce;
import com.nimbusds.openid.connect.sdk.SubjectType;
import com.nimbusds.openid.connect.sdk.claims.IDTokenClaimsSet;
import com.nimbusds.openid.connect.sdk.op.OIDCProviderMetadata;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.pac4j.core.exception.TechnicalException;
import org.pac4j.oidc.config.OidcConfiguration;
import org.pac4j.oidc.metadata.OidcOpMetadataResolver;
import org.pac4j.oidc.metadata.StaticOidcOpMetadataResolver;
import org.pac4j.oidc.profile.creator.TokenValidator;

public class AnythingGoesTokenValidator extends TokenValidator {

    public static final Logger LOGGER = Logger.getLogger(AnythingGoesTokenValidator.class.getName());

    public AnythingGoesTokenValidator() {
        this(createFakeOidcProviderMetadata());
    }

    public AnythingGoesTokenValidator(OIDCProviderMetadata metadata) {
        super(createFakeOidcConfiguration(metadata), metadata);
    }

    @Override
    public IDTokenClaimsSet validate(final JWT idToken, final Nonce expectedNonce) {
        // validation is disabled, so everything is valid.
        try {
            return new IDTokenClaimsSet(idToken.getJWTClaimsSet());
        } catch (ParseException | java.text.ParseException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Token validation is disabled, but the token is corrupt and claims will not be represted.",
                    e);
            try {
                return new IDTokenClaimsSet(new JWTClaimsSet.Builder().build());
            } catch (ParseException e1) {
                throw new TechnicalException("could not create and empty IDTokenClaimsSet");
            }
        }
    }

    /**
     * Annoyingly the super class needs an OidcConfiguration with some values set,
     * which if we are not validating we may not actually have (e.g. jwks_url).
     * So we need a configuration with this set just so the validator can say "this is valid".
     */
    private static OidcConfiguration createFakeOidcConfiguration(OIDCProviderMetadata metadata) {
        OidcConfiguration config = new OidcConfiguration();
        config.setClientId("ignored");
        config.setSecret("ignored");
        OidcOpMetadataResolver opMetadataResolver = new StaticOidcOpMetadataResolver(config, metadata);
        config.setOpMetadataResolver(opMetadataResolver);
        config.setPreferredJwsAlgorithm(JWSAlgorithm.HS256);
        config.setClientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC);
        return config;
    }

    private static OIDCProviderMetadata createFakeOidcProviderMetadata() {
        try {
            OIDCProviderMetadata providerMetadata = new OIDCProviderMetadata(
                    new Issuer("http://ignored"), List.of(SubjectType.PUBLIC), new URI("http://ignored.and.invalid./"));
            providerMetadata.setIDTokenJWSAlgs(List.of(JWSAlgorithm.HS256));
            return providerMetadata;
        } catch (URISyntaxException e) {
            // should never happen the urls we are using are valid
            throw new IllegalStateException(e);
        }
    }
}
