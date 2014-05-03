package org.jboss.netty.example.xnd;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelDownstreamHandler;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;

/**
 * Created by yuanyuanming on 14-4-30.
 */
public class XndServerHandler extends SimpleChannelUpstreamHandler{

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
        System.out.println("接收信息"+e.getMessage());
        super.messageReceived(ctx, e);
    }
}
