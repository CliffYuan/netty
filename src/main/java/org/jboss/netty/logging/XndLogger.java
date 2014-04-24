package org.jboss.netty.logging;

/**
 * Created by yuanyuanming on 14-4-23.
 */
public class XndLogger {

    public static void startServer(String msg){
        System.out.println("服务端-启动-"+msg);
    }

    public static void processServer(String msg){
        System.out.println("服务端-处理-"+msg);
    }

    public static void startClient(String msg){
        System.out.println("客户端-启动-"+msg);
    }

    public static void processClient(String msg){
        System.out.println("客户端-处理-"+msg);
    }

    public static void start(String msg){
        System.out.println("启动      -"+msg);
    }

    public static void process(String msg){
        System.out.println("处理      -"+msg);
    }
}
