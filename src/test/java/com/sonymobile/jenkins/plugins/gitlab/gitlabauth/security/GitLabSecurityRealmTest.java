/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Andreas Alanko, Emil Nilsson, Sony Mobile Communications AB. 
 * All rights reserved.
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

package com.sonymobile.jenkins.plugins.gitlab.gitlabauth.security;

import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.sonymobile.jenkins.plugins.gitlab.gitlabapi.GitLabConfiguration;

import hudson.security.SecurityRealm;
import jenkins.model.Jenkins;

import org.acegisecurity.Authentication;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.jvnet.hudson.test.JenkinsRule;

import java.util.concurrent.Callable;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;

/**
 * Unit tests for {@link GitLabSecurityRealm}.
 *
 * Tests logging in on the system with GitLab authentication.
 * The test cases uses a mock GitLab server to authenticate against.
 *
 * @author Emil Nilsson
 */
public class GitLabSecurityRealmTest {
    /** The port to run the mocked GitLab server on. */
    // fixme: allow setting port from command line to prevent collisions
    private final static int GITLAB_PORT = 9090;

    /** A rule for creating a Jenkins environment. */
    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    /** A rule for mocking the GitLab server API. */
    @Rule
    public WireMockRule wiremockRule = new WireMockRule(GITLAB_PORT);

    /** A rule for catching expected exceptions. */
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    /** The GitLab security realm to use. */
    private SecurityRealm securityRealm;

    /** The Jenkins instance. */
    private Jenkins jenkins;

    /** The Jenkins web client. */
    private JenkinsRule.WebClient webClient;

    /**
     * Set up the Jenkins environment and security realm.
     */
    @Before
    public void setUp() {
        jenkins = jenkinsRule.jenkins;
        webClient = jenkinsRule.createWebClient();

        // configure the GitLab API plugin
        configureApi();
        // configure GitLab authentication
        configureSecurityRealm();
    }

    /**
     * Test authenticating a user logging in using valid credentials.
     */
    @Test
    public void authenticateWithValidCredentials() throws Exception {
        // make GitLab respond with the current user for connection test
        stubFor(get(urlEqualTo("/api/v3/user?private_token=private_token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBodyFile("/api/v3/user.json")));

        // make GitLab respond with a valid session
        stubFor(post(urlEqualTo("/api/v3/session"))
                .withRequestBody(containing("login=username"))
                .withRequestBody(containing("password=password"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withBodyFile("/api/v3/session.json")));

        webClient.login("username", "password");

        // get authentication from the web client
        Authentication authentication = getAuthentication();
        assertThat("User should be logged in", authentication, is(not(Jenkins.ANONYMOUS)));
        assertThat(authentication.getPrincipal(), is(instanceOf(GitLabUserDetails.class)));

        // get the authenticated user
        GitLabUserDetails user = (GitLabUserDetails)authentication.getPrincipal();
        assertThat("username", is(user.getUsername()));
        assertThat(2, is(user.getId()));
        assertThat("user@example.com", is(user.getEmail()));
        assertThat("0123456789abcdef", is(user.getPrivateToken()));
    }

    /**
     * Test authenticating a user logging in using invalid credentials.
     */
    @Test
    public void authenticateWithInvalidCredentials() throws Exception {
        // make GitLab respond with the current user for connection test
        stubFor(get(urlEqualTo("/api/v3/user?private_token=private_token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBodyFile("/api/v3/user.json")));

        // make GitLab respond with an HTTP 401 Unauthorized
        stubFor(post(urlEqualTo("/api/v3/session"))
                .withRequestBody(containing("login=invalidusername"))
                .withRequestBody(containing("password=invalidpassword"))
                .willReturn(aResponse()
                        .withStatus(401)));

        // login should fail with HTTP 401 Unauthorized
        thrown.expect(FailingHttpStatusCodeException.class);
        thrown.expectMessage("401");

        webClient.login("invalidusername", "invalidpassword");
    }

    /**
     * Configures the GitLab API plugin of the Jenkins instance.
     */
    private void configureApi() {
        GitLabConfiguration config = jenkinsRule.get(GitLabConfiguration.class).getInstance();
        config.setServerUrl("http://localhost:" + GITLAB_PORT);
        // private token can be anything
        config.setPrivateToken("private_token");
    }

    /**
     * Configures the Jenkins instance to authenticate against GitLab.
     */
    private void configureSecurityRealm() {
        // use GitLab authentication
        securityRealm = new GitLabSecurityRealm();
        jenkins.setSecurityRealm(securityRealm);
    }

    /**
     * Gets the authentication object from the web client.
     *
     * @return the authentication object
     */
    private Authentication getAuthentication() {
        try {
            return webClient.executeOnServer(new Callable<Authentication>() {
                public Authentication call() throws Exception {
                    return jenkins.getAuthentication();
                }
            });
        } catch (Exception e) {
            // safely ignore all exceptions, the method never throws anything
            return null;
        }

    }
}
