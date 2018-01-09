/*
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License.  You may obtain a
 * copy of the License at the following location:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jasig.cas.client.authentication;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.jasig.cas.client.Protocol;
import org.jasig.cas.client.configuration.ConfigurationKeys;
import org.jasig.cas.client.util.AbstractCasFilter;
import org.jasig.cas.client.util.CommonUtils;
import org.jasig.cas.client.util.ReflectUtils;
import org.jasig.cas.client.validation.Assertion;
import org.jasig.cas.client.validation.AssertionImpl;

/**
 * Filter implementation to intercept all requests and attempt to authenticate
 * the user by redirecting them to CAS (unless the user has a ticket).
 * <p>
 * This filter allows you to specify the following parameters (at either the context-level or the filter-level):
 * <ul>
 * <li><code>casServerLoginUrl</code> - the url to log into CAS, i.e. https://cas.rutgers.edu/login</li>
 * <li><code>renew</code> - true/false on whether to use renew or not.</li>
 * <li><code>gateway</code> - true/false on whether to use gateway or not.</li>
 * </ul>
 *
 * <p>Please see AbstractCasFilter for additional properties.</p>
 *
 * @author Scott Battaglia
 * @author Misagh Moayyed
 * @since 3.0
 */
public class AuthenticationFilter extends AbstractCasFilter {
    /**
     * The URL to the CAS Server login.
     */
    private String casServerLoginUrl;
    
    /**
     * The first part of CAS server login URL domain.
     */
    private String casServerLoginUrlFirstPart;
    
    /**
     * The last part of CAS Server login URL domain.
     */
    private String casServerLoginUrlLastPart;

    /**
     * Whether to send the renew request or not.
     */
    private boolean renew = false;

    /**
     * Whether to send the gateway request or not.
     */
    private boolean gateway = false;

    private GatewayResolver gatewayStorage = new DefaultGatewayResolverImpl();

    private AuthenticationRedirectStrategy authenticationRedirectStrategy = new DefaultAuthenticationRedirectStrategy();
    
    private UrlPatternMatcherStrategy ignoreUrlPatternMatcherStrategyClass = null;
    
    private static final Map<String, Class<? extends UrlPatternMatcherStrategy>> PATTERN_MATCHER_TYPES =
            new HashMap<String, Class<? extends UrlPatternMatcherStrategy>>();
    
    /**
     * The constant representing the first part of CAS login URL.
     */
    public static final String CAS_LOGIN_URL_FIRST_PART = "first_part";
    
    /**
     * The constant representing the last part of CAS login URL.
     */
    public static final String CAS_LOGIN_URL_LAST_PART = "last_part";
    
    static {
        PATTERN_MATCHER_TYPES.put("CONTAINS", ContainsPatternUrlPatternMatcherStrategy.class);
        PATTERN_MATCHER_TYPES.put("REGEX", RegexUrlPatternMatcherStrategy.class);
        PATTERN_MATCHER_TYPES.put("EXACT", ExactUrlPatternMatcherStrategy.class);
    }

    public AuthenticationFilter() {
        this(Protocol.CAS2);
    }

    protected AuthenticationFilter(final Protocol protocol) {
        super(protocol);
    }
    
    protected void initInternal(final FilterConfig filterConfig) throws ServletException {
        if (!isIgnoreInitConfiguration()) {
            super.initInternal(filterConfig);
            setCasServerLoginUrl(getString(ConfigurationKeys.CAS_SERVER_LOGIN_URL));
            setRenew(getBoolean(ConfigurationKeys.RENEW));
            setGateway(getBoolean(ConfigurationKeys.GATEWAY));
            setCasServerLoginUrlDomainParts(getCasLoginUrlDomainParts(this.casServerLoginUrl));
                       
            final String ignorePattern = getString(ConfigurationKeys.IGNORE_PATTERN);
            final String ignoreUrlPatternType = getString(ConfigurationKeys.IGNORE_URL_PATTERN_TYPE);
            
            if (ignorePattern != null) {
                final Class<? extends UrlPatternMatcherStrategy> ignoreUrlMatcherClass = PATTERN_MATCHER_TYPES.get(ignoreUrlPatternType);
                if (ignoreUrlMatcherClass != null) {
                    this.ignoreUrlPatternMatcherStrategyClass = ReflectUtils.newInstance(ignoreUrlMatcherClass.getName());
                } else {
                    try {
                        logger.trace("Assuming {} is a qualified class name...", ignoreUrlPatternType);
                        this.ignoreUrlPatternMatcherStrategyClass = ReflectUtils.newInstance(ignoreUrlPatternType);
                    } catch (final IllegalArgumentException e) {
                        logger.error("Could not instantiate class [{}]", ignoreUrlPatternType, e);
                    }
                }
                if (this.ignoreUrlPatternMatcherStrategyClass != null) {
                    this.ignoreUrlPatternMatcherStrategyClass.setPattern(ignorePattern);
                }
            }
            
            final Class<? extends GatewayResolver> gatewayStorageClass = getClass(ConfigurationKeys.GATEWAY_STORAGE_CLASS);

            if (gatewayStorageClass != null) {
                setGatewayStorage(ReflectUtils.newInstance(gatewayStorageClass));
            }
            
            final Class<? extends AuthenticationRedirectStrategy> authenticationRedirectStrategyClass = getClass(ConfigurationKeys.AUTHENTICATION_REDIRECT_STRATEGY_CLASS);

            if (authenticationRedirectStrategyClass != null) {
                this.authenticationRedirectStrategy = ReflectUtils.newInstance(authenticationRedirectStrategyClass);
            }
        }
    }

    public void init() {
        super.init();
        CommonUtils.assertNotNull(this.casServerLoginUrl, "casServerLoginUrl cannot be null.");
        CommonUtils.assertNotNull(this.casServerLoginUrlFirstPart, "casServerLoginUrlFirstPart cannot be null.");
        CommonUtils.assertNotNull(this.casServerLoginUrlLastPart, "casServerLoginUrlLastPart cannot be null.");
    }

    public final void doFilter(final ServletRequest servletRequest, final ServletResponse servletResponse,
            final FilterChain filterChain) throws IOException, ServletException {
        
        final HttpServletRequest request = (HttpServletRequest) servletRequest;
        final HttpServletResponse response = (HttpServletResponse) servletResponse;
        
        if (isRequestUrlExcluded(request)) {
            logger.debug("Request is ignored.");
            filterChain.doFilter(request, response);
            return;
        }
        
        final HttpSession session = request.getSession(false);
        final Assertion assertion = session != null ? (AssertionImpl) session.getAttribute(CONST_CAS_ASSERTION) : null;

        if (assertion != null) {
            filterChain.doFilter(request, response);
            return;
        }

        final String serviceUrl = constructServiceUrl(request, response);
        final String ticket = retrieveTicketFromRequest(request);
        final boolean wasGatewayed = this.gateway && this.gatewayStorage.hasGatewayedAlready(request, serviceUrl);

        if (CommonUtils.isNotBlank(ticket) || wasGatewayed) {
            filterChain.doFilter(request, response);
            return;
        }

        final String authHeader = request.getHeader("Authorization");
        if (!CommonUtils.isBlank(authHeader)
                && authHeader.toLowerCase().startsWith("Bearer".toLowerCase() + ' ')) {
        	final String accessToken = authHeader.substring("Bearer".length() + 1);
            logger.debug("{}: {}", "access token", accessToken);
            filterChain.doFilter(request, response);
            return;
        }
        
        String modifiedServiceUrl;
        
        logger.debug("no ticket and no assertion found");
        if (this.gateway) {
            logger.debug("setting gateway attribute in session");
            modifiedServiceUrl = this.gatewayStorage.storeGatewayInformation(request, serviceUrl);
        } else {
            modifiedServiceUrl = serviceUrl;
        }
        
        logger.debug("Constructed service url: {}", modifiedServiceUrl);
        
        // To get the domain from the service URL.
        URL url = new URL(modifiedServiceUrl);
        final String newServiceUrlHost = url.getHost();
        final String newServiceUrlDomain = newServiceUrlHost.substring(newServiceUrlHost.lastIndexOf(".") + 1);
        
        // To use the instance variable as is if domain is not different.
        String modifiedCasServerLoginUrl = this.casServerLoginUrl;
        
        // If one domain is different from the other, replace it with the one in the service URL
        // since if it's different logout doesn't work well.
        if (!newServiceUrlDomain.equalsIgnoreCase(casServerLoginUrlLastPart)) {
        	url = new URL(modifiedCasServerLoginUrl);
        	final String casServerLoginUrlHost = String.format("%s.%s", casServerLoginUrlFirstPart, newServiceUrlDomain);
        	modifiedCasServerLoginUrl = CommonUtils.constructNewUrl(url.getProtocol(), casServerLoginUrlHost, url.getPort(), url.getPath());
        }

        //Modify a service url protocol from http to https
        url = new URL(modifiedServiceUrl);
        if(url.getProtocol().equalsIgnoreCase("http")) {
        	modifiedServiceUrl = CommonUtils.constructNewUrl("https", url.getHost(), url.getPort(), url.getFile());
        }
        
        final String urlToRedirectTo = CommonUtils.constructRedirectUrl(modifiedCasServerLoginUrl,
                getProtocol().getServiceParameterName(), modifiedServiceUrl, this.renew, this.gateway);
        
        // Set header values for clients to handle AJAX response.
        response.addHeader("Cas-Server-Login-Url", modifiedCasServerLoginUrl);

        logger.debug("redirecting to \"{}\"", urlToRedirectTo);
        this.authenticationRedirectStrategy.redirect(request, response, urlToRedirectTo);
    }

    public final void setRenew(final boolean renew) {
        this.renew = renew;
    }

    public final void setGateway(final boolean gateway) {
        this.gateway = gateway;
    }

    public final void setCasServerLoginUrl(final String casServerLoginUrl) {
        this.casServerLoginUrl = casServerLoginUrl;
    }
    
    public void setCasServerLoginUrlDomainParts(final Map<String, String> domainParts) {
    	setCasServerLoginUrlFirstPart(domainParts.get(CAS_LOGIN_URL_FIRST_PART));
    	setCasServerLoginUrlLastPart(domainParts.get(CAS_LOGIN_URL_LAST_PART));
	}
    
	public void setCasServerLoginUrlFirstPart(final String casServerLoginUrlFirstPart) {
		this.casServerLoginUrlFirstPart = casServerLoginUrlFirstPart;
	}

	public void setCasServerLoginUrlLastPart(final String casServerLoginUrlLastPart) {
		this.casServerLoginUrlLastPart = casServerLoginUrlLastPart;
	}

	public final void setGatewayStorage(final GatewayResolver gatewayStorage) {
        this.gatewayStorage = gatewayStorage;
    }
        
    private boolean isRequestUrlExcluded(final HttpServletRequest request) {
        if (this.ignoreUrlPatternMatcherStrategyClass == null) {
            return false;
        }
        
        final StringBuffer urlBuffer = request.getRequestURL();
        if (request.getQueryString() != null) {
            urlBuffer.append("?").append(request.getQueryString());
        }
        final String requestUri = urlBuffer.toString();
        return this.ignoreUrlPatternMatcherStrategyClass.matches(requestUri);
    }
    
    /**
     * Split the parts of the domain and returns the divided two parts.
     * 
     * @param urlValue the string of URL.
     * @return the string of the last part of domain.
     */
    private Map<String, String> getCasLoginUrlDomainParts(String urlValue) {
		try {
			URL url = new URL(urlValue);
			String urlHost = url.getHost();
			final int lastIndex = urlHost.lastIndexOf(".");
			final Map<String, String> domainParts = new HashMap<String, String>();
			domainParts.put(AuthenticationFilter.CAS_LOGIN_URL_FIRST_PART, urlHost.substring(0, lastIndex));
			domainParts.put(AuthenticationFilter.CAS_LOGIN_URL_LAST_PART, urlHost.substring(lastIndex + 1));
			return domainParts;
		} catch (MalformedURLException e) {
			throw new RuntimeException(e);
		}
	}

    public final void setIgnoreUrlPatternMatcherStrategyClass(
            final UrlPatternMatcherStrategy ignoreUrlPatternMatcherStrategyClass) {
        this.ignoreUrlPatternMatcherStrategyClass = ignoreUrlPatternMatcherStrategyClass;
    }

}
