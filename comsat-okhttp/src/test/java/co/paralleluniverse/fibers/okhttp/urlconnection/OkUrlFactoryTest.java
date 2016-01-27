/*
 * COMSAT
 * Copyright (c) 2013-2015, Parallel Universe Software Co. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
/*
 * Based on the corresponding class in okhttp-urlconnection.
 * Copyright 2014 Square, Inc.
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package co.paralleluniverse.fibers.okhttp.urlconnection;

import co.paralleluniverse.fibers.okhttp.FiberOkHttpClient;
import co.paralleluniverse.fibers.okhttp.FiberOkHttpUtil;
import com.squareup.okhttp.Cache;
import com.squareup.okhttp.OkUrlFactory;
import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.rule.MockWebServerRule;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static okio.Okio.buffer;
import static okio.Okio.source;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class OkUrlFactoryTest {
  @Rule public MockWebServerRule serverRule = new MockWebServerRule();
  @Rule public TemporaryFolder cacheFolder = new TemporaryFolder();

  private MockWebServer server;
  private OkUrlFactory factory;

  @Before public void setUp() throws IOException {
    server = serverRule.get();

    FiberOkHttpClient client = new FiberOkHttpClient();
    client.setCache(new Cache(cacheFolder.getRoot(), 10 * 1024 * 1024));
    factory = new OkUrlFactory(client);
  }

  /**
   * Response code 407 should only come from proxy servers. Android's client
   * throws if it is sent by an origin server.
   */
  @Test public void originServerSends407() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(407));

    HttpURLConnection conn = FiberOkHttpUtil.openInFiber(factory, server.getUrl("/"));
    try {
      conn.getResponseCode();
      fail();
    } catch (IOException ignored) {
    }
  }

  @Test public void networkResponseSourceHeader() throws Exception {
    server.enqueue(new MockResponse().setBody("Isla Sorna"));

    HttpURLConnection connection = FiberOkHttpUtil.openInFiber(factory, server.getUrl("/"));
    assertResponseHeader(connection, "NETWORK 200");
    assertResponseBody(connection, "Isla Sorna");
  }

  @Test public void networkFailureResponseSourceHeader() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(404));

    HttpURLConnection connection = FiberOkHttpUtil.openInFiber(factory, server.getUrl("/"));
    assertResponseHeader(connection, "NETWORK 404");
  }

  @Test public void conditionalCacheHitResponseSourceHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("Isla Nublar"));
    server.enqueue(new MockResponse().setResponseCode(304));

    HttpURLConnection connection1 = FiberOkHttpUtil.openInFiber(factory, server.getUrl("/"));
    assertResponseHeader(connection1, "NETWORK 200");
    assertResponseBody(connection1, "Isla Nublar");

    HttpURLConnection connection2 = FiberOkHttpUtil.openInFiber(factory, server.getUrl("/"));
    assertResponseHeader(connection2, "CONDITIONAL_CACHE 304");
    assertResponseBody(connection2, "Isla Nublar");
  }

  @Test public void conditionalCacheMissResponseSourceHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Last-Modified: " + formatDate(0, TimeUnit.SECONDS))
        .addHeader("Cache-Control: max-age=0")
        .setBody("Isla Nublar"));
    server.enqueue(new MockResponse().setBody("Isla Sorna"));

    HttpURLConnection connection1 = FiberOkHttpUtil.openInFiber(factory, server.getUrl("/"));
    assertResponseHeader(connection1, "NETWORK 200");
    assertResponseBody(connection1, "Isla Nublar");

    HttpURLConnection connection2 = FiberOkHttpUtil.openInFiber(factory, server.getUrl("/"));
    assertResponseHeader(connection2, "CONDITIONAL_CACHE 200");
    assertResponseBody(connection2, "Isla Sorna");
  }

  @Test public void cacheResponseSourceHeaders() throws Exception {
    server.enqueue(new MockResponse()
        .addHeader("Expires: " + formatDate(2, TimeUnit.HOURS))
        .setBody("Isla Nublar"));

    HttpURLConnection connection1 = FiberOkHttpUtil.openInFiber(factory, server.getUrl("/"));
    assertResponseHeader(connection1, "NETWORK 200");
    assertResponseBody(connection1, "Isla Nublar");

    HttpURLConnection connection2 = FiberOkHttpUtil.openInFiber(factory, server.getUrl("/"));
    assertResponseHeader(connection2, "CACHE 200");
    assertResponseBody(connection2, "Isla Nublar");
  }

  @Test public void noneResponseSourceHeaders() throws Exception {
    server.enqueue(new MockResponse().setBody("Isla Nublar"));

    HttpURLConnection connection1 = FiberOkHttpUtil.openInFiber(factory, server.getUrl("/"));
    assertResponseHeader(connection1, "NETWORK 200");
    assertResponseBody(connection1, "Isla Nublar");

    HttpURLConnection connection2 = FiberOkHttpUtil.openInFiber(factory, server.getUrl("/"));
    connection2.setRequestProperty("Cache-Control", "only-if-cached");
    assertResponseHeader(connection2, "NONE");
  }

  @Test
  public void setInstanceFollowRedirectsFalse() throws Exception {
    server.enqueue(new MockResponse()
        .setResponseCode(302)
        .addHeader("Location: /b")
        .setBody("A"));
    server.enqueue(new MockResponse()
        .setBody("B"));

    HttpURLConnection connection = FiberOkHttpUtil.openInFiber(factory, server.getUrl("/a"));
    connection.setInstanceFollowRedirects(false);
    assertResponseBody(connection, "A");
    assertResponseCode(connection, 302);
  }

  private void assertResponseBody(HttpURLConnection connection, String expected) throws Exception {
    String actual = buffer(source(connection.getInputStream())).readString(US_ASCII);
    assertEquals(expected, actual);
  }

  private void assertResponseHeader(HttpURLConnection connection, String expected) {
    final String headerFieldPrefix = Platform.get().getPrefix();
    assertEquals(expected, connection.getHeaderField(headerFieldPrefix + "-Response-Source"));
  }

  private void assertResponseCode(HttpURLConnection connection, int expected) throws IOException {
    assertEquals(expected, connection.getResponseCode());
  }

  private static String formatDate(long delta, TimeUnit timeUnit) {
    return formatDate(new Date(System.currentTimeMillis() + timeUnit.toMillis(delta)));
  }

  private static String formatDate(Date date) {
    DateFormat rfc1123 = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
    rfc1123.setTimeZone(TimeZone.getTimeZone("GMT"));
    return rfc1123.format(date);
  }
}