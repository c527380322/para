/*
 * Copyright 2013-2015 Erudika. http://erudika.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For issues and patches go to: https://github.com/erudika
 */
package com.erudika.para.security;

import com.erudika.para.Para;
import com.erudika.para.rest.Signer;
import com.erudika.para.utils.Config;
import com.typesafe.config.ConfigList;
import com.typesafe.config.ConfigObject;
import com.typesafe.config.ConfigValue;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import javax.annotation.security.DeclareRoles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.RememberMeAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.openid.OpenIDAuthenticationProvider;
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

/**
 * Programmatic configuration for Spring Security.
 * @author Alex Bogdanovski [alex@erudika.com]
 */
@Configuration
@EnableWebSecurity
@DeclareRoles({ "ROLE_USER", "ROLE_MOD", "ROLE_ADMIN", "ROLE_APP" })
public class SecurityConfig extends WebSecurityConfigurerAdapter {

	private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);

	private final CachedCsrfTokenRepository csrfTokenRepository;
	private final SimpleRememberMeServices rememberMeServices;
	private final PasswordAuthFilter passwordFilter;
	private final OpenIDAuthFilter openidFilter;
	private final FacebookAuthFilter facebookFilter;
	private final GoogleAuthFilter googleFilter;
	private final LinkedInAuthFilter linkedinFilter;
	private final TwitterAuthFilter twitterFilter;
	private final GitHubAuthFilter githubFilter;
	private final JWTRestfulAuthFilter jwtFilter;

	public SecurityConfig() {
		csrfTokenRepository = Para.getInstance(CachedCsrfTokenRepository.class);
		rememberMeServices = Para.getInstance(SimpleRememberMeServices.class);
		passwordFilter = Para.getInstance(PasswordAuthFilter.class);
		openidFilter = Para.getInstance(OpenIDAuthFilter.class);
		facebookFilter = Para.getInstance(FacebookAuthFilter.class);
		googleFilter = Para.getInstance(GoogleAuthFilter.class);
		linkedinFilter = Para.getInstance(LinkedInAuthFilter.class);
		twitterFilter = Para.getInstance(TwitterAuthFilter.class);
		githubFilter = Para.getInstance(GitHubAuthFilter.class);
		jwtFilter = Para.getInstance(JWTRestfulAuthFilter.class);
	}

	/**
	 * Configures the authentication providers
	 *
	 * @param auth a builder
	 * @throws Exception ex
	 */
	@Override
	protected void configure(AuthenticationManagerBuilder auth) throws Exception {
		OpenIDAuthenticationProvider openidProvider = new OpenIDAuthenticationProvider();
		openidProvider.setAuthenticationUserDetailsService(new SimpleUserService());
		auth.authenticationProvider(openidProvider);

		RememberMeAuthenticationProvider rmeProvider = new RememberMeAuthenticationProvider(Config.APP_SECRET_KEY);
		auth.authenticationProvider(rmeProvider);

		JWTAuthenticationProvider jwtProvider = new JWTAuthenticationProvider();
		auth.authenticationProvider(jwtProvider);
	}

	/**
	 * Configures the unsecured public resources
	 *
	 * @param web web sec object
	 * @throws Exception ex
	 */
	@Override
	public void configure(WebSecurity web) throws Exception {
		web.ignoring().requestMatchers(IgnoredRequestMatcher.INSTANCE);
		//web.debug(true);
	}

	/**
	 * Configures the protected private resources
	 *
	 * @param http HTTP sec object
	 * @throws Exception ex
	 */
	@Override
	protected void configure(HttpSecurity http) throws Exception {
		String[] defRoles = {"USER", "MOD", "ADMIN", "APP"};
		Map<String, String> confMap = Config.getConfigMap();
		ConfigObject protectedResources = Config.getConfig().getObject("security.protected");
		ConfigValue apiSec = Config.getConfig().getValue("security.api_security");
		boolean enableRestFilter = apiSec != null && Boolean.TRUE.equals(apiSec.unwrapped());

		// If API security is disabled don't add the API endpoint to the list of protected resources
		if (enableRestFilter) {
			http.authorizeRequests().requestMatchers(RestRequestMatcher.INSTANCE);
		}

		for (String key : protectedResources.keySet()) {
			ConfigValue cv = protectedResources.get(key);
			LinkedList<String> patterns = new LinkedList<String>();
			LinkedList<String> roles = new LinkedList<String>();

			for (ConfigValue configValue : (ConfigList) cv) {
				if (configValue instanceof List) {
					for (ConfigValue role : (ConfigList) configValue) {
						roles.add(((String) role.unwrapped()).toUpperCase());
					}
				} else {
					patterns.add((String) configValue.unwrapped());
				}
			}
			String[] rolz = (roles.isEmpty()) ? defRoles : roles.toArray(new String[0]);
			http.authorizeRequests().antMatchers(patterns.toArray(new String[0])).hasAnyRole(rolz);
		}

		if (Config.getConfigParamUnwrapped("security.csrf_protection", true)) {
			http.csrf().requireCsrfProtectionMatcher(CsrfProtectionRequestMatcher.INSTANCE).
					csrfTokenRepository(csrfTokenRepository);
		} else {
			http.csrf().disable();
		}

		http.sessionManagement().enableSessionUrlRewriting(false);
		http.sessionManagement().sessionCreationPolicy(SessionCreationPolicy.NEVER);
		http.sessionManagement().sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy());
		http.exceptionHandling().authenticationEntryPoint(new SimpleAuthenticationEntryPoint(confMap.get("security.signin")));
		http.exceptionHandling().accessDeniedHandler(new SimpleAccessDeniedHandler(confMap.get("security.access_denied")));
		http.requestCache().requestCache(new SimpleRequestCache());
		http.logout().logoutUrl(confMap.get("security.signout")).logoutSuccessUrl(confMap.get("security.signout_success"));
		http.rememberMe().rememberMeServices(rememberMeServices);

		if (passwordFilter != null) {
			passwordFilter.setAuthenticationManager(authenticationManager());
			http.addFilterAfter(passwordFilter, BasicAuthenticationFilter.class);
		}

		if (openidFilter != null) {
			openidFilter.setAuthenticationManager(authenticationManager());
			http.addFilterAfter(openidFilter, BasicAuthenticationFilter.class);
		}

		if (facebookFilter != null) {
			facebookFilter.setAuthenticationManager(authenticationManager());
			http.addFilterAfter(facebookFilter, BasicAuthenticationFilter.class);
		}

		if (googleFilter != null) {
			googleFilter.setAuthenticationManager(authenticationManager());
			http.addFilterAfter(googleFilter, BasicAuthenticationFilter.class);
		}

		if (linkedinFilter != null) {
			linkedinFilter.setAuthenticationManager(authenticationManager());
			http.addFilterAfter(linkedinFilter, BasicAuthenticationFilter.class);
		}

		if (twitterFilter != null) {
			twitterFilter.setAuthenticationManager(authenticationManager());
			http.addFilterAfter(twitterFilter, BasicAuthenticationFilter.class);
		}

		if (githubFilter != null) {
			githubFilter.setAuthenticationManager(authenticationManager());
			http.addFilterAfter(githubFilter, BasicAuthenticationFilter.class);
		}

		if (enableRestFilter) {
			if (jwtFilter != null) {
				jwtFilter.setAuthenticationManager(authenticationManager());
				http.addFilterAfter(jwtFilter, RememberMeAuthenticationFilter.class);
			}
			RestAuthFilter restFilter = new RestAuthFilter(new Signer());
			http.addFilterAfter(restFilter, JWTRestfulAuthFilter.class);
		}
	}

}
