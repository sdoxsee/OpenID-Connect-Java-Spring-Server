/*******************************************************************************
 * Copyright 2015 The MITRE Corporation
 *   and the MIT Kerberos and Internet Trust Consortium
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
/**
 * 
 */
package org.mitre.oauth2.service.impl;

import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.mitre.oauth2.model.AuthenticationHolderEntity;
import org.mitre.oauth2.model.ClientDetailsEntity;
import org.mitre.oauth2.model.OAuth2AccessTokenEntity;
import org.mitre.oauth2.model.OAuth2RefreshTokenEntity;
import org.mitre.oauth2.model.SystemScope;
import org.mitre.oauth2.repository.AuthenticationHolderRepository;
import org.mitre.oauth2.repository.OAuth2TokenRepository;
import org.mitre.oauth2.service.ClientDetailsEntityService;
import org.mitre.oauth2.service.OAuth2TokenEntityService;
import org.mitre.oauth2.service.SystemScopeService;
import org.mitre.openid.connect.model.ApprovedSite;
import org.mitre.openid.connect.service.ApprovedSiteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.common.exceptions.InvalidClientException;
import org.springframework.security.oauth2.common.exceptions.InvalidScopeException;
import org.springframework.security.oauth2.common.exceptions.InvalidTokenException;
import org.springframework.security.oauth2.provider.OAuth2Authentication;
import org.springframework.security.oauth2.provider.OAuth2Request;
import org.springframework.security.oauth2.provider.TokenRequest;
import org.springframework.security.oauth2.provider.token.TokenEnhancer;
import org.springframework.stereotype.Service;

import com.google.common.collect.Sets;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;


/**
 * @author jricher
 * 
 */
@Service("defaultOAuth2ProviderTokenService")
public class DefaultOAuth2ProviderTokenService implements OAuth2TokenEntityService {

	/**
	 * Logger for this class
	 */
	private static final Logger logger = LoggerFactory.getLogger(DefaultOAuth2ProviderTokenService.class);

	@Autowired
	private OAuth2TokenRepository tokenRepository;

	@Autowired
	private AuthenticationHolderRepository authenticationHolderRepository;

	@Autowired
	private ClientDetailsEntityService clientDetailsService;

	@Autowired
	private TokenEnhancer tokenEnhancer;

	@Autowired
	private SystemScopeService scopeService;

	@Override
	public Set<OAuth2AccessTokenEntity> getAllAccessTokensForUser(String id) {

		Set<OAuth2AccessTokenEntity> all = tokenRepository.getAllAccessTokens();
		Set<OAuth2AccessTokenEntity> results = Sets.newLinkedHashSet();

		for (OAuth2AccessTokenEntity token : all) {
			if (token.getAuthenticationHolder().getAuthentication().getName().equals(id)) {
				results.add(token);
			}
		}

		return results;
	}


	@Override
	public Set<OAuth2RefreshTokenEntity> getAllRefreshTokensForUser(String id) {
		Set<OAuth2RefreshTokenEntity> all = tokenRepository.getAllRefreshTokens();
		Set<OAuth2RefreshTokenEntity> results = Sets.newLinkedHashSet();

		for (OAuth2RefreshTokenEntity token : all) {
			if (token.getAuthenticationHolder().getAuthentication().getName().equals(id)) {
				results.add(token);
			}
		}

		return results;
	}

	@Override
	public OAuth2AccessTokenEntity getAccessTokenById(Long id) {
		return tokenRepository.getAccessTokenById(id);
	}

	@Override
	public OAuth2RefreshTokenEntity getRefreshTokenById(Long id) {
		return tokenRepository.getRefreshTokenById(id);
	}

	@Autowired
	private ApprovedSiteService approvedSiteService;


	@Override
	public OAuth2AccessTokenEntity createAccessToken(OAuth2Authentication authentication) throws AuthenticationException, InvalidClientException {
		if (authentication != null && authentication.getOAuth2Request() != null) {
			// look up our client
			OAuth2Request clientAuth = authentication.getOAuth2Request();

			ClientDetailsEntity client = clientDetailsService.loadClientByClientId(clientAuth.getClientId());

			if (client == null) {
				throw new InvalidClientException("Client not found: " + clientAuth.getClientId());
			}

			OAuth2AccessTokenEntity token = new OAuth2AccessTokenEntity();//accessTokenFactory.createNewAccessToken();

			// attach the client
			token.setClient(client);

			// inherit the scope from the auth, but make a new set so it is
			//not unmodifiable. Unmodifiables don't play nicely with Eclipselink, which
			//wants to use the clone operation.
			Set<SystemScope> scopes = scopeService.fromStrings(clientAuth.getScope());

			// remove any of the special system scopes
			scopes = scopeService.removeReservedScopes(scopes);

			token.setScope(scopeService.toStrings(scopes));

			// make it expire if necessary
			if (client.getAccessTokenValiditySeconds() != null && client.getAccessTokenValiditySeconds() > 0) {
				Date expiration = new Date(System.currentTimeMillis() + (client.getAccessTokenValiditySeconds() * 1000L));
				token.setExpiration(expiration);
			}

			// attach the authorization so that we can look it up later
			AuthenticationHolderEntity authHolder = new AuthenticationHolderEntity();
			authHolder.setAuthentication(authentication);
			authHolder = authenticationHolderRepository.save(authHolder);

			token.setAuthenticationHolder(authHolder);

			// attach a refresh token, if this client is allowed to request them and the user gets the offline scope
			if (client.isAllowRefresh() && token.getScope().contains(SystemScopeService.OFFLINE_ACCESS)) {
				OAuth2RefreshTokenEntity savedRefreshToken = createRefreshToken(client, authHolder);

				token.setRefreshToken(savedRefreshToken);
			}

			OAuth2AccessTokenEntity enhancedToken = (OAuth2AccessTokenEntity) tokenEnhancer.enhance(token, authentication);

			OAuth2AccessTokenEntity savedToken = tokenRepository.saveAccessToken(enhancedToken);

			//Add approved site reference, if any
			OAuth2Request originalAuthRequest = authHolder.getAuthentication().getOAuth2Request();

			if (originalAuthRequest.getExtensions() != null && originalAuthRequest.getExtensions().containsKey("approved_site")) {

				Long apId = Long.parseLong((String) originalAuthRequest.getExtensions().get("approved_site"));
				ApprovedSite ap = approvedSiteService.getById(apId);
				Set<OAuth2AccessTokenEntity> apTokens = ap.getApprovedAccessTokens();
				apTokens.add(savedToken);
				ap.setApprovedAccessTokens(apTokens);
				approvedSiteService.save(ap);

			}

			if (savedToken.getRefreshToken() != null) {
				tokenRepository.saveRefreshToken(savedToken.getRefreshToken()); // make sure we save any changes that might have been enhanced
			}

			return savedToken;
		}

		throw new AuthenticationCredentialsNotFoundException("No authentication credentials found");
	}


	private OAuth2RefreshTokenEntity createRefreshToken(ClientDetailsEntity client, AuthenticationHolderEntity authHolder) {
		OAuth2RefreshTokenEntity refreshToken = new OAuth2RefreshTokenEntity(); //refreshTokenFactory.createNewRefreshToken();
		JWTClaimsSet.Builder refreshClaims = new JWTClaimsSet.Builder();


		// make it expire if necessary
		if (client.getRefreshTokenValiditySeconds() != null) {
			Date expiration = new Date(System.currentTimeMillis() + (client.getRefreshTokenValiditySeconds() * 1000L));
			refreshToken.setExpiration(expiration);
			refreshClaims.expirationTime(expiration);
		}

		// set a random identifier
		refreshClaims.jwtID(UUID.randomUUID().toString());

		// TODO: add issuer fields, signature to JWT

		PlainJWT refreshJwt = new PlainJWT(refreshClaims.build());
		refreshToken.setJwt(refreshJwt);

		//Add the authentication
		refreshToken.setAuthenticationHolder(authHolder);
		refreshToken.setClient(client);



		// save the token first so that we can set it to a member of the access token (NOTE: is this step necessary?)
		OAuth2RefreshTokenEntity savedRefreshToken = tokenRepository.saveRefreshToken(refreshToken);
		return savedRefreshToken;
	}

	@Override
	public OAuth2AccessTokenEntity refreshAccessToken(String refreshTokenValue, TokenRequest authRequest) throws AuthenticationException {

		OAuth2RefreshTokenEntity refreshToken = tokenRepository.getRefreshTokenByValue(refreshTokenValue);

		if (refreshToken == null) {
			throw new InvalidTokenException("Invalid refresh token: " + refreshTokenValue);
		}

		ClientDetailsEntity client = refreshToken.getClient();

		AuthenticationHolderEntity authHolder = refreshToken.getAuthenticationHolder();

		// make sure that the client requesting the token is the one who owns the refresh token
		ClientDetailsEntity requestingClient = clientDetailsService.loadClientByClientId(authRequest.getClientId());
		if (!client.getClientId().equals(requestingClient.getClientId())) {
			tokenRepository.removeRefreshToken(refreshToken);
			throw new InvalidClientException("Client does not own the presented refresh token");
		}

		//Make sure this client allows access token refreshing
		if (!client.isAllowRefresh()) {
			throw new InvalidClientException("Client does not allow refreshing access token!");
		}

		// clear out any access tokens
		if (client.isClearAccessTokensOnRefresh()) {
			tokenRepository.clearAccessTokensForRefreshToken(refreshToken);
		}

		if (refreshToken.isExpired()) {
			tokenRepository.removeRefreshToken(refreshToken);
			throw new InvalidTokenException("Expired refresh token: " + refreshTokenValue);
		}

		OAuth2AccessTokenEntity token = new OAuth2AccessTokenEntity();

		// get the stored scopes from the authentication holder's authorization request; these are the scopes associated with the refresh token
		Set<String> refreshScopesRequested = new HashSet<>(refreshToken.getAuthenticationHolder().getAuthentication().getOAuth2Request().getScope());
		Set<SystemScope> refreshScopes = scopeService.fromStrings(refreshScopesRequested);
		// remove any of the special system scopes
		refreshScopes = scopeService.removeReservedScopes(refreshScopes);

		Set<String> scopeRequested = authRequest.getScope() == null ? new HashSet<String>() : new HashSet<>(authRequest.getScope());
		Set<SystemScope> scope = scopeService.fromStrings(scopeRequested);

		// remove any of the special system scopes
		scope = scopeService.removeReservedScopes(scope);

		if (scope != null && !scope.isEmpty()) {
			// ensure a proper subset of scopes
			if (refreshScopes != null && refreshScopes.containsAll(scope)) {
				// set the scope of the new access token if requested
				token.setScope(scopeService.toStrings(scope));
			} else {
				String errorMsg = "Up-scoping is not allowed.";
				logger.error(errorMsg);
				throw new InvalidScopeException(errorMsg);
			}
		} else {
			// otherwise inherit the scope of the refresh token (if it's there -- this can return a null scope set)
			token.setScope(scopeService.toStrings(refreshScopes));
		}

		token.setClient(client);

		if (client.getAccessTokenValiditySeconds() != null) {
			Date expiration = new Date(System.currentTimeMillis() + (client.getAccessTokenValiditySeconds() * 1000L));
			token.setExpiration(expiration);
		}

		if (client.isReuseRefreshToken()) {
			// if the client re-uses refresh tokens, do that
			token.setRefreshToken(refreshToken);
		} else {
			// otherwise, make a new refresh token
			OAuth2RefreshTokenEntity newRefresh = createRefreshToken(client, authHolder);
			token.setRefreshToken(newRefresh);

			// clean up the old refresh token
			tokenRepository.removeRefreshToken(refreshToken);
		}

		token.setAuthenticationHolder(authHolder);

		tokenEnhancer.enhance(token, authHolder.getAuthentication());

		tokenRepository.saveAccessToken(token);

		return token;

	}

	@Override
	public OAuth2Authentication loadAuthentication(String accessTokenValue) throws AuthenticationException {

		OAuth2AccessTokenEntity accessToken = tokenRepository.getAccessTokenByValue(accessTokenValue);

		if (accessToken == null) {
			throw new InvalidTokenException("Invalid access token: " + accessTokenValue);
		}

		if (accessToken.isExpired()) {
			//tokenRepository.removeAccessToken(accessToken);
			revokeAccessToken(accessToken);
			throw new InvalidTokenException("Expired access token: " + accessTokenValue);
		}

		return accessToken.getAuthenticationHolder().getAuthentication();
	}


	/**
	 * Get an access token from its token value.
	 */
	@Override
	public OAuth2AccessTokenEntity readAccessToken(String accessTokenValue) throws AuthenticationException {
		OAuth2AccessTokenEntity accessToken = tokenRepository.getAccessTokenByValue(accessTokenValue);
		if (accessToken == null) {
			throw new InvalidTokenException("Access token for value " + accessTokenValue + " was not found");
		}
		else {
			return accessToken;
		}
	}

	/**
	 * Get an access token by its authentication object.
	 */
	@Override
	public OAuth2AccessTokenEntity getAccessToken(OAuth2Authentication authentication) {
		// TODO: implement this against the new service (#825)
		throw new UnsupportedOperationException("Unable to look up access token from authentication object.");
	}

	/**
	 * Get a refresh token by its token value.
	 */
	@Override
	public OAuth2RefreshTokenEntity getRefreshToken(String refreshTokenValue) throws AuthenticationException {
		OAuth2RefreshTokenEntity refreshToken = tokenRepository.getRefreshTokenByValue(refreshTokenValue);
		if (refreshToken == null) {
			throw new InvalidTokenException("Refresh token for value " + refreshTokenValue + " was not found");
		}
		else {
			return refreshToken;
		}
	}

	/**
	 * Revoke a refresh token and all access tokens issued to it.
	 */
	@Override
	public void revokeRefreshToken(OAuth2RefreshTokenEntity refreshToken) {
		tokenRepository.clearAccessTokensForRefreshToken(refreshToken);
		tokenRepository.removeRefreshToken(refreshToken);
	}

	/**
	 * Revoke an access token.
	 */
	@Override
	public void revokeAccessToken(OAuth2AccessTokenEntity accessToken) {
		tokenRepository.removeAccessToken(accessToken);
	}


	/* (non-Javadoc)
	 * @see org.mitre.oauth2.service.OAuth2TokenEntityService#getAccessTokensForClient(org.mitre.oauth2.model.ClientDetailsEntity)
	 */
	@Override
	public List<OAuth2AccessTokenEntity> getAccessTokensForClient(ClientDetailsEntity client) {
		return tokenRepository.getAccessTokensForClient(client);
	}

	/* (non-Javadoc)
	 * @see org.mitre.oauth2.service.OAuth2TokenEntityService#getRefreshTokensForClient(org.mitre.oauth2.model.ClientDetailsEntity)
	 */
	@Override
	public List<OAuth2RefreshTokenEntity> getRefreshTokensForClient(ClientDetailsEntity client) {
		return tokenRepository.getRefreshTokensForClient(client);
	}

	/**
	 * Clears out expired tokens and any abandoned authentication objects
	 */
	@Override
	public void clearExpiredTokens() {
		logger.info("Cleaning out all expired tokens");
		
		// get all the duplicated tokens first to maintain consistency
		tokenRepository.clearDuplicateAccessTokens();
		tokenRepository.clearDuplicateRefreshTokens();

		Collection<OAuth2AccessTokenEntity> accessTokens = getExpiredAccessTokens();
		logger.info("Found " + accessTokens.size() + " expired access tokens");
		for (OAuth2AccessTokenEntity oAuth2AccessTokenEntity : accessTokens) {
			try {
				revokeAccessToken(oAuth2AccessTokenEntity);
			} catch (IllegalArgumentException e) {
				//An ID token is deleted with its corresponding access token, but then the ID token is on the list of expired tokens as well and there is
				//nothing in place to distinguish it from any other.
				//An attempt to delete an already deleted token returns an error, stopping the cleanup dead. We need it to keep going.
			}
		}

		Collection<OAuth2RefreshTokenEntity> refreshTokens = getExpiredRefreshTokens();
		logger.info("Found " + refreshTokens.size() + " expired refresh tokens");
		for (OAuth2RefreshTokenEntity oAuth2RefreshTokenEntity : refreshTokens) {
			revokeRefreshToken(oAuth2RefreshTokenEntity);
		}

		Collection<AuthenticationHolderEntity> authHolders = getOrphanedAuthenticationHolders();
		logger.info("Found " + authHolders.size() + " orphaned authentication holders");
		for(AuthenticationHolderEntity authHolder : authHolders) {
			authenticationHolderRepository.remove(authHolder);
		}
	}

	private Collection<OAuth2AccessTokenEntity> getExpiredAccessTokens() {
		return Sets.newHashSet(tokenRepository.getAllExpiredAccessTokens());
	}

	private Collection<OAuth2RefreshTokenEntity> getExpiredRefreshTokens() {
		return Sets.newHashSet(tokenRepository.getAllExpiredRefreshTokens());
	}

	private Collection<AuthenticationHolderEntity> getOrphanedAuthenticationHolders() {
		return Sets.newHashSet(authenticationHolderRepository.getOrphanedAuthenticationHolders());
	}

	/* (non-Javadoc)
	 * @see org.mitre.oauth2.service.OAuth2TokenEntityService#saveAccessToken(org.mitre.oauth2.model.OAuth2AccessTokenEntity)
	 */
	@Override
	public OAuth2AccessTokenEntity saveAccessToken(OAuth2AccessTokenEntity accessToken) {
		return tokenRepository.saveAccessToken(accessToken);
	}

	/* (non-Javadoc)
	 * @see org.mitre.oauth2.service.OAuth2TokenEntityService#saveRefreshToken(org.mitre.oauth2.model.OAuth2RefreshTokenEntity)
	 */
	@Override
	public OAuth2RefreshTokenEntity saveRefreshToken(OAuth2RefreshTokenEntity refreshToken) {
		return tokenRepository.saveRefreshToken(refreshToken);
	}

	/**
	 * @return the tokenEnhancer
	 */
	public TokenEnhancer getTokenEnhancer() {
		return tokenEnhancer;
	}

	/**
	 * @param tokenEnhancer the tokenEnhancer to set
	 */
	public void setTokenEnhancer(TokenEnhancer tokenEnhancer) {
		this.tokenEnhancer = tokenEnhancer;
	}

	/* (non-Javadoc)
	 * @see org.mitre.oauth2.service.OAuth2TokenEntityService#getAccessTokenForIdToken(org.mitre.oauth2.model.OAuth2AccessTokenEntity)
	 */
	@Override
	public OAuth2AccessTokenEntity getAccessTokenForIdToken(OAuth2AccessTokenEntity idToken) {
		return tokenRepository.getAccessTokenForIdToken(idToken);
	}


	@Override
	public OAuth2AccessTokenEntity getRegistrationAccessTokenForClient(ClientDetailsEntity client) {
		List<OAuth2AccessTokenEntity> allTokens = getAccessTokensForClient(client);

		for (OAuth2AccessTokenEntity token : allTokens) {
			if ((token.getScope().contains(SystemScopeService.REGISTRATION_TOKEN_SCOPE) || token.getScope().contains(SystemScopeService.RESOURCE_TOKEN_SCOPE))
					&& token.getScope().size() == 1) {
				// if it only has the registration scope, then it's a registration token
				return token;
			}
		}

		return null;
	}



}
