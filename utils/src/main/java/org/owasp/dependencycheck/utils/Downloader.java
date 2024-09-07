/*
 * This file is part of dependency-check-utils.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) 2024 Hans Aikema. All Rights Reserved.
 */
package org.owasp.dependencycheck.utils;

import org.apache.hc.client5.http.HttpResponseException;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.CredentialsProviderBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.Method;
import org.apache.hc.core5.http.message.BasicClassicHttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import static java.lang.String.format;

public final class Downloader {

    /**
     * The builder to use for a HTTP Client that uses the configured proxy-settings
     */
    private final HttpClientBuilder httpClientBuilder;

    /**
     * The builder to use for a HTTP Client that explicitly opts out of proxy-usage
     */
    private final HttpClientBuilder httpClientBuilderExplicitNoproxy;


    /**
     * The settings
     */
    private Settings settings;

    /**
     * The Logger for use throughout the class.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(Downloader.class);

    /**
     * The singleton instance of the downloader
     */
    private static final Downloader INSTANCE = new Downloader();

    private Downloader() {
        // Singleton class
        final PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        //TODO: ensure proper closure and eviction policy
        httpClientBuilder = HttpClientBuilder.create().useSystemProperties().setConnectionManager(connectionManager).setConnectionManagerShared(true);
        httpClientBuilderExplicitNoproxy = HttpClientBuilder.create().setConnectionManager(connectionManager).setConnectionManagerShared(true);
    }

    /**
     * The singleton instance for downloading file resources.
     *
     * @return The singleton instance managing download credentials and proxy configuration
     */
    public static Downloader getInstance() {
        return INSTANCE;
    }

    /**
     * Initialize the Downloader from the settings.
     * Extracts the configured proxy- and credential information from the settings and system properties and
     * caches those for future use by the Downloader.
     *
     * @param settings The settings to configure from
     * @throws InvalidSettingException When improper configurations are found.
     */
    public void configure(Settings settings) throws InvalidSettingException {
        this.settings = settings;
        final CredentialsProviderBuilder credentialsProviderBuilder = CredentialsProviderBuilder.create();
        if (settings.getString(Settings.KEYS.PROXY_SERVER) != null) {
            // Legacy proxy configuration present
            // So don't rely on the system properties for proxy; use the legacy settings configuration
            final String proxyHost = settings.getString(Settings.KEYS.PROXY_SERVER);
            final int proxyPort = settings.getInt(Settings.KEYS.PROXY_PORT, -1);
            httpClientBuilder.setProxy(new HttpHost(proxyHost, proxyPort));
            if (settings.getString(Settings.KEYS.PROXY_USERNAME) != null) {
                final String proxyuser = settings.getString(Settings.KEYS.PROXY_USERNAME);
                final char[] proxypass = settings.getString(Settings.KEYS.PROXY_PASSWORD).toCharArray();
                credentialsProviderBuilder.add(new AuthScope(null, proxyHost, proxyPort, null, null), proxyuser, proxypass);
            }
        }
        tryAddRetireJSCredentials(settings, credentialsProviderBuilder);
        tryAddHostedSuppressionCredentials(settings, credentialsProviderBuilder);
        tryAddKEVCredentials(settings, credentialsProviderBuilder);
        tryAddNexusAnalyzerCredentials(settings, credentialsProviderBuilder);
        httpClientBuilder.setDefaultCredentialsProvider(credentialsProviderBuilder.build());
        httpClientBuilderExplicitNoproxy.setDefaultCredentialsProvider(credentialsProviderBuilder.build());
    }

    private void tryAddRetireJSCredentials(Settings settings, CredentialsProviderBuilder credentialsProviderBuilder) throws InvalidSettingException {
        if (settings.getString(Settings.KEYS.ANALYZER_RETIREJS_REPO_JS_PASSWORD) != null) {
            validateAndAddUsernamePasswordCredentials(settings, credentialsProviderBuilder,
                    Settings.KEYS.ANALYZER_RETIREJS_REPO_JS_USER,
                    Settings.KEYS.ANALYZER_RETIREJS_REPO_JS_URL,
                    Settings.KEYS.ANALYZER_RETIREJS_REPO_JS_PASSWORD,
                    "RetireJS repo.js");
        }
    }

    private void tryAddHostedSuppressionCredentials(Settings settings, CredentialsProviderBuilder credentialsProviderBuilder) throws InvalidSettingException {
        if (settings.getString(Settings.KEYS.HOSTED_SUPPRESSIONS_PASSWORD) != null) {
            validateAndAddUsernamePasswordCredentials(settings, credentialsProviderBuilder,
                    Settings.KEYS.HOSTED_SUPPRESSIONS_USER,
                    Settings.KEYS.HOSTED_SUPPRESSIONS_URL,
                    Settings.KEYS.HOSTED_SUPPRESSIONS_PASSWORD,
                    "Hosted suppressions");
        }
    }

    private void tryAddKEVCredentials(Settings settings, CredentialsProviderBuilder credProviderBuilder) throws InvalidSettingException {
        if (settings.getString(Settings.KEYS.KEV_PASSWORD) != null) {
            validateAndAddUsernamePasswordCredentials(settings, credProviderBuilder,
                    Settings.KEYS.KEV_USER,
                    Settings.KEYS.KEV_URL,
                    Settings.KEYS.KEV_PASSWORD,
                    "Known Exploited Vulnerabilities");
        }
    }

    private void tryAddNexusAnalyzerCredentials(Settings settings, CredentialsProviderBuilder credProviderBuilder) throws InvalidSettingException {
        if (settings.getString(Settings.KEYS.ANALYZER_NEXUS_PASSWORD) != null) {
            validateAndAddUsernamePasswordCredentials(settings, credProviderBuilder,
                    Settings.KEYS.ANALYZER_NEXUS_URL,
                    Settings.KEYS.ANALYZER_NEXUS_USER,
                    Settings.KEYS.ANALYZER_NEXUS_PASSWORD,
                    "Nexus Analyzer");
        }
    }

    private void tryAddNVDApiDatafeed(Settings settings, CredentialsProviderBuilder credProviderBuilder) throws InvalidSettingException {
        if (settings.getString(Settings.KEYS.NVD_API_DATAFEED_PASSWORD) != null) {
            validateAndAddUsernamePasswordCredentials(settings, credProviderBuilder,
                    Settings.KEYS.NVD_API_DATAFEED_URL,
                    Settings.KEYS.NVD_API_DATAFEED_USER,
                    Settings.KEYS.NVD_API_DATAFEED_PASSWORD,
                    "NVD API Datafeed");
        }
    }

    private void validateAndAddUsernamePasswordCredentials(Settings settings, CredentialsProviderBuilder credentialsProviderBuilder, String userKey, String urlKey, String passwordKey, String messageScope) throws InvalidSettingException {
        final String theUser = settings.getString(userKey);
        final String theURL = settings.getString(urlKey);
        final char[] thePass = settings.getString(passwordKey, "").toCharArray();
        if (theUser == null || theURL == null || thePass.length == 0) {
            throw new InvalidSettingException(messageScope + " URL and username are required when setting " + messageScope + " password");
        }
        try {
            final URL parsedURL = new URL(theURL);
            addCredentials(credentialsProviderBuilder, messageScope, parsedURL, theUser, thePass);
        } catch (MalformedURLException e) {
            throw new InvalidSettingException(messageScope + " URL must be a valid URL", e);
        }
    }

    private static void addCredentials(CredentialsProviderBuilder credentialsProviderBuilder, String messageScope, URL parsedURL, String theUser, char[] thePass) throws InvalidSettingException {
        final String theProtocol = parsedURL.getProtocol();
        if ("file".equals(theProtocol)) {
            LOGGER.warn("Credentials are not supported for file-protocol, double-check your configuration options for {}.", messageScope);
            return;
        } else if ("http".equals(theProtocol)) {
            LOGGER.warn("Insecure configuration: Basic Credentials are configured to be used over a plain http connection for {}. Consider migrating to https to guard the credentials.", messageScope);
        } else if (!"https".equals(theProtocol)) {
            throw new InvalidSettingException("Unsupported protocol in the " + messageScope + " URL; only file://, http:// and https:// are supported");
        }
        final String theHost = parsedURL.getHost();
        final int thePort = parsedURL.getPort();
        final Credentials creds = new UsernamePasswordCredentials(theUser, thePass);
        final AuthScope scope = new AuthScope(theProtocol, theHost, thePort, null, null);
        credentialsProviderBuilder.add(scope, creds);
    }

    /**
     * Retrieves a file from a given URL and saves it to the outputPath.
     *
     * @param url        the URL of the file to download
     * @param outputPath the path to the save the file to
     * @throws org.owasp.dependencycheck.utils.DownloadFailedException is thrown
     *                                                                 if there is an error downloading the file
     * @throws TooManyRequestsException                                thrown when a 429 is received
     * @throws ResourceNotFoundException                               thrown when a 404 is received
     */
    public void fetchFile(URL url, File outputPath) throws DownloadFailedException, TooManyRequestsException, ResourceNotFoundException {
        fetchFile(url, outputPath, true);
    }

    /**
     * Retrieves a file from a given URL and saves it to the outputPath.
     *
     * @param url        the URL of the file to download
     * @param outputPath the path to the save the file to
     * @param useProxy   whether to use the configured proxy when downloading
     *                   files
     * @throws org.owasp.dependencycheck.utils.DownloadFailedException is thrown
     *                                                                 if there is an error downloading the file
     * @throws TooManyRequestsException                                thrown when a 429 is received
     * @throws ResourceNotFoundException                               thrown when a 404 is received
     */
    public void fetchFile(URL url, File outputPath, boolean useProxy) throws DownloadFailedException,
            TooManyRequestsException, ResourceNotFoundException {
        try {
            if ("file".equals(url.getProtocol())) {
                final Path p = Paths.get(url.toURI());
                Files.copy(p, outputPath.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                final BasicClassicHttpRequest req;
                req = new BasicClassicHttpRequest(Method.GET, url.toURI());
                try (CloseableHttpClient hc = useProxy ? httpClientBuilder.build() : httpClientBuilderExplicitNoproxy.build()) {
                    final SaveToFileResponseHandler responseHandler = new SaveToFileResponseHandler(outputPath);
                    hc.execute(req, responseHandler);
                }
            }
        } catch (HttpResponseException hre) {
            final String messageFormat = "%s - Server status: %d - Server reason: %s";
            switch (hre.getStatusCode()) {
                case 404:
                    throw new ResourceNotFoundException(String.format(messageFormat, url, hre.getStatusCode(), hre.getReasonPhrase()));
                case 429:
                    throw new TooManyRequestsException(String.format(messageFormat, url, hre.getStatusCode(), hre.getReasonPhrase()));
                default:
                    throw new DownloadFailedException(String.format(messageFormat, url, hre.getStatusCode(), hre.getReasonPhrase()));
            }
        } catch (RuntimeException | URISyntaxException | IOException ex) {
            final String msg = format("Download failed, unable to copy '%s' to '%s'; %s", url, outputPath.getAbsolutePath(), ex.getMessage());
            throw new DownloadFailedException(msg, ex);
        }
    }

    /**
     * Retrieves a file from a given URL using an ad-hoc created CredentialsProvider if needed
     * and saves it to the outputPath.
     *
     * @param url         the URL of the file to download
     * @param outputPath  the path to the save the file to
     * @param useProxy    whether to use the configured proxy when downloading
     *                    files
     * @param userKey     the settings key for the username to be used
     * @param passwordKey the settings key for the password to be used
     * @throws org.owasp.dependencycheck.utils.DownloadFailedException is thrown
     *                                                                 if there is an error downloading the file
     * @throws TooManyRequestsException                                thrown when a 429 is received
     * @throws ResourceNotFoundException                               thrown when a 404 is received
     * @implNote This method should only be used in cases where the URL cannot be
     * determined beforehand from settings, so that ad-hoc Credentials needs to
     * be constructed for the target URL when the user/password keys point to configured credentials.
     * The method delegates to {@link #fetchFile(URL, File, boolean)} when credentials are not configured for the given keys or the resource points to a file.
     */
    public void fetchFile(URL url, File outputPath, boolean useProxy, String userKey, String passwordKey) throws DownloadFailedException,
            TooManyRequestsException, ResourceNotFoundException {
        if ("file".equals(url.getProtocol())
                || userKey == null || settings.getString(userKey) == null
                || passwordKey == null || settings.getString(passwordKey) == null
        ) {
            // no credentials configured, so use the default fetchFile
            fetchFile(url, outputPath, useProxy);
            return;
        }
        final String theProtocol = url.getProtocol();
        if (!("http".equals(theProtocol) || "https".equals(theProtocol))) {
            throw new DownloadFailedException("Unsupported protocol in the URL; only file://, http:// and https:// are supported");
        }
        try {
            final HttpClientContext context = HttpClientContext.create();
            final CredentialsProviderBuilder credBuilder = new CredentialsProviderBuilder();
            addCredentials(credBuilder, url.toExternalForm(), url, settings.getString(userKey), settings.getString(passwordKey).toCharArray());
            context.setCredentialsProvider(credBuilder.build());
            try (CloseableHttpClient hc = useProxy ? httpClientBuilder.build() : httpClientBuilderExplicitNoproxy.build()) {
                final BasicClassicHttpRequest req = new BasicClassicHttpRequest(Method.GET, url.toURI());
                final SaveToFileResponseHandler responseHandler = new SaveToFileResponseHandler(outputPath);
                hc.execute(req, context, responseHandler);
            }
        } catch (HttpResponseException hre) {
            final String messageFormat = "%s - Server status: %d - Server reason: %s";
            switch (hre.getStatusCode()) {
                case 404:
                    throw new ResourceNotFoundException(String.format(messageFormat, url, hre.getStatusCode(), hre.getReasonPhrase()));
                case 429:
                    throw new TooManyRequestsException(String.format(messageFormat, url, hre.getStatusCode(), hre.getReasonPhrase()));
                default:
                    throw new DownloadFailedException(String.format(messageFormat, url, hre.getStatusCode(), hre.getReasonPhrase()));
            }
        } catch (RuntimeException | URISyntaxException | IOException ex) {
            final String msg = format("Download failed, unable to copy '%s' to '%s'; %s", url, outputPath.getAbsolutePath(), ex.getMessage());
            throw new DownloadFailedException(msg, ex);
        }
    }

    /**
     * Retrieves a file from a given URL and returns the contents.
     *
     * @param url         the URL of the file to download
     * @param charset    The characterset to use to interpret the binary content of the file
     * @return the content of the file
     * @throws DownloadFailedException   is thrown if there is an error
     *                                   downloading the file
     * @throws TooManyRequestsException  thrown when a 429 is received
     * @throws ResourceNotFoundException thrown when a 404 is received
     */
    public String fetchContent(URL url, Charset charset) throws DownloadFailedException, TooManyRequestsException, ResourceNotFoundException {
        return fetchContent(url, true, charset);
    }

    /**
     * Retrieves a file from a given URL and returns the contents.
     *
     * @param url         the URL of the file to download
     * @param useProxy    whether to use the configured proxy when downloading
     *                    files
     * @param charset    The characterset to use to interpret the binary content of the file
     * @return the content of the file
     * @throws DownloadFailedException   is thrown if there is an error
     *                                   downloading the file
     * @throws TooManyRequestsException  thrown when a 429 is received
     * @throws ResourceNotFoundException thrown when a 404 is received
     */
    public String fetchContent(URL url, boolean useProxy, Charset charset)
            throws DownloadFailedException, TooManyRequestsException, ResourceNotFoundException {
        String result = "";
        try {
            if ("file".equals(url.getProtocol())) {
                final Path p = Paths.get(url.toURI());
                result = new String(Files.readAllBytes(p), charset);
            } else {
                final BasicClassicHttpRequest req;
                req = new BasicClassicHttpRequest(Method.GET, url.toURI());
                try (CloseableHttpClient hc = useProxy ? httpClientBuilder.build() : httpClientBuilderExplicitNoproxy.build()) {
                    final ExplicitEncodingToStringResponseHandler responseHandler = new ExplicitEncodingToStringResponseHandler(charset);
                    result = hc.execute(req, responseHandler);
                }
            }
        } catch (HttpResponseException hre) {
            final String messageFormat = "%s - Server status: %d - Server reason: %s";
            switch (hre.getStatusCode()) {
                case 404:
                    throw new ResourceNotFoundException(String.format(messageFormat, url, hre.getStatusCode(), hre.getReasonPhrase()));
                case 429:
                    throw new TooManyRequestsException(String.format(messageFormat, url, hre.getStatusCode(), hre.getReasonPhrase()));
                default:
                    throw new DownloadFailedException(String.format(messageFormat, url, hre.getStatusCode(), hre.getReasonPhrase()));
            }
        } catch (RuntimeException | URISyntaxException | IOException ex) {
            final String msg = format("Download failed, error downloading '%s'; %s", url, ex.getMessage());
            throw new DownloadFailedException(msg, ex);
        }
        return result;
    }

}
