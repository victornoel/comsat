/*
 * COMSAT
 * Copyright (C) 2014, Parallel Universe Software Co. All rights reserved.
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
package co.paralleluniverse.fibers.retrofit;

import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.fibers.Suspendable;
import co.paralleluniverse.fibers.retrofit.HelloWorldApplication.Contributor;
import co.paralleluniverse.strands.SuspendableRunnable;
import java.io.IOException;
import java.util.List;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.HttpHostConnectException;
import org.apache.http.impl.client.HttpClients;
import static org.junit.Assert.*;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import retrofit.http.GET;
import retrofit.http.Path;

public class FiberRestAdaptherBuilderTest {
    @BeforeClass
    public static void setUpClass() throws InterruptedException, IOException {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    new HelloWorldApplication().run(new String[]{"server"});
                } catch (Exception ex) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        waitUrlAvailable("http://localhost:8080");
    }

    @Test
    public void testGet() throws IOException, InterruptedException, Exception {
        final GitHub github = new FiberRestAdaptherBuilder().setEndpoint("http://localhost:8080").build().create(GitHub.class);
        new Fiber<Void>(new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                List<Contributor> contributors = github.contributors("puniverse", "comsat");
                assertEquals("puniverse", contributors.get(1).login);
                assertEquals(4, contributors.size());
            }
        }).start().join();
    }

    @Suspendable
    public static interface GitHub {
        @GET(value = "/repos/{owner}/{repo}/contributors")
        List<Contributor> contributors(@Path(value = "owner") String owner, @Path(value = "repo") String repo);
    }

    public static void waitUrlAvailable(final String url) throws InterruptedException, IOException {
        for (;;) {
            Thread.sleep(10);
            try {
                if (HttpClients.createDefault().execute(new HttpGet(url)).getStatusLine().getStatusCode() > -100)
                    break;
            } catch (HttpHostConnectException ex) {
            }
        }
    }

    @Rule
    public Timeout globalTimeout = new Timeout(10000); // 10 seconds max per method tested
}