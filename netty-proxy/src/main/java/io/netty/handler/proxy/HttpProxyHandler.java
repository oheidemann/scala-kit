/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.handler.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.InvalidParameterException;

public final class HttpProxyHandler extends ProxyHandler {

    private static final String AUTH_BASIC = "basic";

    private final HttpClientCodec2 codec = new HttpClientCodec2();
    private final String protocol;
    private final String username;
    private final String password;
    private final CharSequence authorization;
    private HttpResponseStatus status;

    public HttpProxyHandler(SocketAddress proxyAddress) {
        this(proxyAddress, "http");
    }

    public HttpProxyHandler(SocketAddress proxyAddress, String protocol) {
        super(proxyAddress);
        this.protocol = protocol;
        this.username = null;
        this.password = null;
        this.authorization = null;
    }

    public HttpProxyHandler(SocketAddress proxyAddress, String username, String password) {
        this(proxyAddress, username, password, "http");
    }

    public HttpProxyHandler(SocketAddress proxyAddress, String username, String password, String protocol) {
        super(proxyAddress);
        if (username == null) {
            throw new NullPointerException("username");
        }
        if (password == null) {
            throw new NullPointerException("password");
        }
        if (!"http".equals(protocol) && !"https".equals(protocol)) {
            throw new InvalidParameterException("protocol should be http or https");
        }
        this.username = username;
        this.password = password;
        this.protocol = protocol;

        ByteBuf authz = Unpooled.copiedBuffer(username + ':' + password, CharsetUtil.UTF_8);
        ByteBuf authzBase64 = Base64.encode(authz, false);

        authorization = "Basic " + authzBase64.toString(CharsetUtil.US_ASCII);

        authz.release();
        authzBase64.release();
    }

    @Override
    public String protocol(){
        return protocol;
    }

    @Override
    public String authScheme() {
        return authorization != null? AUTH_BASIC : AUTH_NONE;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    @Override
    protected void addCodec(ChannelHandlerContext ctx) throws Exception {
        ChannelPipeline p = ctx.pipeline();
        String name = ctx.name();
        p.addBefore(name, name + ".codec", codec);
    }

    @Override
    protected void removeEncoder(ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().remove(codec.encoder());
    }

    @Override
    protected void removeDecoder(ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().remove(codec.decoder());
    }

    @Override
    protected Object newInitialMessage(ChannelHandlerContext ctx) throws Exception {
        InetSocketAddress raddr = destinationAddress();
        String rhost;
        if (raddr.isUnresolved()) {
            rhost = raddr.getHostString();
        } else {
            rhost = raddr.getAddress().getHostAddress();
        }

        FullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_0, HttpMethod.CONNECT,
                rhost + ':' + raddr.getPort(),
                Unpooled.EMPTY_BUFFER, false);

        SocketAddress proxyAddress = proxyAddress();
        if (proxyAddress instanceof InetSocketAddress) {
            InetSocketAddress hostAddr = (InetSocketAddress) proxyAddress;
            req.headers().set(HttpHeaders.Names.HOST, hostAddr.getHostString() + ':' + hostAddr.getPort());
        }

        if (authorization != null) {
            req.headers().set(HttpHeaders.Names.PROXY_AUTHORIZATION, authorization);
        }

        return req;
    }

    @Override
    protected boolean handleResponse(ChannelHandlerContext ctx, Object response) throws Exception {
        if (response instanceof HttpResponse) {
            if (status != null) {
                throw new ProxyConnectException(exceptionMessage("too many responses"));
            }
            status = ((HttpResponse) response).getStatus();
        }

        boolean finished = response instanceof LastHttpContent;
        if (finished) {
            if (status == null) {
                throw new ProxyConnectException(exceptionMessage("missing response"));
            }
            if (status.code() != 200) {
                throw new ProxyConnectException(exceptionMessage("status: " + status));
            }
        }

        return finished;
    }
}
