/*
 * The MIT License
 *
 * Copyright (c) 2004-2009, Sun Microsystems, Inc.
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
package hudson.model;

import hudson.remoting.Channel;
import hudson.remoting.PingThread;
import hudson.remoting.Channel.Mode;
import org.apache.commons.io.IOUtils;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Builds a {@link Channel} on top of two HTTP streams (one used for each direction.)
 *
 * @author Kohsuke Kawaguchi
 */
abstract class FullDuplexHttpChannel {
    private Channel channel;

    private final PipedOutputStream pipe = new PipedOutputStream();

    private final UUID uuid;
    private final boolean restricted;

    public FullDuplexHttpChannel(UUID uuid, boolean restricted) throws IOException {
        this.uuid = uuid;
        this.restricted = restricted;
    }

    /**
     * This is where we send the data to the client.
     *
     * <p>
     * If this connection is lost, we'll abort the channel.
     */
    public void download(StaplerRequest req, StaplerResponse rsp) throws InterruptedException, IOException {
        rsp.setStatus(HttpServletResponse.SC_OK);

        // server->client channel.
        // this is created first, and this controls the lifespan of the channel
        rsp.addHeader("Transfer-Encoding", "chunked");
        channel = new Channel("HTTP full-duplex channel " + uuid,
                Computer.threadPoolForRemoting, Mode.BINARY, new PipedInputStream(pipe), rsp.getOutputStream(), null, restricted);

        // so that we can detect dead clients, periodically send something
        PingThread ping = new PingThread(channel) {
            @Override
            protected void onDead() {
                LOGGER.info("Duplex-HTTP session " + uuid + " is terminated");
                // this will cause the channel to abort and subsequently clean up
                try {
                    pipe.close();
                } catch (IOException e) {
                    // this can never happen
                    throw new AssertionError(e);
                }
            }
        };
        ping.start();
        main(channel);
        channel.join();
        ping.interrupt();
    }

    protected abstract void main(Channel channel) throws IOException, InterruptedException;

    /**
     * This is where we receive inputs from the client.
     */
    public void upload(StaplerRequest req, StaplerResponse rsp) throws InterruptedException, IOException {
        rsp.setStatus(HttpServletResponse.SC_OK);
        IOUtils.copy(req.getInputStream(),pipe);
    }

    public Channel getChannel() {
        return channel;
    }

    private static final Logger LOGGER = Logger.getLogger(FullDuplexHttpChannel.class.getName());
}
